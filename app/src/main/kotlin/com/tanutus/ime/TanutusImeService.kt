package com.tanutus.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
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

        return LinearLayout(this).apply {
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
            onCompositionUpdated(kanaConverter.input(char.lowercaseChar()))
        } else {
            commitLiteralChar(char)
        }
    }

    override fun onShiftPairKey(pair: KeyAction.ShiftPair) {
        val state = stateMachine.state
        val glyph = if (state.shift != ShiftState.OFF) pair.shifted else pair.base
        commitLiteralChar(glyph, alreadyCased = true)
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
        val layout = KeyboardLayouts.layoutFor(state.layer)
        val colors = KeyboardThemeProvider.themeFor(state.layer, this)
        keyboardView.render(layout, colors) { action -> stateMachine.isLockedVisual(action) }
    }

    private companion object {
        const val ROW_COUNT = 4
    }
}
