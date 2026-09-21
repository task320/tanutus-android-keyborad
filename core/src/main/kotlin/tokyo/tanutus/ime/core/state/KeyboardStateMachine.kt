package tokyo.tanutus.ime.core.state

import tokyo.tanutus.ime.core.layout.KeyAction
import tokyo.tanutus.ime.core.layout.Layer

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

    /**
     * Long-press the romaji-toggle key: flips zenkaku (full-width) on/off.
     *
     * Unlike [ShiftDoubleTap], this never follows a [RomajiToggleTap] for the same gesture —
     * a long press suppresses the tap entirely (see KeyboardView.onLongPressTriggered), so
     * there is no input-mode flip to undo here.
     */
    data object RomajiToggleLongPress : StateEvent

    /** A character was just committed; momentary shift auto-resets after one character. */
    data object CharacterCommitted : StateEvent
}

data class Transition(val newState: KeyboardUiState, val hapticFeedback: Boolean)

/** Which background a key is drawn with — the spec's 「固定中のキーは塗りつぶし表示」rule. */
enum class KeyFill {
    NORMAL,
    LOCKED,

    /** Shift's lock, drawn dimmer so it reads apart from the other toggles' lock. */
    SHIFT_LOCKED,

    /**
     * Shift armed for a single character. Deliberately fainter than [SHIFT_LOCKED], because
     * momentary shift is not one of the spec's 「固定中」states — the tint says "armed", the
     * full fill stays reserved for "locked". Without it the state is invisible wherever shift
     * changes no key label: layer 1 while composing romaji, where kana has no case at all.
     */
    SHIFT_MOMENTARY,
}

/**
 * How one key should be drawn.
 *
 * [fill] and [outlined] are deliberately separate channels. The romaji-toggle key carries two
 * *independent* states — direct-alnum mode and zenkaku mode — and a single fill color cannot
 * say which of them is on: before this, either one filled the key, so switching to full-width
 * looked exactly like switching to direct alphanumeric. Fill now answers "which input mode",
 * the outline answers "full-width or not", and all four combinations stay readable without
 * inventing four colors.
 */
data class KeyVisualState(val fill: KeyFill = KeyFill.NORMAL, val outlined: Boolean = false)

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
                        // Switching input mode always lands on an unshifted keyboard, in both
                        // directions. Going *to* romaji, a carried-over lock would sit filled
                        // while doing nothing to the kana being typed. Going *to* direct-alnum
                        // it is worse: layer 1's letters ignore shift entirely while composing
                        // romaji, so arming it there changes nothing on screen, and the whole
                        // keyboard would then jump to uppercase the moment the user toggled —
                        // with no visible cause, since a momentary shift draws no fill.
                        shift = ShiftState.OFF,
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
                // Tied to the *lock* specifically, not to any change of fill: momentary shift
                // now tints the key too (KeyFill.SHIFT_MOMENTARY), but the spec reserves the
                // haptic tick for 固定/解除 — arming one character is not that.
                StateEvent.ShiftTap ->
                    (before.shift == ShiftState.LOCKED) != (after.shift == ShiftState.LOCKED)
                else -> true
            }
        return Transition(after, haptic)
    }

    fun visualStateFor(action: KeyAction): KeyVisualState = visualStateFor(action, state)

    private fun visualStateFor(action: KeyAction, uiState: KeyboardUiState): KeyVisualState =
        when (action) {
            KeyAction.Shift ->
                KeyVisualState(
                    when (uiState.shift) {
                        ShiftState.LOCKED -> KeyFill.SHIFT_LOCKED
                        ShiftState.MOMENTARY -> KeyFill.SHIFT_MOMENTARY
                        ShiftState.OFF -> KeyFill.NORMAL
                    },
                )

            KeyAction.LayerToggle ->
                KeyVisualState(if (uiState.layer == Layer.SYMBOL) KeyFill.LOCKED else KeyFill.NORMAL)

            // Two independent states on one key, so two independent channels — see
            // KeyVisualState. Zenkaku only actually changes what gets typed in direct-alnum
            // mode, which is exactly the combination the outline-over-fill reads as.
            KeyAction.RomajiToggle ->
                KeyVisualState(
                    fill = if (uiState.inputMode == InputMode.DIRECT_ALNUM) KeyFill.LOCKED else KeyFill.NORMAL,
                    outlined = uiState.zenkaku,
                )

            else -> KeyVisualState()
        }
}
