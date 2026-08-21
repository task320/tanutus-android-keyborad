package com.tanutus.ime.core.conversion

/**
 * A single in-progress (or just-resolved) conversion state.
 *
 * [candidates] contains [text] itself whenever [text] is non-empty, and is empty otherwise —
 * an empty [text] (e.g. right after [KanaConverter.dropLast] clears the last character) must
 * not surface as a phantom empty-string candidate in the candidate bar (see
 * com.tanutus.ime.view.CandidateBarView.setCandidates, which treats a non-empty candidate list
 * as "show these", even if every entry is blank). Today's placeholder converter never produces
 * more than one candidate, but the field exists so the space key's "next candidate" gesture
 * (see core/gesture/SpaceKeyGestureHandler) has real plumbing to call into once a real
 * kana-kanji engine (e.g. Mozc) is dropped in behind the [KanaConverter] interface.
 */
data class Composition(
    val rawInput: String,
    val text: String,
    val candidates: List<String> = if (text.isEmpty()) emptyList() else listOf(text),
    val candidateIndex: Int = 0,
) {
    val isEmpty: Boolean get() = rawInput.isEmpty()
    val currentCandidate: String get() = candidates.getOrElse(candidateIndex) { text }
}

/**
 * Converts raw key input into composed text. This is the seam a real kana-kanji conversion
 * engine plugs into later; nothing above this interface (gesture handling, the IME service)
 * needs to know whether the implementation behind it is the placeholder romaji table in this
 * repo or a real engine.
 */
interface KanaConverter {
    /** Feed one more raw character into the composition. Returns the updated state. */
    fun input(char: Char): Composition

    /** Advance to the next candidate for the current composition (no-op if there's only one). */
    fun nextCandidate(): Composition

    /** Finalize the current composition, returning the committed text, and clear state. */
    fun commit(): String

    /** Undo the most recently resolved unit (a whole kana segment, or one pending raw char). */
    fun dropLast(): Composition

    fun hasActiveComposition(): Boolean

    fun currentComposition(): Composition

    fun reset()
}
