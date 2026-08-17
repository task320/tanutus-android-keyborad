package com.tanutus.ime.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpaceGestureDetectorTest {
    private val recorded = mutableListOf<SpaceGestureAction>()
    private val listener = SpaceGestureListener { recorded.add(it) }
    private lateinit var detector: SpaceGestureDetector

    private fun sample(x: Float, y: Float, t: Long = 0L) = TouchSample(x, y, t)

    @Before
    fun setUp() {
        recorded.clear()
    }

    @Test
    fun `short tap within slop emits TAP only`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(3f, 2f))
        detector.onUp(sample(3f, 2f))
        assertEquals(listOf(SpaceGestureAction.TAP), recorded)
    }

    @Test
    fun `long press timer fires LONG_PRESS when finger has not moved`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onLongPressTimerFired()
        assertEquals(listOf(SpaceGestureAction.LONG_PRESS), recorded)

        // A subsequent onUp must not also emit TAP.
        detector.onUp(sample(0f, 0f))
        assertEquals(listOf(SpaceGestureAction.LONG_PRESS), recorded)
    }

    @Test
    fun `moving past slop before the timer fires suppresses LONG_PRESS`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 1000f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(50f, 0f))
        detector.onLongPressTimerFired()
        assertFalse(recorded.contains(SpaceGestureAction.LONG_PRESS))
    }

    @Test
    fun `horizontal drag emits one cursor step per threshold crossing`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(60f, 0f))
        assertEquals(listOf(SpaceGestureAction.CURSOR_RIGHT), recorded)
    }

    @Test
    fun `fast horizontal flick emits multiple cursor steps from one move event`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(170f, 0f))
        assertEquals(
            listOf(SpaceGestureAction.CURSOR_RIGHT, SpaceGestureAction.CURSOR_RIGHT, SpaceGestureAction.CURSOR_RIGHT),
            recorded,
        )
    }

    @Test
    fun `vertical drag emits cursor up and down steps`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(0f, 60f))
        assertEquals(listOf(SpaceGestureAction.CURSOR_DOWN), recorded)

        recorded.clear()
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(0f, -60f))
        assertEquals(listOf(SpaceGestureAction.CURSOR_UP), recorded)
    }

    @Test
    fun `diagonal movement locks to the initial dominant axis`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 100f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        // First movement past slop is horizontally dominant -> locks to HORIZONTAL.
        detector.onMove(sample(20f, 5f))
        // Even though the vertical component later dominates, the axis stays locked.
        detector.onMove(sample(20f, 200f))
        assertTrue(recorded.none { it == SpaceGestureAction.CURSOR_UP || it == SpaceGestureAction.CURSOR_DOWN })
    }

    @Test
    fun `left drag emits CURSOR_LEFT`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(100f, 0f))
        detector.onMove(sample(40f, 0f))
        assertEquals(listOf(SpaceGestureAction.CURSOR_LEFT), recorded)
    }

    @Test
    fun `cancel resets state and emits nothing`() {
        detector = SpaceGestureDetector(listener, dragThresholdPx = 50f, tapSlopPx = 12f)
        detector.onDown(sample(0f, 0f))
        detector.onMove(sample(3f, 2f))
        detector.onCancel()
        detector.onLongPressTimerFired()
        detector.onUp(sample(3f, 2f))
        assertTrue(recorded.isEmpty())
    }
}
