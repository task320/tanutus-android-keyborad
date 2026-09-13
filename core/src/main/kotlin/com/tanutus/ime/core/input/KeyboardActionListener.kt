package com.tanutus.ime.core.input

import com.tanutus.ime.core.layout.KeyAction

/**
 * Callbacks for every key event except the space key, which has its own richer contract
 * (see core/gesture/SpaceKeyGestureHandler) because of its stacked tap/long-press/swipe
 * gestures. Implemented by the IME service.
 */
interface KeyboardActionListener {
    /** A plain character key was tapped (layer 1 letters, layer 2 digits/single symbols). */
    fun onKeyChar(char: Char)

    /** A layer-2 shift-pair key (`-`/`_`, `'`/`"`) was tapped; the listener resolves which glyph applies. */
    fun onShiftPairKey(pair: KeyAction.ShiftPair)

    /** 句点/読点, shown flanking the space key only in romaji mode. */
    fun onPunctuationKey(char: Char)

    fun onBackspace()

    fun onShiftTap()

    /** Two quick taps on the shift key, i.e. the shift-lock gesture (see KeyboardView). */
    fun onShiftDoubleTap()

    fun onLayerToggleTap()

    fun onRomajiToggleTap()

    /** Long-press on the romaji-toggle key, i.e. the zenkaku latch (see KeyboardView). */
    fun onRomajiToggleLongPress()

    fun onEnter()
}
