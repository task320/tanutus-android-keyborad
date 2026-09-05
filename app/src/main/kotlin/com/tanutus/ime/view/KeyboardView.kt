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
import com.tanutus.ime.gesture.SpaceKeyTouchAdapter
import com.tanutus.ime.theme.KeyboardColors
import com.tanutus.ime.theme.KeyboardThemeProvider

/**
 * Canvas-drawn keyboard surface. Row 4's structure is identical across [Layer.BASE] and
 * [Layer.SYMBOL] for a given [com.tanutus.ime.core.state.InputMode] (see
 * KeyboardLayoutDataTest), so switching layers never needs a relayout — but it does differ
 * *between* input modes (Shift is only present in direct-alnum's row 4), so [render]
 * recomputes [rowRects] on every call rather than assuming the row structure is fixed.
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
        private var isLockedVisual: (KeyAction) -> Boolean = { false }
        private var uppercaseLetterKeys: Boolean = false
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

        private val interactionHandler = Handler(Looper.getMainLooper())
        private val longPressTimeoutMs = resources.getInteger(R.integer.long_press_timeout_ms).toLong()
        private val backspaceRepeatIntervalMs = resources.getInteger(R.integer.backspace_repeat_interval_ms).toLong()
        private val doubleTapTimeoutMs = resources.getInteger(R.integer.double_tap_timeout_ms).toLong()

        private var pressedKey: KeyDef? = null
        private var longPressHandled = false
        private val longPressRunnable = Runnable { onLongPressTriggered() }
        private var lastShiftTapUptimeMs = 0L
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
         * [enterLabel] is the resolved label for the focused field's IME action (see
         * [com.tanutus.ime.editor.EditorInfoActionMapper]) — "検索", "送信", … — which
         * docs/keyboard-spec.md asks the Enter key to show alongside performing that action.
         */
        fun render(
            newLayout: KeyboardLayout,
            newColors: KeyboardColors,
            uppercaseLetters: Boolean = false,
            enterLabel: String = DEFAULT_ENTER_LABEL,
            lockedVisual: (KeyAction) -> Boolean,
        ) {
            layout = newLayout
            colors = newColors
            uppercaseLetterKeys = uppercaseLetters
            enterKeyLabel = enterLabel
            isLockedVisual = lockedVisual
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
            // Row 0's per-unit-weight width is the shared reference a `centered` row's keys are
            // sized against, so they line up with row 0's columns instead of stretching to fill
            // the full width themselves (see KeyRow.centered).
            val unitWidth = w / rows[0].keys.sumOf { it.widthWeight.toDouble() }.toFloat()
            val rowHeight = h.toFloat() / rows.size
            rowRects =
                Array(rows.size) { rowIndex ->
                    val row = rows[rowIndex]
                    val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                    val top = rowHeight * rowIndex
                    val bottom = top + rowHeight
                    var x = if (row.centered) (w - unitWidth * totalWeight) / 2f else 0f
                    Array(row.keys.size) { colIndex ->
                        val width =
                            if (row.centered) unitWidth * row.keys[colIndex].widthWeight else w * (row.keys[colIndex].widthWeight / totalWeight)
                        val rect = RectF(x, top, x + width, bottom)
                        x += width
                        rect
                    }
                }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(colors.background)
            val rows = layout.rows
            val inset = keyGapPx / 2f
            for (rowIndex in rows.indices) {
                val keys = rows[rowIndex].keys
                val rects = rowRects.getOrNull(rowIndex) ?: continue
                for (colIndex in keys.indices) {
                    val key = keys[colIndex]
                    val rect = rects.getOrNull(colIndex) ?: continue
                    val locked = isLockedVisual(key.action)
                    keyBackgroundPaint.color =
                        when {
                            locked && key.action == KeyAction.Shift -> colors.keyBackgroundShiftLocked
                            locked -> colors.keyBackgroundLocked
                            else -> colors.keyBackgroundNormal
                        }
                    canvas.drawRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, keyBackgroundPaint)
                    keyTextPaint.color = if (locked) colors.keyTextLocked else colors.keyTextNormal
                    val label = labelFor(key)
                    fitLabelToKey(label, rect)
                    val textY = rect.centerY() - (keyTextPaint.descent() + keyTextPaint.ascent()) / 2f
                    canvas.drawText(label, rect.centerX(), textY, keyTextPaint)
                }
            }
        }

        private fun labelFor(key: KeyDef): String =
            when {
                key.action == KeyAction.Enter -> enterKeyLabel
                uppercaseLetterKeys && key.action is KeyAction.Char -> key.label.uppercase()
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
            return rowIndex to (if (x < rects.first().left) 0 else rects.size - 1)
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
            when (val action = key.action) {
                is KeyAction.Char -> listener.onKeyChar(action.char)
                is KeyAction.ShiftPair -> listener.onShiftPairKey(action)
                is KeyAction.Punctuation -> listener.onPunctuationKey(action.char)
                KeyAction.Backspace -> listener.onBackspace()
                KeyAction.Shift -> dispatchShiftTap(listener)
                KeyAction.LayerToggle -> listener.onLayerToggleTap()
                KeyAction.RomajiToggle -> listener.onRomajiToggleTap()
                KeyAction.Enter -> listener.onEnter()
                KeyAction.Space -> Unit // space is handled entirely via the gesture path above
            }
        }

        /**
         * Shift-lock is two quick taps rather than a long-press (see docs/keyboard-spec.md). A
         * tap within [doubleTapTimeoutMs] of the previous one is the lock gesture *instead of*
         * an ordinary tap (not in addition to one) — dispatching both would let the first tap's
         * own state change (e.g. LOCKED -> OFF, since a single tap already exits the lock) throw
         * off what "double-tap" toggles from. [lastShiftTapUptimeMs] resets to 0 after consuming
         * a double-tap so a third quick tap starts a fresh pair rather than chaining into
         * another lock toggle.
         */
        private fun dispatchShiftTap(listener: KeyboardActionListener) {
            val now = SystemClock.uptimeMillis()
            val isDoubleTap = lastShiftTapUptimeMs != 0L && now - lastShiftTapUptimeMs <= doubleTapTimeoutMs
            lastShiftTapUptimeMs = if (isDoubleTap) 0L else now
            if (isDoubleTap) listener.onShiftDoubleTap() else listener.onShiftTap()
        }

        private companion object {
            /** Shown until a field is focused; matches EditorInfoActionMapper's newline label. */
            const val DEFAULT_ENTER_LABEL = "⏎"
            const val LABEL_PADDING_PX = 6f
        }
    }
