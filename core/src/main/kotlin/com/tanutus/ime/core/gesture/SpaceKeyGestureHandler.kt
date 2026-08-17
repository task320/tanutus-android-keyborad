package com.tanutus.ime.core.gesture

import com.tanutus.ime.core.conversion.Composition
import com.tanutus.ime.core.conversion.KanaConverter

/** Everything a resolved space-key gesture can ask the IME service to actually do. */
interface SpaceKeyActions {
    fun insertSpace()

    fun insertTab()

    /** Move the cursor by [delta] characters (negative = left, positive = right). */
    fun moveCursorChars(delta: Int)

    /** Move the cursor by [delta] lines (negative = up, positive = down). */
    fun moveCursorLines(delta: Int)

    fun commitComposedText(text: String)

    fun onCompositionUpdated(composition: Composition)
}

/**
 * Encodes the composition-aware branching docs/keyboard-spec.md specifies for the space key:
 * while a kana-kanji composition is active, TAP advances to the next candidate instead of
 * inserting a space, and every other gesture first commits the leading candidate before
 * performing its normal action (Tab / cursor move).
 */
class SpaceKeyGestureHandler(
    private val converter: KanaConverter,
    private val actions: SpaceKeyActions,
) : SpaceGestureListener {
    override fun onSpaceGesture(action: SpaceGestureAction) {
        when (action) {
            SpaceGestureAction.TAP ->
                if (converter.hasActiveComposition()) {
                    actions.onCompositionUpdated(converter.nextCandidate())
                } else {
                    actions.insertSpace()
                }

            SpaceGestureAction.LONG_PRESS -> {
                commitIfComposing()
                actions.insertTab()
            }

            SpaceGestureAction.CURSOR_LEFT -> {
                commitIfComposing()
                actions.moveCursorChars(-1)
            }

            SpaceGestureAction.CURSOR_RIGHT -> {
                commitIfComposing()
                actions.moveCursorChars(1)
            }

            SpaceGestureAction.CURSOR_UP -> {
                commitIfComposing()
                actions.moveCursorLines(-1)
            }

            SpaceGestureAction.CURSOR_DOWN -> {
                commitIfComposing()
                actions.moveCursorLines(1)
            }
        }
    }

    private fun commitIfComposing() {
        if (converter.hasActiveComposition()) {
            actions.commitComposedText(converter.commit())
        }
    }
}
