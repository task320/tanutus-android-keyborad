package com.tanutus.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
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
 * Canvas-drawn keyboard surface. Rows 1-3 swap contents when the layer toggles; row 4 and
 * Backspace never move, because [rowRects] is computed once from the row *structure* (key
 * counts/weights), which is identical across [KeyboardLayouts.BASE_LAYOUT] and
 * [KeyboardLayouts.SYMBOL_LAYOUT] (see KeyboardLayoutDataTest) — [render] only rebinds which
 * [KeyDef] occupies each precomputed slot, it never triggers a relayout.
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

        private var layout: KeyboardLayout = KeyboardLayouts.BASE_LAYOUT
        private var colors: KeyboardColors = KeyboardThemeProvider.themeFor(Layer.BASE, context)
        private var isLockedVisual: (KeyAction) -> Boolean = { false }

        private var rowRects: Array<Array<RectF>> = emptyArray()

        private val keyBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keyTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = resources.getDimension(R.dimen.key_label_text_size)
            }
        private val keyGapPx = resources.getDimension(R.dimen.key_gap)

        private val interactionHandler = Handler(Looper.getMainLooper())
        private val longPressTimeoutMs = resources.getInteger(R.integer.long_press_timeout_ms).toLong()
        private val backspaceRepeatIntervalMs = resources.getInteger(R.integer.backspace_repeat_interval_ms).toLong()

        private var pressedKey: KeyDef? = null
        private var longPressHandled = false
        private val longPressRunnable = Runnable { onLongPressTriggered() }
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

        /** Single entry point the service calls on every state change to keep drawing in sync. */
        fun render(newLayout: KeyboardLayout, newColors: KeyboardColors, lockedVisual: (KeyAction) -> Boolean) {
            layout = newLayout
            colors = newColors
            isLockedVisual = lockedVisual
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeRowRects(w, h)
        }

        private fun computeRowRects(w: Int, h: Int) {
            val rows = layout.rows
            if (rows.isEmpty() || w == 0 || h == 0) {
                rowRects = emptyArray()
                return
            }
            val rowHeight = h.toFloat() / rows.size
            rowRects =
                Array(rows.size) { rowIndex ->
                    val row = rows[rowIndex]
                    val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                    val top = rowHeight * rowIndex
                    val bottom = top + rowHeight
                    var x = 0f
                    Array(row.keys.size) { colIndex ->
                        val width = w * (row.keys[colIndex].widthWeight / totalWeight)
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
                    keyBackgroundPaint.color = if (locked) colors.keyBackgroundLocked else colors.keyBackgroundNormal
                    canvas.drawRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, keyBackgroundPaint)
                    keyTextPaint.color = if (locked) colors.keyTextLocked else colors.keyTextNormal
                    val textY = rect.centerY() - (keyTextPaint.descent() + keyTextPaint.ascent()) / 2f
                    canvas.drawText(key.label, rect.centerX(), textY, keyTextPaint)
                }
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
            if (key.action == KeyAction.Shift || key.action == KeyAction.RomajiToggle || key.action == KeyAction.Backspace) {
                interactionHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }
        }

        private fun onLongPressTriggered() {
            longPressHandled = true
            when (pressedKey?.action) {
                KeyAction.Shift -> keyboardActionListener?.onShiftLongPress()
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
                KeyAction.Backspace -> listener.onBackspace()
                KeyAction.Shift -> listener.onShiftTap()
                KeyAction.LayerToggle -> listener.onLayerToggleTap()
                KeyAction.RomajiToggle -> listener.onRomajiToggleTap()
                KeyAction.Enter -> listener.onEnter()
                KeyAction.Space -> Unit // space is handled entirely via the gesture path above
            }
        }
    }
