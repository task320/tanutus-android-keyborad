package tokyo.tanutus.ime.mozc

import android.content.Context
import tokyo.tanutus.ime.core.conversion.Composition
import tokyo.tanutus.ime.core.conversion.KanaConverter
import tokyo.tanutus.ime.core.conversion.SegmentCommit
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

    // The candidate list's ids (distinct from CandidateWord.index — see commands.proto), kept
    // alongside composition.candidates so commitFocusedSegment() can tell Mozc *which* candidate
    // the focused one is via SessionCommand.SUBMIT_CANDIDATE.
    private var candidateWords: List<ProtoCandidateWindow.CandidateWord> = emptyList()

    override fun input(char: Char): Composition {
        val output = sendKey(ProtoCommands.KeyEvent.newBuilder().setKeyCode(char.code))
        // Typing while a conversion is showing makes Mozc confirm it first (session.cc,
        // Session::InsertCharacter: `should_commit = state == CONVERSION`) and start a new
        // composition from this key alone — so the raw input typed before it is no longer pending.
        if (output.hasResult()) rawInputBuffer.clear()
        rawInputBuffer.append(char)
        return applyOutput(output, surfaceResult = true)
    }

    override fun nextCandidate(): Composition {
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.SPACE))
        return applyOutput(output, surfaceResult = true)
    }

    override fun commit(): String {
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.ENTER))
        val result = if (output.hasResult()) output.result.value else composition.text
        reset()
        return result
    }

    /**
     * Confirms only the focused candidate's segment via `SessionCommand.SUBMIT_CANDIDATE` — the
     * mechanism Mozc's own docs describe for "mobile IME's partial conversion" — instead of the
     * whole-composition ENTER used by [commit]. With no real candidate list (e.g. Enter on a raw,
     * unconverted reading), there's no segment to isolate, so it falls back to the same full
     * ENTER commit as [commit].
     */
    override fun commitFocusedSegment(): SegmentCommit = commitCandidate(composition.candidateIndex)

    /**
     * Backs both [commitFocusedSegment] and the candidate bar's tap-to-select: submitting by
     * [ProtoCandidateWindow.CandidateWord.id] (not the bar's display index, which is unrelated —
     * see commands.proto) is what lets [index] be *any* candidate, not just whichever one Mozc
     * currently has focused.
     */
    override fun commitCandidate(index: Int): SegmentCommit {
        val id = candidateWords.getOrNull(index)?.id
        val output =
            if (id != null) {
                send(
                    ProtoCommands.Input.newBuilder()
                        .setType(ProtoCommands.Input.CommandType.SEND_COMMAND)
                        .setId(sessionId)
                        .setCommand(
                            ProtoCommands.SessionCommand.newBuilder()
                                .setType(ProtoCommands.SessionCommand.CommandType.SUBMIT_CANDIDATE)
                                .setId(id),
                        ),
                )
            } else {
                sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.ENTER))
            }
        val committedText = if (output.hasResult()) output.result.value else ""
        val stillComposing = output.hasPreedit() && output.preedit.segmentList.isNotEmpty()
        return if (stillComposing) {
            // surfaceResult = false: the result is already this SegmentCommit's committedText,
            // and carrying it on `remaining` too would get it committed twice.
            SegmentCommit(committedText, applyOutput(output, surfaceResult = false))
        } else {
            // Nothing left pending: fall back to whatever was showing if this particular
            // response carried no explicit Result, so a partial commit never silently drops text.
            val fallback = committedText.ifEmpty { composition.text }
            reset()
            SegmentCommit(fallback, null)
        }
    }

    override fun dropLast(): Composition {
        if (rawInputBuffer.isNotEmpty()) {
            rawInputBuffer.deleteCharAt(rawInputBuffer.length - 1)
        }
        val output = sendKey(specialKey(ProtoCommands.KeyEvent.SpecialKey.BACKSPACE))
        return applyOutput(output, surfaceResult = true)
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
        candidateWords = emptyList()
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

    /**
     * [surfaceResult]: whether any `Output.result` in this response is returned as the
     * composition's [Composition.committedText]. Operations that report their own commit (see
     * [commitCandidate]) pass false so the same text is not committed twice; everything else
     * passes true, so text Mozc finalizes as a side effect is never silently dropped. Dropping it
     * was the bug where typing during a conversion replaced the conversion instead of confirming
     * it — Mozc had confirmed it, but only `preedit` was read back.
     */
    private fun applyOutput(output: ProtoCommands.Output, surfaceResult: Boolean): Composition {
        val text =
            if (output.hasPreedit()) {
                output.preedit.segmentList.joinToString("") { it.value }
            } else {
                ""
            }
        // No preedit means nothing is composing any more (e.g. Mozc committed the whole input
        // directly). Keeping stale raw input here would leave hasActiveComposition() true with
        // nothing on screen, and the next Enter would be spent "confirming" an empty composition
        // instead of performing the editor action.
        if (text.isEmpty()) rawInputBuffer.clear()
        // Mozc computes zero-query SUGGESTION candidates on every keystroke (for a separate
        // autocomplete-style strip real clients show above the keyboard), not just after an
        // explicit conversion request. Treating those as *the* candidate list would make
        // Composition.currentCandidate — and so the text this app actually composes — jump to
        // an unrelated suggestion the moment one exists (e.g. from user history), instead of
        // the literal reading the user is typing. Only a real conversion (triggered by
        // nextCandidate()'s SPACE, see the class doc) should drive candidate selection.
        candidateWords =
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
        // The stored composition never carries committedText (it is a one-shot event, see
        // Composition.committedText); only the value handed back to this call's caller does.
        val committedText = if (surfaceResult && output.hasResult()) output.result.value else ""
        return if (committedText.isEmpty()) composition else composition.copy(committedText = committedText)
    }
}
