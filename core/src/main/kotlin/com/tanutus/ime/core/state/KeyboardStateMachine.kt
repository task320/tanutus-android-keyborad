package com.tanutus.ime.core.state

import com.tanutus.ime.core.layout.KeyAction
import com.tanutus.ime.core.layout.Layer

enum class ShiftState {
    OFF,
    MOMENTARY,
    LOCKED,
}

enum class InputMode {
    ROMAJI,
    DIRECT_ALNUM,
}

data class KeyboardUiState(
    val layer: Layer = Layer.BASE,
    val shift: ShiftState = ShiftState.OFF,
    val inputMode: InputMode = InputMode.ROMAJI,
    val zenkaku: Boolean = false,
)

sealed interface StateEvent {
    /** Tap the shift key: OFF -> MOMENTARY, MOMENTARY -> OFF, LOCKED -> OFF. */
    data object ShiftTap : StateEvent

    /** Double-tap the shift key (two quick taps): toggles the LOCKED state on/off. */
    data object ShiftDoubleTap : StateEvent

    /** Tap the layer-toggle key: flips between the base and symbol layers. */
    data object LayerToggleTap : StateEvent

    /** Tap the romaji-toggle key: flips between romaji input and direct alphanumeric input. */
    data object RomajiToggleTap : StateEvent

    /** Long-press the romaji-toggle key: flips zenkaku (full-width) on/off. */
    data object RomajiToggleLongPress : StateEvent

    /** A character was just committed; momentary shift auto-resets after one character. */
    data object CharacterCommitted : StateEvent
}

data class Transition(val newState: KeyboardUiState, val hapticFeedback: Boolean)

/**
 * Owns the keyboard's toggle/lock state and the single rule shared by every toggle key's
 * visual feedback: "filled = locked-on, unfilled = normal" (see docs/keyboard-spec.md,
 * "視覚的フィードバックの統一ルール"). Haptic feedback fires exactly when a key's filled
 * state flips, mirroring the visual change — not on every tap.
 */
class KeyboardStateMachine(initial: KeyboardUiState = KeyboardUiState()) {
    var state: KeyboardUiState = initial
        private set

    fun dispatch(event: StateEvent): Transition {
        val before = state
        val after =
            when (event) {
                StateEvent.ShiftTap ->
                    when (before.shift) {
                        ShiftState.OFF -> before.copy(shift = ShiftState.MOMENTARY)
                        ShiftState.MOMENTARY -> before.copy(shift = ShiftState.OFF)
                        ShiftState.LOCKED -> before.copy(shift = ShiftState.OFF)
                    }

                StateEvent.ShiftDoubleTap ->
                    if (before.shift == ShiftState.LOCKED) {
                        before.copy(shift = ShiftState.OFF)
                    } else {
                        before.copy(shift = ShiftState.LOCKED)
                    }

                StateEvent.LayerToggleTap ->
                    before.copy(layer = if (before.layer == Layer.BASE) Layer.SYMBOL else Layer.BASE)

                StateEvent.RomajiToggleTap -> {
                    val newMode = if (before.inputMode == InputMode.ROMAJI) InputMode.DIRECT_ALNUM else InputMode.ROMAJI
                    before.copy(
                        inputMode = newMode,
                        // Shift is hidden (and unreachable) during romaji composition — see
                        // KeyboardLayouts.FUNCTION_ROW_ROMAJI — so returning to it must drop
                        // any shift the user left engaged in direct-alnum mode rather than
                        // leaving a dangling lock the user can no longer see or clear.
                        shift = if (newMode == InputMode.ROMAJI) ShiftState.OFF else before.shift,
                    )
                }

                StateEvent.RomajiToggleLongPress -> before.copy(zenkaku = !before.zenkaku)

                StateEvent.CharacterCommitted ->
                    if (before.shift == ShiftState.MOMENTARY) before.copy(shift = ShiftState.OFF) else before
            }

        state = after
        val haptic =
            when (event) {
                StateEvent.CharacterCommitted -> false
                StateEvent.ShiftTap -> isLockedVisual(KeyAction.Shift, before) != isLockedVisual(KeyAction.Shift, after)
                else -> true
            }
        return Transition(after, haptic)
    }

    fun isLockedVisual(action: KeyAction): Boolean = isLockedVisual(action, state)

    private fun isLockedVisual(action: KeyAction, uiState: KeyboardUiState): Boolean =
        when (action) {
            KeyAction.Shift -> uiState.shift == ShiftState.LOCKED
            KeyAction.LayerToggle -> uiState.layer == Layer.SYMBOL
            // Spec (docs/keyboard-spec.md, 視覚的フィードバックの統一ルール) lists both
            // direct-alnum mode and zenkaku mode as fill triggers for this one key — the OR
            // is intentional, not a placeholder collapsing two states into one.
            KeyAction.RomajiToggle -> uiState.inputMode == InputMode.DIRECT_ALNUM || uiState.zenkaku
            else -> false
        }
}
