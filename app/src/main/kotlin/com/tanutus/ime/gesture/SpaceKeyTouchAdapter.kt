package com.tanutus.ime.gesture

import android.os.Handler
import android.view.MotionEvent
import com.tanutus.ime.core.gesture.SpaceGestureDetector
import com.tanutus.ime.core.gesture.TouchSample

/**
 * The only Android-aware piece of the space key's gesture handling: converts [MotionEvent]s
 * into the platform-agnostic [TouchSample]s [SpaceGestureDetector] expects, and owns the
 * [Handler] that schedules the long-press timer callback the detector can't schedule itself.
 */
class SpaceKeyTouchAdapter(
    private val detector: SpaceGestureDetector,
    private val handler: Handler,
) {
    private val longPressRunnable = Runnable { detector.onLongPressTimerFired() }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val sample = TouchSample(event.x, event.y, event.eventTime)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                detector.onDown(sample)
                handler.postDelayed(longPressRunnable, detector.longPressTimeoutMillis())
            }
            MotionEvent.ACTION_MOVE -> detector.onMove(sample)
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                detector.onUp(sample)
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                detector.onCancel()
            }
        }
        return true
    }
}
