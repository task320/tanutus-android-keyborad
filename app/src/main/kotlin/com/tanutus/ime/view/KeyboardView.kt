package com.tanutus.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tanutus.ime.R
import com.tanutus.ime.core.gesture.SpaceGestureDetector
import com.tanutus.ime.core.gesture.SpaceGestureListener
import com.tanutus.ime.core.input.KeyboardActionListener
import com.tanutus.ime.core.layout.KeyAction
import com.tanutus.ime.core.layout.KeyDef
import com.tanutus.ime.core.layout.KeyboardLayout
import com.tanutus.ime.core.layout.KeyboardLayouts
import com.tanutus.ime.core.layout.Layer
import com.tanutus.ime.core.layout.RowAlignment
import com.tanutus.ime.core.state.KeyFill
import com.tanutus.ime.core.state.KeyVisualState
import com.tanutus.ime.gesture.SpaceKeyTouchAdapter
import com.tanutus.ime.theme.KeyboardColors
import com.tanutus.ime.theme.KeyboardThemeProvider
import kotlin.math.abs

/**
 * Canvas-drawn keyboard surface. Row 4's structure is identical across [Layer.BASE] and
 * [Layer.SYMBOL] for a given [com.tanutus.ime.core.state.InputMode] (see
 * KeyboardLayoutDataTest), so switching layers never needs a relayout — but it does differ
 * *between* layers (row 3 carries a different number of symbol keys), so [render] recomputes
 * [rowRects] on every call rather than assuming the row structure is fixed.
 *
 * The space key's touch stream is handed off entirely to [spaceTouchAdapter] /
 * [SpaceGestureDetector] for the duration of that gesture; every other key uses a simple
 * generic tap/long-press dispatch below.
 */
