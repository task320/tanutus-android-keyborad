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

    fun onBackspace()

    fun onShiftTap()

    fun onShiftLongPress()

    fun onLayerToggleTap()

    fun onRomajiToggleTap()

    fun onRomajiToggleLongPress()

    fun onEnter()
}
