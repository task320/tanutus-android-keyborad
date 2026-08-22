package com.tanutus.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tanutus.ime.core.conversion.Composition
import com.tanutus.ime.core.conversion.KanaConverter
import com.tanutus.ime.core.conversion.RomajiHiraganaConverter
import com.tanutus.ime.core.conversion.ZenkakuHankakuConverter
import com.tanutus.ime.core.gesture.SpaceKeyActions
import com.tanutus.ime.core.gesture.SpaceKeyGestureHandler
import com.tanutus.ime.core.input.KeyboardActionListener
import com.tanutus.ime.core.layout.KeyAction
import com.tanutus.ime.core.layout.KeyboardLayouts
import com.tanutus.ime.core.layout.Layer
import com.tanutus.ime.core.state.InputMode
import com.tanutus.ime.core.state.KeyboardStateMachine
import com.tanutus.ime.core.state.ShiftState
import com.tanutus.ime.core.state.StateEvent
import com.tanutus.ime.editor.EditorInfoActionMapper
import com.tanutus.ime.editor.EnterKeyBehavior
import com.tanutus.ime.haptics.HapticsHelper
import com.tanutus.ime.theme.KeyboardThemeProvider
import com.tanutus.ime.view.CandidateBarView
import com.tanutus.ime.view.KeyboardView
import kotlin.math.abs

/**
 * Composition root: owns the keyboard's state machine and the (placeholder) kana converter,
 * implements the two callback interfaces core logic dispatches through
 * ([KeyboardActionListener] for everything but space, [SpaceKeyActions] for the space key's
 * composition-aware gestures), and drives [android.view.inputmethod.InputConnection].
 */