class KeyboardView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        var keyboardActionListener: KeyboardActionListener? = null
        var spaceGestureListener: SpaceGestureListener? = null

        private var layout: KeyboardLayout = KeyboardLayouts.BASE_LAYOUT_ROMAJI
        private var colors: KeyboardColors = KeyboardThemeProvider.themeFor(Layer.BASE, context)
        private var visualStateFor: (KeyAction) -> KeyVisualState = { KeyVisualState() }
        private var uppercaseLetterKeys: Boolean = false
        private var shiftActiveKeys: Boolean = false
        private var enterKeyLabel: String = DEFAULT_ENTER_LABEL

        private var rowRects: Array<Array<RectF>> = emptyArray()

        private val keyBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keyLabelTextSizePx = resources.getDimension(R.dimen.key_label_text_size)
        private val keyTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = keyLabelTextSizePx
            }
        private val keyGapPx = resources.getDimension(R.dimen.key_gap)
        private val keyOutlineWidthPx = resources.getDimension(R.dimen.key_outline_width)
        private val keyOutlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = keyOutlineWidthPx
            }

        private val interactionHandler = Handler(Looper.getMainLooper())
        private val longPressTimeoutMs = resources.getInteger(R.integer.long_press_timeout_ms).toLong()
        private val backspaceRepeatIntervalMs = resources.getInteger(R.integer.backspace_repeat_interval_ms).toLong()
        private val doubleTapTimeoutMs = resources.getInteger(R.integer.double_tap_timeout_ms).toLong()

        private var pressedKey: KeyDef? = null
        private var longPressHandled = false
        private val longPressRunnable = Runnable { onLongPressTriggered() }
        private var lastLatchTapKeyId: String? = null
        private var lastLatchTapUptimeMs = 0L
        private val backspaceRepeatRunnable =
            object : Runnable {
                override fun run() {
                    keyboardActionListener?.onBackspace()
                    interactionHandler.postDelayed(this, backspaceRepeatIntervalMs)
                }
            }

        private val spaceGestureProxy = SpaceGestureListener { action -> spaceGestureListener?.onSpaceGesture(action) }
        private val spaceGestureDetector =
            SpaceGestureDetector(
                listener = spaceGestureProxy,
                dragThresholdPx = resources.getDimension(R.dimen.space_drag_threshold),
                longPressTimeoutMillis = longPressTimeoutMs,
                tapSlopPx = resources.getDimension(R.dimen.space_tap_slop),
            )
        private val spaceTouchAdapter = SpaceKeyTouchAdapter(spaceGestureDetector, interactionHandler)
        private var handlingSpaceGesture = false

        /**
         * Single entry point the service calls on every state change to keep drawing in sync.
         *
         * [uppercaseLetters] only affects the drawn label of [KeyAction.Char] letter keys — it
         * intentionally does not apply while composing romaji (kana has no case, so the
         * converted output wouldn't match an uppercase glyph; see
         * [com.tanutus.ime.TanutusImeService.refreshKeyboardView]).
         *
         * [shiftActive] is the raw "shift is engaged" flag, and unlike [uppercaseLetters] it is
         * not gated on input mode: [KeyAction.ShiftPair] keys resolve to their shifted glyph in
         * either mode, so their labels have to follow in either mode too.
         *
         * [enterLabel] is the resolved label for the focused field's IME action (see
         * [com.tanutus.ime.editor.EditorInfoActionMapper]) — "検索", "送信", … — which
         * docs/keyboard-spec.md asks the Enter key to show alongside performing that action.
         */
        fun render(
            newLayout: KeyboardLayout,
            newColors: KeyboardColors,
            uppercaseLetters: Boolean = false,
            shiftActive: Boolean = false,
            enterLabel: String = DEFAULT_ENTER_LABEL,
            visualState: (KeyAction) -> KeyVisualState,
        ) {
            layout = newLayout
            colors = newColors
            uppercaseLetterKeys = uppercaseLetters
            shiftActiveKeys = shiftActive
            enterKeyLabel = enterLabel
            visualStateFor = visualState
            computeRowRects(width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeRowRects(w, h)
            excludeFromSystemGestures(w, h)
        }

        /**
         * Row 4's leftmost keys (Shift) and rightmost keys (Enter) sit flush against the
         * screen edges, the same band the system reserves for its back-gesture/nav-handle
         * touch handling. Without this, a tap there can be swallowed by system UI instead of
         * reaching [onTouchEvent] (observed on-device as the IME silently hiding itself instead
         * of registering the tap). Declaring the whole keyboard as gesture-excluded is the
         * platform-sanctioned fix other on-screen keyboards use for this.
         */
        private fun excludeFromSystemGestures(w: Int, h: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            if (w == 0 || h == 0) {
                systemGestureExclusionRects = emptyList()
                return
            }
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }

        private fun computeRowRects(w: Int, h: Int) {
            val rows = layout.rows
            if (rows.isEmpty() || w == 0 || h == 0) {
                rowRects = emptyArray()
                return
            }
            // Row 0's per-unit-weight width is the shared reference every non-STRETCH row is
            // sized against, so those rows line up with row 0's columns instead of stretching
            // their own keys to fill the width (see RowAlignment).
            val unitWidth = w / rows[0].keys.sumOf { it.widthWeight.toDouble() }.toFloat()
            val rowHeight = h.toFloat() / rows.size
            rowRects =
                Array(rows.size) { rowIndex ->
                    val row = rows[rowIndex]
                    val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                    val top = rowHeight * rowIndex
                    val bottom = top + rowHeight
                    val widths =
                        FloatArray(row.keys.size) { i ->
                            val weight = row.keys[i].widthWeight
                            if (row.alignment == RowAlignment.STRETCH) w * (weight / totalWeight) else unitWidth * weight
                        }
                    val lefts = rowKeyLefts(row.alignment, widths, w.toFloat())
                    Array(row.keys.size) { i -> RectF(lefts[i], top, lefts[i] + widths[i], bottom) }
                }
        }

        /** Left edge of each key in a row, per its [RowAlignment]. See [computeRowRects]. */
        private fun rowKeyLefts(alignment: RowAlignment, widths: FloatArray, rowWidth: Float): FloatArray {
            val lefts = FloatArray(widths.size)
            val last = widths.size - 1
            // UNIT_EDGES needs two keys to have edges to pin; with fewer, fall back to centering
            // the row rather than producing a degenerate layout.
            val pinEdges = alignment == RowAlignment.UNIT_EDGES && widths.size >= 2
            if (pinEdges) {
                lefts[0] = 0f
                lefts[last] = rowWidth - widths[last]
                val middleWidth = (1 until last).sumOf { widths[it].toDouble() }.toFloat()
                var x = widths[0] + (rowWidth - widths[0] - widths[last] - middleWidth) / 2f
                for (i in 1 until last) {
                    lefts[i] = x
                    x += widths[i]
                }
                return lefts
            }
            var x = if (alignment == RowAlignment.STRETCH) 0f else (rowWidth - widths.sum()) / 2f
            for (i in widths.indices) {
                lefts[i] = x
                x += widths[i]
            }
            return lefts
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(colors.background)
            keyOutlinePaint.color = colors.keyOutlineZenkaku
            val rows = layout.rows
            val inset = keyGapPx / 2f
            for (rowIndex in rows.indices) {
                val keys = rows[rowIndex].keys
                val rects = rowRects.getOrNull(rowIndex) ?: continue
                for (colIndex in keys.indices) {
                    val key = keys[colIndex]
                    val rect = rects.getOrNull(colIndex) ?: continue
                    val visual = visualStateFor(key.action)
                    val filled = visual.fill != KeyFill.NORMAL
                    keyBackgroundPaint.color =
                        when (visual.fill) {
                            KeyFill.SHIFT_LOCKED -> colors.keyBackgroundShiftLocked
                            KeyFill.SHIFT_MOMENTARY -> colors.keyBackgroundShiftMomentary
                            KeyFill.LOCKED -> colors.keyBackgroundLocked
                            KeyFill.NORMAL -> colors.keyBackgroundNormal
                        }
                    val left = rect.left + inset
                    val top = rect.top + inset
                    val right = rect.right - inset
                    val bottom = rect.bottom - inset
                    canvas.drawRect(left, top, right, bottom, keyBackgroundPaint)
                    if (visual.outlined) {
                        // Inset by half the stroke width so the stroke lands fully inside the
                        // key rather than straddling its edge and bleeding into the gap.
                        val half = keyOutlineWidthPx / 2f
                        canvas.drawRect(left + half, top + half, right - half, bottom - half, keyOutlinePaint)
                    }
                    keyTextPaint.color = if (filled) colors.keyTextLocked else colors.keyTextNormal
                    val label = labelFor(key)
                    fitLabelToKey(label, rect)
                    val textY = rect.centerY() - (keyTextPaint.descent() + keyTextPaint.ascent()) / 2f
                    canvas.drawText(label, rect.centerX(), textY, keyTextPaint)
                }
            }
        }

        private fun labelFor(key: KeyDef): String =
            when (val action = key.action) {
                KeyAction.Enter -> enterKeyLabel
                // A shift-pair key's two glyphs are unrelated characters, so the label has to
                // say which one is armed — unlike a letter, you cannot infer `{` from seeing `[`.
                is KeyAction.ShiftPair -> (if (shiftActiveKeys) action.shifted else action.base).toString()
                is KeyAction.Char -> if (uppercaseLetterKeys) key.label.uppercase() else key.label
                else -> key.label
            }

        /**
         * Sets [keyTextPaint]'s size for this one key, shrinking it if the label wouldn't fit.
         * Most labels are a single glyph, but the Enter key now shows the field's IME action
         * label, which an app can supply as arbitrary text via [android.view.inputmethod
         * .EditorInfo.actionLabel] — without this, a long one would overdraw its neighbours.
         */
        private fun fitLabelToKey(label: String, rect: RectF) {
            keyTextPaint.textSize = keyLabelTextSizePx
            val available = rect.width() - keyGapPx - LABEL_PADDING_PX * 2f
            if (available <= 0f) return
            val measured = keyTextPaint.measureText(label)
            if (measured > available) {
                keyTextPaint.textSize = keyLabelTextSizePx * (available / measured)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (handlingSpaceGesture) {
                spaceTouchAdapter.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    handlingSpaceGesture = false
                }
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (row, col) = findKeyAt(event.x, event.y) ?: return true
                    val key = layout.rows[row].keys[col]
                    if (key.action == KeyAction.Space) {
                        handlingSpaceGesture = true
                        spaceTouchAdapter.onTouchEvent(event)
                    } else {
                        handleGenericDown(key)
                    }
                }
                MotionEvent.ACTION_UP -> handleGenericUp()
                MotionEvent.ACTION_CANCEL -> handleGenericCancel()
                else -> Unit
            }
            return true
        }

        private fun findKeyAt(x: Float, y: Float): Pair<Int, Int>? {
            val rows = rowRects
            if (rows.isEmpty()) return null
            val rowHeight = height.toFloat() / rows.size
            val rowIndex = (y / rowHeight).toInt().coerceIn(0, rows.size - 1)
            val rects = rows[rowIndex]
            if (rects.isEmpty()) return null
            val colIndex = rects.indexOfFirst { x >= it.left && x < it.right }
            if (colIndex >= 0) return rowIndex to colIndex
            // A UNIT_CENTERED row leaves margins at both ends and a UNIT_EDGES row also leaves
            // gaps beside its pinned first/last keys, so a miss has to snap to the *nearest*
            // key. Falling through to "first or last" would hand a tap in the gap next to Shift
            // straight to Backspace at the other end of the row.
            val nearest = rects.indices.minByOrNull { abs(x - rects[it].centerX()) } ?: return null
            return rowIndex to nearest
        }

        private fun handleGenericDown(key: KeyDef) {
            pressedKey = key
            longPressHandled = false
            if (key.action == KeyAction.RomajiToggle || key.action == KeyAction.Backspace) {
                interactionHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }
        }

        private fun onLongPressTriggered() {
            longPressHandled = true
            when (pressedKey?.action) {
                KeyAction.RomajiToggle -> keyboardActionListener?.onRomajiToggleLongPress()
                KeyAction.Backspace -> {
                    keyboardActionListener?.onBackspace()
                    interactionHandler.postDelayed(backspaceRepeatRunnable, backspaceRepeatIntervalMs)
                }
                else -> Unit
            }
        }

        private fun handleGenericUp() {
            interactionHandler.removeCallbacks(longPressRunnable)
            interactionHandler.removeCallbacks(backspaceRepeatRunnable)
            val key = pressedKey
            if (key != null && !longPressHandled) {
                dispatchTap(key)
            }
            pressedKey = null
        }

        private fun handleGenericCancel() {
            interactionHandler.removeCallbacks(longPressRunnable)
            interactionHandler.removeCallbacks(backspaceRepeatRunnable)
            pressedKey = null
        }

        private fun dispatchTap(key: KeyDef) {
            val listener = keyboardActionListener ?: return
            val action = key.action
            when (action) {
                is KeyAction.Char -> listener.onKeyChar(action.char)
                is KeyAction.ShiftPair -> listener.onShiftPairKey(action)
                is KeyAction.Punctuation -> listener.onPunctuationKey(action.char)
                KeyAction.Backspace -> listener.onBackspace()
                KeyAction.Shift -> dispatchLatchableTap(key, listener::onShiftTap, listener::onShiftDoubleTap)
                KeyAction.LayerToggle -> listener.onLayerToggleTap()
                KeyAction.RomajiToggle -> listener.onRomajiToggleTap()
                KeyAction.Enter -> listener.onEnter()
                KeyAction.Space -> Unit // space is handled entirely via the gesture path above
            }
            // Any other key ends a pending latch pairing: two shift taps with a letter typed
            // between them are two separate taps, not the lock gesture, however fast they land.
            if (action != KeyAction.Shift) {
                lastLatchTapKeyId = null
            }
        }

        /**
         * Shift-lock is two quick taps rather than a long-press (see docs/keyboard-spec.md).
         * A tap within [doubleTapTimeoutMs] of the previous tap *on the same key* is the latch
         * gesture *instead of* an ordinary tap (not in addition to one) — dispatching both would
         * let the first tap's own state change (e.g. LOCKED -> OFF, since a single tap already
         * exits the lock) throw off what "double-tap" toggles from.
         *
         * The pairing is keyed by [KeyDef.id] and cleared after a double tap is consumed, so a
         * third quick tap starts a fresh pair rather than chaining into another latch toggle.
         * [dispatchTap] also clears it on any other key, so a letter typed between two Shift
         * taps keeps them from pairing up.
         *
         * Shift is the only caller today: the zenkaku latch on the romaji-toggle key is a
         * long-press, which suppresses the tap outright and so needs none of this.
         */
        private fun dispatchLatchableTap(key: KeyDef, onTap: () -> Unit, onDoubleTap: () -> Unit) {
            val now = SystemClock.uptimeMillis()
            val isDoubleTap = lastLatchTapKeyId == key.id && now - lastLatchTapUptimeMs <= doubleTapTimeoutMs
            lastLatchTapKeyId = if (isDoubleTap) null else key.id
            lastLatchTapUptimeMs = now
            if (isDoubleTap) onDoubleTap() else onTap()
        }

        private companion object {
            /** Shown until a field is focused; matches EditorInfoActionMapper's newline label. */
            const val DEFAULT_ENTER_LABEL = "⏎"
            const val LABEL_PADDING_PX = 6f
        }
    }
