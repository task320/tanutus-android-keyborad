package com.tanutus.ime.core.gesture

import kotlin.math.abs

/** A single touch sample, decoupled from Android's `MotionEvent` so this file has zero Android imports. */
data class TouchSample(val x: Float, val y: Float, val timeMillis: Long)

enum class SpaceGestureAction {
    TAP,
    LONG_PRESS,
    CURSOR_LEFT,
    CURSOR_RIGHT,
    CURSOR_UP,
    CURSOR_DOWN,
}

fun interface SpaceGestureListener {
    fun onSpaceGesture(action: SpaceGestureAction)
}

private enum class Axis { HORIZONTAL, VERTICAL }

/**
 * Disambiguates the space key's stacked gestures (tap / long-press / left-right cursor drag /
 * up-down cursor drag) from one pointer-down-to-up sequence.
 *
 * The first movement past [tapSlopPx] locks the gesture to whichever axis had the larger
 * displacement at that moment (preventing diagonal jitter from firing both axes) and
 * permanently suppresses [SpaceGestureAction.LONG_PRESS] for this touch. While locked to an
 * axis, a fixed [dragThresholdPx] is crossed repeatedly (a `while` loop, so a fast flick that
 * crosses multiple thresholds within one `onMove` call emits multiple cursor-move actions) —
 * this is the "drag" cursor-movement mechanism described in docs/keyboard-spec.md, shared by
 * both the left-right and up-down swipe.
 *
 * The caller (an Android-aware adapter) owns the long-press timer and must call
 * [onLongPressTimerFired] itself after [longPressTimeoutMillis] has elapsed since [onDown],
 * normally via `Handler.postDelayed`.
 */
class SpaceGestureDetector(
    private val listener: SpaceGestureListener,
    private val dragThresholdPx: Float,
    private val longPressTimeoutMillis: Long = 500L,
    private val tapSlopPx: Float = 12f,
) {
    private var downSample: TouchSample? = null
    private var movedBeyondSlop = false
    private var axis: Axis? = null
    private var lastCrossingX = 0f
    private var lastCrossingY = 0f
    private var longPressFired = false

    fun onDown(sample: TouchSample) {
        downSample = sample
        movedBeyondSlop = false
        axis = null
        longPressFired = false
        lastCrossingX = sample.x
        lastCrossingY = sample.y
    }

    fun onMove(sample: TouchSample) {
        val down = downSample ?: return
        val dx = sample.x - down.x
        val dy = sample.y - down.y

        if (!movedBeyondSlop) {
            if (kotlin.math.max(abs(dx), abs(dy)) <= tapSlopPx) return
            movedBeyondSlop = true
            axis = if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
        }

        when (axis) {
            Axis.HORIZONTAL -> {
                var delta = sample.x - lastCrossingX
                while (abs(delta) >= dragThresholdPx) {
                    if (delta > 0) {
                        listener.onSpaceGesture(SpaceGestureAction.CURSOR_RIGHT)
                        lastCrossingX += dragThresholdPx
                    } else {
                        listener.onSpaceGesture(SpaceGestureAction.CURSOR_LEFT)
                        lastCrossingX -= dragThresholdPx
                    }
                    delta = sample.x - lastCrossingX
                }
            }
            Axis.VERTICAL -> {
                var delta = sample.y - lastCrossingY
                while (abs(delta) >= dragThresholdPx) {
                    if (delta > 0) {
                        listener.onSpaceGesture(SpaceGestureAction.CURSOR_DOWN)
                        lastCrossingY += dragThresholdPx
                    } else {
                        listener.onSpaceGesture(SpaceGestureAction.CURSOR_UP)
                        lastCrossingY -= dragThresholdPx
                    }
                    delta = sample.y - lastCrossingY
                }
            }
            null -> Unit
        }
    }

    fun onUp(sample: TouchSample) {
        if (downSample != null && !movedBeyondSlop && !longPressFired) {
            listener.onSpaceGesture(SpaceGestureAction.TAP)
        }
        downSample = null
    }

    fun onCancel() {
        downSample = null
        movedBeyondSlop = false
        axis = null
        longPressFired = false
    }

    /** Called by the app-layer timer after [longPressTimeoutMillis] have elapsed since [onDown]. */
    fun onLongPressTimerFired() {
        if (downSample != null && !movedBeyondSlop && !longPressFired) {
            longPressFired = true
            listener.onSpaceGesture(SpaceGestureAction.LONG_PRESS)
        }
    }

    fun longPressTimeoutMillis(): Long = longPressTimeoutMillis
}
