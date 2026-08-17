package com.tanutus.ime.core.conversion

/**
 * Placeholder [KanaConverter]: romaji -> hiragana only, via [RomajiKanaTable], with no
 * kanji conversion and no multi-candidate list (per the "no Mozc yet" scope decision in
 * docs/keyboard-spec.md). Sokuon (っ) and trailing "n" (ん) are resolved algorithmically
 * rather than via table lookup.
 *
 * Resolved input is tracked as a list of [Segment]s (raw romaji + resolved kana) rather than
 * a flat string so [dropLast] can undo a whole contracted unit (e.g. "きゃ") as one step,
 * matching how it was typed, plus a [buffer] holding the as-yet-unresolved romaji tail.
 */
class RomajiHiraganaConverter : KanaConverter {
    private data class Segment(val raw: String, val kana: String)

    private val segments = mutableListOf<Segment>()
    private val buffer = StringBuilder()

    override fun input(char: Char): Composition {
        buffer.append(char.lowercaseChar())
        resolve(forceFinal = false)
        return currentComposition()
    }

    override fun nextCandidate(): Composition {
        // Only one candidate exists until a real conversion engine replaces this converter.
        return currentComposition()
    }

    override fun commit(): String {
        resolve(forceFinal = true)
        val result = segments.joinToString("") { it.kana }
        reset()
        return result
    }

    override fun dropLast(): Composition {
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
        } else if (segments.isNotEmpty()) {
            segments.removeAt(segments.size - 1)
        }
        return currentComposition()
    }

    override fun hasActiveComposition(): Boolean = segments.isNotEmpty() || buffer.isNotEmpty()

    override fun currentComposition(): Composition {
        val raw = segments.joinToString("") { it.raw } + buffer.toString()
        val text = segments.joinToString("") { it.kana } + buffer.toString()
        return Composition(rawInput = raw, text = text)
    }

    override fun reset() {
        segments.clear()
        buffer.clear()
    }

    /**
     * Repeatedly resolves whatever of [buffer] it can into [segments].
     *
     * With [forceFinal] = false (called after each keystroke), a buffer that's a valid prefix
     * of some longer table entry is left pending so more input can complete it (e.g. "k" waits
     * for "ka"). With [forceFinal] = true (called from [commit]), nothing is left pending: a
     * trailing bare "n" resolves to "ん" and anything else unresolved falls back to a literal
     * passthrough of its first character.
     */
    private fun resolve(forceFinal: Boolean) {
        while (true) {
            val buf = buffer.toString()
            if (buf.isEmpty()) return

            if (buf.length >= 2 && buf[0] == buf[1] && isConsonant(buf[0])) {
                pushSegment(buf.substring(0, 1), "っ")
                buffer.deleteCharAt(0)
                continue
            }

            if (buf[0] == 'n') {
                val second = buf.getOrNull(1)
                val resolveAsN =
                    when {
                        second == null -> forceFinal
                        second == 'n' -> true
                        second !in VOWELS_AND_Y -> true
                        else -> false
                    }
                if (resolveAsN) {
                    pushSegment("n", "ん")
                    buffer.deleteCharAt(0)
                    continue
                }
            }

            val matchLen =
                listOf(3, 2, 1).firstOrNull { len ->
                    len <= buf.length && RomajiKanaTable.TABLE.containsKey(buf.substring(0, len))
                }
            if (matchLen != null) {
                val raw = buf.substring(0, matchLen)
                pushSegment(raw, RomajiKanaTable.TABLE.getValue(raw))
                buffer.delete(0, matchLen)
                continue
            }

            if (!forceFinal) {
                val isPendingPrefix = RomajiKanaTable.TABLE.keys.any { it.length > buf.length && it.startsWith(buf) }
                if (isPendingPrefix) return
            }

            // No match and not a recognized prefix: pass the character through literally.
            pushSegment(buf.substring(0, 1), buf.substring(0, 1))
            buffer.deleteCharAt(0)
        }
    }

    private fun pushSegment(raw: String, kana: String) {
        segments.add(Segment(raw, kana))
    }

    private fun isConsonant(char: Char): Boolean = char in 'a'..'z' && char !in "aiueo" && char != 'n'

    private companion object {
        const val VOWELS_AND_Y = "aiueoy"
    }
}