class TanutusImeService :
    InputMethodService(),
    KeyboardActionListener,
    SpaceKeyActions {
    private var stateMachine = KeyboardStateMachine()
    private val kanaConverter: KanaConverter = RomajiHiraganaConverter()
    private val spaceKeyGestureHandler = SpaceKeyGestureHandler(kanaConverter, this)

    private lateinit var haptics: HapticsHelper
    private lateinit var keyboardView: KeyboardView
    private lateinit var candidateBarView: CandidateBarView
    private lateinit var inputRootView: LinearLayout
    private var keyboardViewReady = false

    private var enterKeyBehavior: EnterKeyBehavior = EditorInfoActionMapper.mapEnterKey(null)

    override fun onCreate() {
        super.onCreate()
        haptics = HapticsHelper(this)
    }

    override fun onCreateInputView(): View {
        candidateBarView =
            CandidateBarView(this).apply {
                onCandidateSelected = { commitActiveComposition() }
            }
        keyboardView =
            KeyboardView(this).apply {
                keyboardActionListener = this@TanutusImeService
                spaceGestureListener = spaceKeyGestureHandler
            }
        keyboardViewReady = true

        inputRootView =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    candidateBarView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.candidate_bar_height),
                    ),
                )
                addView(
                    keyboardView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        resources.getDimensionPixelSize(R.dimen.key_height) * ROW_COUNT,
                    ),
                )
            }
        applyGestureSafeAreaPadding(inputRootView)
        return inputRootView
    }

    /**
     * Row 4's bottom edge sits flush with the screen edge, the same strip the system reserves
     * for the home-swipe/assistant-long-press gesture (WindowInsets.Type.
     * mandatorySystemGestures()). Unlike the back-swipe edges (handled in
     * [KeyboardView.excludeFromSystemGestures]), apps cannot opt out of this one — the fix is
     * to keep every key's hit-box out of it by padding the input view by that inset.
     */
    private fun applyGestureSafeAreaPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, maxOf(gestureBottom, navBarBottom))
            insets
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stateMachine = KeyboardStateMachine()
        kanaConverter.reset()
        enterKeyBehavior = EditorInfoActionMapper.mapEnterKey(info)
        candidateBarView.setCandidates(emptyList(), 0)
        refreshKeyboardView()
    }

    // region KeyboardActionListener

    override fun onKeyChar(char: Char) {
        val state = stateMachine.state
        if (state.layer == Layer.BASE && state.inputMode == InputMode.ROMAJI) {
            // Kana has no case, so shift never affects the converted text here — but the key
            // itself still counts as "the next character" for the spec's momentary-shift rule
            // (docs/keyboard-spec.md, シフトキー: "入力後は自動解除"), so a dangling MOMENTARY
            // shift must still be released.
            onCompositionUpdated(kanaConverter.input(char.lowercaseChar()))
            consumeMomentaryShift()
        } else {
            commitLiteralChar(char)
        }
    }

    override fun onShiftPairKey(pair: KeyAction.ShiftPair) {
        val state = stateMachine.state
        val glyph = if (state.shift != ShiftState.OFF) pair.shifted else pair.base
        commitLiteralChar(glyph, alreadyCased = true)
    }

    override fun onPunctuationKey(char: Char) {
        // 句点/読点 end a sentence — commit whatever romaji composition is pending first
        // rather than feeding the mark into it, so it lands as its own character.
        commitActiveComposition()
        currentInputConnection?.commitText(char.toString(), 1)
        consumeMomentaryShift()
    }

    override fun onBackspace() {
        if (kanaConverter.hasActiveComposition()) {
            onCompositionUpdated(kanaConverter.dropLast())
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    override fun onShiftTap() = applyStateEvent(StateEvent.ShiftTap)

    override fun onShiftLongPress() = applyStateEvent(StateEvent.ShiftLongPress)

    override fun onLayerToggleTap() = applyStateEvent(StateEvent.LayerToggleTap)

    override fun onRomajiToggleTap() {
        // Switching input mode shouldn't leave a stray composition behind.
        commitActiveComposition()
        applyStateEvent(StateEvent.RomajiToggleTap)
    }

    override fun onRomajiToggleLongPress() = applyStateEvent(StateEvent.RomajiToggleLongPress)

    override fun onEnter() {
        commitActiveComposition()
        val action = enterKeyBehavior.editorAction
        if (action != EditorInfo.IME_ACTION_NONE) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        consumeMomentaryShift()
    }

    // endregion

    // region SpaceKeyActions

    override fun insertSpace() {
        currentInputConnection?.commitText(" ", 1)
        consumeMomentaryShift()
    }

    override fun insertTab() {
        currentInputConnection?.commitText("\t", 1)
        consumeMomentaryShift()
    }

    override fun moveCursorChars(delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) { sendDownUpKeyEvents(keyCode) }
    }

    override fun moveCursorLines(delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(abs(delta)) { sendDownUpKeyEvents(keyCode) }
    }

    override fun commitComposedText(text: String) {
        currentInputConnection?.commitText(text, 1)
        candidateBarView.setCandidates(emptyList(), 0)
    }

    override fun onCompositionUpdated(composition: Composition) {
        candidateBarView.setCandidates(composition.candidates, composition.candidateIndex)
        currentInputConnection?.setComposingText(composition.currentCandidate, 1)
    }

    // endregion

    private fun commitActiveComposition() {
        if (kanaConverter.hasActiveComposition()) {
            commitComposedText(kanaConverter.commit())
        }
    }

    private fun commitLiteralChar(char: Char, alreadyCased: Boolean = false) {
        val state = stateMachine.state
        var output = if (!alreadyCased && state.shift != ShiftState.OFF) char.uppercaseChar() else char
        if (state.zenkaku) output = ZenkakuHankakuConverter.toZenkaku(output)
        currentInputConnection?.commitText(output.toString(), 1)
        consumeMomentaryShift()
    }

    private fun consumeMomentaryShift() {
        val transition = stateMachine.dispatch(StateEvent.CharacterCommitted)
        if (transition.hapticFeedback) haptics.performStateChangeTick()
        refreshKeyboardView()
    }

    private fun applyStateEvent(event: StateEvent) {
        val transition = stateMachine.dispatch(event)
        if (transition.hapticFeedback) haptics.performStateChangeTick()
        refreshKeyboardView()
    }

    private fun refreshKeyboardView() {
        if (!keyboardViewReady) return
        val state = stateMachine.state
        val layout = KeyboardLayouts.layoutFor(state.layer, state.inputMode)
        val colors = KeyboardThemeProvider.themeFor(state.layer, this)
        // Only direct-alnum mode is excluded from this: romaji composing ignores shift for
        // casing (kana has no case, see onKeyChar), so showing uppercase key glyphs there
        // would promise a case change the typed kana would never actually show.
        val uppercaseLetters = state.inputMode == InputMode.DIRECT_ALNUM && state.shift != ShiftState.OFF
        keyboardView.render(layout, colors, uppercaseLetters) { action -> stateMachine.isLockedVisual(action) }
        // Keeps the gesture-safe-area padding (see applyGestureSafeAreaPadding) visually
        // seamless with row 4 instead of showing as a mismatched strip below it.
        inputRootView.setBackgroundColor(colors.background)
    }

    private companion object {
        const val ROW_COUNT = 4
    }
}
