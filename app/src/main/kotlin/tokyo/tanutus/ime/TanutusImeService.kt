package tokyo.tanutus.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import tokyo.tanutus.ime.core.conversion.Composition
import tokyo.tanutus.ime.core.conversion.KanaConverter
import tokyo.tanutus.ime.core.conversion.SegmentCommit
import tokyo.tanutus.ime.core.conversion.ZenkakuHankakuConverter
import tokyo.tanutus.ime.core.gesture.SpaceKeyActions
import tokyo.tanutus.ime.core.gesture.SpaceKeyGestureHandler
import tokyo.tanutus.ime.core.input.KeyboardActionListener
import tokyo.tanutus.ime.core.layout.KeyAction
import tokyo.tanutus.ime.core.layout.KeyboardLayouts
import tokyo.tanutus.ime.core.layout.Layer
import tokyo.tanutus.ime.core.state.InputMode
import tokyo.tanutus.ime.core.state.KeyboardStateMachine
import tokyo.tanutus.ime.core.state.KeyboardUiState
import tokyo.tanutus.ime.core.state.ShiftState
import tokyo.tanutus.ime.core.state.StateEvent
import tokyo.tanutus.ime.editor.ConversionPolicy
import tokyo.tanutus.ime.editor.EditorInfoActionMapper
import tokyo.tanutus.ime.editor.EditorInfoInputModeMapper
import tokyo.tanutus.ime.editor.EnterKeyBehavior
import tokyo.tanutus.ime.haptics.HapticsHelper
import tokyo.tanutus.ime.mozc.MozcKanaConverter
import tokyo.tanutus.ime.theme.KeyboardThemeProvider
import tokyo.tanutus.ime.view.CandidateBarView
import tokyo.tanutus.ime.view.KeyboardView
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
    private var conversionPolicy: ConversionPolicy = ConversionPolicy.NORMAL

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
        // The field's own declared type decides how much of the conversion pipeline it gets:
        // a password prompt must not echo what's being typed into the candidate bar, and an
        // email/number/URI field opening in romaji mode just means the user has to reach for
        // the A/あ key before every single use. See EditorInfoInputModeMapper.
        conversionPolicy = EditorInfoInputModeMapper.mapConversionPolicy(info)
        stateMachine = KeyboardStateMachine(KeyboardUiState(inputMode = conversionPolicy.initialInputMode))
        kanaConverter.reset()
        enterKeyBehavior = EditorInfoActionMapper.mapEnterKey(info)
        // Hidden outright rather than just left blank (the spec's "候補が何もない状態では
        // 空白のまま" rule): with conversion off for the whole field, a permanently empty bar
        // is only wasted height.
        candidateBarView.visibility =
            if (conversionPolicy == ConversionPolicy.SUPPRESSED) View.GONE else View.VISIBLE
        candidateBarView.setCandidates(emptyList(), 0)
        refreshKeyboardView()
    }

    /**
     * Keeps the converter's idea of the pending composition in sync with the editor's. Without
     * this, tapping elsewhere in the text mid-conversion left Mozc still holding the composition
     * while the editor had moved on, so the next keystroke spliced its output back at the old
     * spot (or reordered it) — the composing span [android.view.inputmethod.InputConnection]
     * replaces is no longer where the user is looking.
     *
     * Only acted on when the editor actually reports a composing region ([candidatesStart] >= 0):
     * some editors (notably WebViews) always report -1, and treating that as "the user moved
     * away" would abandon the composition on every single keystroke there.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!kanaConverter.hasActiveComposition()) return
        if (candidatesStart < 0) return
        // Our own edits leave the caret collapsed at the end of the composing span, so anything
        // still inside that span is this IME's own update echoing back, not the user moving.
        val causedByOwnEdit = newSelStart == newSelEnd && newSelStart in candidatesStart..candidatesEnd
        if (causedByOwnEdit) return
        abandonComposition()
    }

    /**
     * Drops a composition the user has navigated away from. The composing text is already in the
     * document, so it's finalized where it stands rather than committed a second time — the goal
     * is only to stop the converter from reaching back into a span it no longer owns.
     */
    private fun abandonComposition() {
        currentInputConnection?.finishComposingText()
        kanaConverter.reset()
        if (keyboardViewReady) candidateBarView.setCandidates(emptyList(), 0)
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
        if (state.inputMode == InputMode.ROMAJI && state.shift == ShiftState.OFF) {
            // Unshifted "-" in romaji mode is the chōon (long vowel) mark, not the ASCII hyphen —
            // feed it through the kana converter like any other romaji key instead of committing
            // it as a literal symbol, so it lands inside the pending composition (e.g. "で" + "-"
            // reads as "でー", ready to convert to "データ") rather than jumping ahead of it.
            // Shift+"-" (the "_" on layer 2) is still a plain literal symbol.
            if (pair.base == '-') {
                onCompositionUpdated(kanaConverter.input('-'))
                consumeMomentaryShift()
                return
            }
            // Digits join a pending composition too (the same rule as 句点/読点 in
            // onPunctuationKey): "第" + "1" should keep composing as "だい１" so it can still be
            // converted and edited, instead of the digit force-committing whatever candidate
            // happened to be selected and ending the composition mid-word. Mozc composes digits
            // full-width in hiragana mode, so this keeps matching the zenkaku output
            // commitLiteralChar produced before. With nothing pending there is no composition to
            // preserve, so a digit still commits straight through (no stray preedit — and no
            // extra Enter — just to type a number), and shifted digits (!@#…) are plain ASCII
            // symbols either way.
            if (pair.base.isDigit() && kanaConverter.hasActiveComposition()) {
                onCompositionUpdated(kanaConverter.input(pair.base))
                consumeMomentaryShift()
                return
            }
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
        // In a password field there is no romaji mode to switch *to*: conversion is off for as
        // long as that field is focused (ConversionPolicy.SUPPRESSED), so the key stays inert
        // rather than flipping into a mode whose whole point — the candidate bar — is hidden.
        if (conversionPolicy == ConversionPolicy.SUPPRESSED) return
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
        val inputConnection = currentInputConnection
        // One edit, for the same reason as applySegmentCommit: between the commit and the new
        // composing text the composing span is momentarily gone.
        inputConnection?.beginBatchEdit()
        try {
            // Text the engine confirmed on the way to this composition (e.g. the conversion that
            // was showing when the user typed the next key) must land in the document *before*
            // the composing span is replaced. commitText on an active composing span replaces
            // that span with the committed text, which is exactly the "confirm in place" wanted;
            // skipping it let setComposingText below overwrite the conversion with the new key.
            if (composition.committedText.isNotEmpty()) {
                inputConnection?.commitText(composition.committedText, 1)
            }
            // composition.text (not currentCandidate) is what belongs in the composing span: for
            // a multi-segment Mozc conversion, currentCandidate is only the focused segment's
            // word, and setComposingText replaces the *entire* span — using it here would make
            // every segment but the focused one disappear from view mid-conversion. text is
            // Mozc's own preedit (all segments, with the focused one already reflecting its
            // current candidate), which is the right thing to show whether one segment is
            // composing or many.
            inputConnection?.setComposingText(composition.text, 1)
        } finally {
            inputConnection?.endBatchEdit()
        }
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
        val inputConnection = currentInputConnection
        // The commit and the re-compose have to reach the editor as one edit: between them the
        // composing span is momentarily gone, and an onUpdateSelection delivered in that gap
        // looks exactly like the user tapping away from the composition — which would get the
        // remaining segments abandoned mid-conversion.
        inputConnection?.beginBatchEdit()
        try {
            if (step.committedText.isNotEmpty()) {
                inputConnection?.commitText(step.committedText, 1)
            }
            val remaining = step.remaining
            if (remaining != null) {
                onCompositionUpdated(remaining)
            } else {
                candidateBarView.setCandidates(emptyList(), 0)
            }
        } finally {
            inputConnection?.endBatchEdit()
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
        val shiftActive = state.shift != ShiftState.OFF
        val uppercaseLetters = state.inputMode == InputMode.DIRECT_ALNUM && shiftActive
        keyboardView.render(layout, colors, uppercaseLetters, shiftActive, enterKeyBehavior.label) { action ->
            stateMachine.visualStateFor(action)
        }
        // Keeps the gesture-safe-area padding (see applyGestureSafeAreaPadding) visually
        // seamless with row 4 instead of showing as a mismatched strip below it.
        inputRootView.setBackgroundColor(colors.background)
    }

    private companion object {
        const val ROW_COUNT = 4
    }
}
