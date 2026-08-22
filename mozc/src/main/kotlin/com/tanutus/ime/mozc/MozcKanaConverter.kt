package com.tanutus.ime.mozc

import android.content.Context
import com.tanutus.ime.core.conversion.Composition
import com.tanutus.ime.core.conversion.KanaConverter
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands

/**
 * [KanaConverter] backed by the real Mozc conversion engine (`libmozc.so`), speaking the same
 * session protocol as upstream Mozc clients: one long-lived session, key events fed in one at a
 * time, [Composition] read back from `Output.preedit` / `Output.all_candidate_words`.
 *
 * [nextCandidate] and [commit] map onto SPACE and ENTER key events rather than a dedicated
 * session command because that's how real Mozc clients drive conversion too: SPACE both starts
 * conversion (first press) and cycles candidates (subsequent presses), which happens to match
 * this project's own space-key "next candidate" gesture (see
 * core/gesture/SpaceKeyGestureHandler) directly.
 */
class MozcKanaConverter(context: Context) : KanaConverter {
    init {
        MozcEngine.ensureLoaded(context.applicationContext)
    }

    private var sessionId: Long = 0L
    private val rawInputBuffer = StringBuilder()
    private var composition = Composition(rawInput = "", text = "")

    override fun input(char: Char): Composition {
        rawInputBuffer.append(char)
        val output = sendKey(ProtoCommands.KeyEvent.newBuilder().setKeyCode(char.code))
        return applyOutput(output)
    }

    override fun nextCandidate(): Composition {
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.SPACE))
        return applyOutput(output)
    }

    override fun commit(): String {
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.ENTER))
        val result = if (output.hasResult()) output.result.value else composition.text
        reset()
        return result
    }

    override fun dropLast(): Composition {
        if (rawInputBuffer.isNotEmpty()) {
            rawInputBuffer.deleteCharAt(rawInputBuffer.length - 1)
        }
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.BACKSPACE))
        return applyOutput(output)
    }

    override fun hasActiveComposition(): Boolean = !composition.isEmpty

    override fun currentComposition(): Composition = composition

    override fun reset() {
        if (sessionId != 0L) {
            send(ProtoCommands.Input.newBuilder().setType(ProtoCommands.Input.CommandType.DELETE_SESSION).setId(sessionId))
            sessionId = 0L
        }
        rawInputBuffer.clear()
        composition = Composition(rawInput = "", text = "")
    }

    private fun specialKey(key: ProtoCommands.KeyEvent.SpecialKey): ProtoCommands.KeyEvent.Builder =
        ProtoCommands.KeyEvent.newBuilder().setSpecialKey(key)

    private fun sendKey(key: ProtoCommands.KeyEvent.Builder): ProtoCommands.Output {
        ensureSession()
        val input =
            ProtoCommands.Input.newBuilder()
                .setType(ProtoCommands.Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(key.setMode(ProtoCommands.CompositionMode.HIRAGANA).setActivated(true))
        return send(input)
    }

    private fun ensureSession() {
        if (sessionId != 0L) return
        val output = send(ProtoCommands.Input.newBuilder().setType(ProtoCommands.Input.CommandType.CREATE_SESSION))
        sessionId = output.id
    }

    private fun send(input: ProtoCommands.Input.Builder): ProtoCommands.Output {
        val command = ProtoCommands.Command.newBuilder().setInput(input).build()
        val responseBytes = MozcEngine.evalCommand(command.toByteArray())
        return ProtoCommands.Command.parseFrom(responseBytes).output
    }

    private fun applyOutput(output: ProtoCommands.Output): Composition {
        val text =
            if (output.hasPreedit()) {
                output.preedit.segmentList.joinToString("") { it.value }
            } else {
                ""
            }
        // Mozc computes zero-query SUGGESTION candidates on every keystroke (for a separate
        // autocomplete-style strip real clients show above the keyboard), not just after an
        // explicit conversion request. Treating those as *the* candidate list would make
        // Composition.currentCandidate — and so the text this app actually composes — jump to
        // an unrelated suggestion the moment one exists (e.g. from user history), instead of
        // the literal reading the user is typing. Only a real conversion (triggered by
        // nextCandidate()'s SPACE, see the class doc) should drive candidate selection.
        val candidateWords =
            if (output.hasAllCandidateWords() &&
                output.allCandidateWords.category != ProtoCandidateWindow.Category.SUGGESTION
            ) {
                output.allCandidateWords.candidatesList
            } else {
                emptyList()
            }
        val candidates =
            when {
                candidateWords.isNotEmpty() -> candidateWords.map { it.value }
                text.isNotEmpty() -> listOf(text)
                else -> emptyList()
            }
        val candidateIndex =
            if (candidateWords.isNotEmpty()) {
                output.allCandidateWords.focusedIndex.toInt().coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
            } else {
                0
            }
        composition =
            Composition(
                rawInput = rawInputBuffer.toString(),
                text = text,
                candidates = candidates,
                candidateIndex = candidateIndex,
            )
        return composition
    }
}
