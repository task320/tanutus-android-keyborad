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
import com.tanutus.ime.core.conversion.SegmentCommit
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
import com.tanutus.ime.mozc.MozcKanaConverter
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
    private lateinit var kanaConverter: KanaConverter
    private lateinit var spaceKeyGestureHandler: SpaceKeyGestureHandler

    private lateinit var haptics: HapticsHelper
    private lateinit var keyboardView: KeyboardView
    private lateinit var candidateBarView: CandidateBarView
    private lateinit var inputRootView: LinearLayout
    private var keyboardViewReady = false

    private var enterKeyBehavior: EnterKeyBehavior = EditorInfoActionMapper.mapEnterKey(null)

    override fun onCreate() {
        super.onCreate()
        haptics = HapticsHelper(this)
        // MozcKanaConverter touches Context (assets/filesDir) during construction, which isn't
        // safe before attachBaseContext has run — so this and spaceKeyGestureHandler (which
        // wraps it) are built here rather than as field initializers.
        kanaConverter = MozcKanaConverter(this)
        spaceKeyGestureHandler = SpaceKeyGestureHandler(kanaConverter, this)
    }

    override fun onCreateInputView(): View {
        candidateBarView =
            CandidateBarView(this).apply {
                onCandidateSelected = { index -> commitCandidate(index) }
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

    /**
     * Now that punctuation can join a pending composition instead of always force-committing it
     * (see [onPunctuationKey]), a composition can be left pending right up until the keyboard is
     * dismissed — e.g. the user finishes with "。" and then taps outside the field, or switches
     * apps, without another keystroke to flush it. [onStartInputView]'s `kanaConverter.reset()`
     * discards state silently, so without this the pending text would just vanish; committing it
     * here, before the view actually goes away, keeps that from being a data-loss trap.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        commitActiveComposition()
        super.onFinishInputView(finishingInput)
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
        // Unshifted "-" in romaji mode is the chōon (long vowel) mark, not the ASCII hyphen —
        // feed it through the kana converter like any other romaji key instead of committing it
        // as a literal symbol, so it lands inside the pending composition (e.g. "で" + "-" reads
        // as "でー", ready to convert to "データ") rather than jumping ahead of it. Shift+"-"
        // (the "_" on layer 2) is still a plain literal symbol.
        if (state.inputMode == InputMode.ROMAJI && pair.base == '-' && state.shift == ShiftState.OFF) {
            onCompositionUpdated(kanaConverter.input('-'))
            consumeMomentaryShift()
            return
        }
        val glyph = if (state.shift != ShiftState.OFF) pair.shifted else pair.base
        commitLiteralChar(glyph, alreadyCased = true)
    }

    override fun onPunctuationKey(char: Char) {
        // 句点/読点 join a pending romaji composition instead of force-committing it (matching
        // the chōon fix in onShiftPairKey): the user may still be mid-word, and typing
        // punctuation shouldn't silently finalize whatever candidate happened to be selected.
        // With nothing pending, there's no composition to preserve, so it just commits directly.
        if (kanaConverter.hasActiveComposition()) {
            onCompositionUpdated(kanaConverter.input(char))
        } else {
            currentInputConnection?.commitText(char.toString(), 1)
        }
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

    override fun onShiftDoubleTap() = applyStateEvent(StateEvent.ShiftDoubleTap)

    override fun onLayerToggleTap() = applyStateEvent(StateEvent.LayerToggleTap)

    override fun onRomajiToggleTap() {
        // Switching input mode shouldn't leave a stray composition behind.
        commitActiveComposition()
        applyStateEvent(StateEvent.RomajiToggleTap)
    }

    override fun onRomajiToggleLongPress() = applyStateEvent(StateEvent.RomajiToggleLongPress)

    override fun onEnter() {
        // While romaji composition is active, Enter's first job is finalizing it — matching
        // every other Japanese IME's UX (and avoiding a half-typed reading getting submitted as
        // the literal query/text). The editor action (search, newline, ...) only fires on a
        // second, separate Enter press once nothing is left to commit. For a multi-segment
        // conversion, one Enter press confirms only the focused segment (see
        // commitFocusedSegment): the rest keeps composing so it can be converted on its own,
        // rather than every segment beyond the first getting silently auto-committed at once.
        if (kanaConverter.hasActiveComposition()) {
            commitFocusedSegment()
        } else {
            val action = enterKeyBehavior.editorAction
            if (action != EditorInfo.IME_ACTION_NONE) {
                currentInputConnection?.performEditorAction(action)
            } else {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
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
        // composition.text (not currentCandidate) is what belongs in the composing span: for a
        // multi-segment Mozc conversion, currentCandidate is only the focused segment's word, and
        // setComposingText replaces the *entire* span — using it here would make every segment
        // but the focused one disappear from view mid-conversion. text is Mozc's own preedit
        // (all segments, with the focused one already reflecting its current candidate), which
        // is the right thing to show whether one segment is composing or many.
        currentInputConnection?.setComposingText(composition.text, 1)
    }

    // endregion

    private fun commitActiveComposition() {
        if (kanaConverter.hasActiveComposition()) {
            commitComposedText(kanaConverter.commit())
        }
    }

    /**
     * Confirms just the currently-focused segment (see [KanaConverter.commitFocusedSegment]) —
     * used by Enter so a multi-segment conversion can be stepped through one segment at a time
     * instead of every segment past the first getting auto-committed with whatever candidate
     * Mozc defaulted to.
     */
    private fun commitFocusedSegment() = applySegmentCommit(kanaConverter.commitFocusedSegment())

    /**
     * Confirms the candidate at [index] specifically — what backs tapping a chip in the
     * candidate bar (see [KanaConverter.commitCandidate]), as opposed to [commitFocusedSegment]
     * which always confirms whatever the engine currently has focused.
     */
    private fun commitCandidate(index: Int) = applySegmentCommit(kanaConverter.commitCandidate(index))

    private fun applySegmentCommit(step: SegmentCommit) {
        if (step.committedText.isNotEmpty()) {
            currentInputConnection?.commitText(step.committedText, 1)
        }
        val remaining = step.remaining
        if (remaining != null) {
            onCompositionUpdated(remaining)
        } else {
            candidateBarView.setCandidates(emptyList(), 0)
        }
    }

    private fun commitLiteralChar(char: Char, alreadyCased: Boolean = false) {
        // A literal symbol commits straight to the input connection, bypassing the kana
        // converter entirely — if a romaji composition were still pending, InputConnection would
        // treat this commitText as replacing that composing span, visually splicing the symbol
        // into the middle of (and reordering) the composition instead of after it. Flushing
        // first guarantees literal chars always land after whatever the user already composed.
        commitActiveComposition()
        val state = stateMachine.state
        var output = if (!alreadyCased && state.shift != ShiftState.OFF) char.uppercaseChar() else char
        // Layer 2 (digits/symbols) in romaji mode is always full-width, independent of the
        // manual zenkaku toggle (which still governs direct-alnum mode as before): numbers and
        // punctuation typed while composing Japanese text should match the surrounding
        // full-width text rather than defaulting to half-width ASCII.
        val useZenkaku = state.zenkaku || (state.layer == Layer.SYMBOL && state.inputMode == InputMode.ROMAJI)
        if (useZenkaku) output = ZenkakuHankakuConverter.toZenkaku(output)
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
