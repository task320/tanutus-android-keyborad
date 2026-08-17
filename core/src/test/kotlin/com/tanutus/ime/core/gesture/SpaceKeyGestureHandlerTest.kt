package com.tanutus.ime.core.gesture

import com.tanutus.ime.core.conversion.Composition
import com.tanutus.ime.core.conversion.KanaConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A minimal fake so tests exercise [SpaceKeyGestureHandler]'s branching without a real converter. */
private class FakeKanaConverter(private var composing: Boolean) : KanaConverter {
    var nextCandidateCalls = 0
    var commitCalls = 0

    override fun input(char: Char): Composition = currentComposition()

    override fun nextCandidate(): Composition {
        nextCandidateCalls++
        return currentComposition()
    }

    override fun commit(): String {
        commitCalls++
        composing = false
        return "committed"
    }

    override fun dropLast(): Composition = currentComposition()

    override fun hasActiveComposition(): Boolean = composing

    override fun currentComposition(): Composition = Composition(rawInput = if (composing) "k" else "", text = if (composing) "か" else "")

    override fun reset() {
        composing = false
    }
}

private class RecordingActions : SpaceKeyActions {
    val calls = mutableListOf<String>()
    var lastCommittedText: String? = null
    var lastCharDelta: Int? = null
    var lastLineDelta: Int? = null

    override fun insertSpace() {
        calls.add("insertSpace")
    }

    override fun insertTab() {
        calls.add("insertTab")
    }

    override fun moveCursorChars(delta: Int) {
        calls.add("moveCursorChars")
        lastCharDelta = delta
    }

    override fun moveCursorLines(delta: Int) {
        calls.add("moveCursorLines")
        lastLineDelta = delta
    }

    override fun commitComposedText(text: String) {
        calls.add("commitComposedText")
        lastCommittedText = text
    }

    override fun onCompositionUpdated(composition: Composition) {
        calls.add("onCompositionUpdated")
    }
}

class SpaceKeyGestureHandlerTest {
    private lateinit var actions: RecordingActions

    @Before
    fun setUp() {
        actions = RecordingActions()
    }

    @Test
    fun `tap while idle inserts a space`() {
        val converter = FakeKanaConverter(composing = false)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.TAP)
        assertEquals(listOf("insertSpace"), actions.calls)
        assertEquals(0, converter.nextCandidateCalls)
    }

    @Test
    fun `tap while composing advances to next candidate instead of inserting a space`() {
        val converter = FakeKanaConverter(composing = true)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.TAP)
        assertEquals(listOf("onCompositionUpdated"), actions.calls)
        assertEquals(1, converter.nextCandidateCalls)
        assertFalse(actions.calls.contains("insertSpace"))
    }

    @Test
    fun `long press while idle inserts tab without committing`() {
        val converter = FakeKanaConverter(composing = false)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.LONG_PRESS)
        assertEquals(listOf("insertTab"), actions.calls)
        assertEquals(0, converter.commitCalls)
    }

    @Test
    fun `long press while composing commits the leading candidate then inserts tab`() {
        val converter = FakeKanaConverter(composing = true)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.LONG_PRESS)
        assertEquals(listOf("commitComposedText", "insertTab"), actions.calls)
        assertEquals(1, converter.commitCalls)
        assertEquals("committed", actions.lastCommittedText)
    }

    @Test
    fun `cursor swipe while idle just moves the cursor`() {
        val converter = FakeKanaConverter(composing = false)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.CURSOR_RIGHT)
        assertEquals(listOf("moveCursorChars"), actions.calls)
        assertEquals(1, actions.lastCharDelta)

        actions.calls.clear()
        handler.onSpaceGesture(SpaceGestureAction.CURSOR_LEFT)
        assertEquals(-1, actions.lastCharDelta)

        actions.calls.clear()
        handler.onSpaceGesture(SpaceGestureAction.CURSOR_DOWN)
        assertEquals(1, actions.lastLineDelta)

        actions.calls.clear()
        handler.onSpaceGesture(SpaceGestureAction.CURSOR_UP)
        assertEquals(-1, actions.lastLineDelta)
    }

    @Test
    fun `cursor swipe while composing commits first then moves the cursor`() {
        val converter = FakeKanaConverter(composing = true)
        val handler = SpaceKeyGestureHandler(converter, actions)
        handler.onSpaceGesture(SpaceGestureAction.CURSOR_UP)
        assertEquals(listOf("commitComposedText", "moveCursorLines"), actions.calls)
        assertTrue(converter.commitCalls == 1)
    }
}
