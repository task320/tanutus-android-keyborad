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
 * Result of confirming just the currently-focused unit of a composition — see
 * [KanaConverter.commitFocusedSegment]. [committedText] is what should be inserted into the
 * document right now. [remaining], when non-null, is the composition still pending after that
 * partial commit (e.g. a multi-segment Mozc conversion where only the first segment was just
 * confirmed and the rest are still awaiting their own conversion); null means nothing is left.
 */
data class SegmentCommit(val committedText: String, val remaining: Composition?)

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

    /** Finalize the entire pending composition, returning the committed text, and clear state. */
    fun commit(): String

    /**
     * Confirms only the currently-focused unit rather than everything pending (see [commit] for
     * "confirm all and reset"). This is what lets a multi-segment conversion be stepped through
     * one segment at a time: convert the first segment, confirm it, the rest stays composing
     * for its own conversion, repeat until nothing is left. A converter with no segment concept
     * (the romaji placeholder) behaves the same as [commit], with `remaining = null`.
     */
    fun commitFocusedSegment(): SegmentCommit

    /**
     * Confirms the candidate at [index] into [Composition.candidates] specifically — this backs
     * the candidate bar itself, where tapping a chip should convert to *that* candidate rather
     * than whatever the engine currently has focused. Otherwise behaves exactly like
     * [commitFocusedSegment] (including the segment-by-segment [SegmentCommit.remaining]).
     */
    fun commitCandidate(index: Int): SegmentCommit

    /** Undo the most recently resolved unit (a whole kana segment, or one pending raw char). */
    fun dropLast(): Composition

    fun hasActiveComposition(): Boolean

    fun currentComposition(): Composition

    fun reset()
}
