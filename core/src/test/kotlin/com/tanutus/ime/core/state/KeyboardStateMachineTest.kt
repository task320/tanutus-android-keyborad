package com.tanutus.ime.core.state

import com.tanutus.ime.core.layout.KeyAction
import com.tanutus.ime.core.layout.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyboardStateMachineTest {
    private lateinit var machine: KeyboardStateMachine

    @Before
    fun setUp() {
        machine = KeyboardStateMachine()
    }

    @Test
    fun `shift tap from off enters momentary without haptic`() {
        val transition = machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.MOMENTARY, transition.newState.shift)
        assertFalse(transition.hapticFeedback)
        assertFalse(machine.isLockedVisual(KeyAction.Shift))
    }

    @Test
    fun `shift tap from momentary returns to off without haptic`() {
        machine.dispatch(StateEvent.ShiftTap)
        val transition = machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertFalse(transition.hapticFeedback)
    }

    @Test
    fun `character committed auto-resets momentary shift silently`() {
        machine.dispatch(StateEvent.ShiftTap)
        val transition = machine.dispatch(StateEvent.CharacterCommitted)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertFalse(transition.hapticFeedback)
    }

    @Test
    fun `character committed does not affect locked shift`() {
        machine.dispatch(StateEvent.ShiftLongPress)
        val transition = machine.dispatch(StateEvent.CharacterCommitted)
        assertEquals(ShiftState.LOCKED, transition.newState.shift)
    }

    @Test
    fun `shift long press engages lock with haptic and updates fill visual`() {
        val transition = machine.dispatch(StateEvent.ShiftLongPress)
        assertEquals(ShiftState.LOCKED, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
        assertTrue(machine.isLockedVisual(KeyAction.Shift))
    }

    @Test
    fun `shift long press again disengages lock with haptic`() {
        machine.dispatch(StateEvent.ShiftLongPress)
        val transition = machine.dispatch(StateEvent.ShiftLongPress)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
        assertFalse(machine.isLockedVisual(KeyAction.Shift))
    }

    @Test
    fun `shift tap while locked exits to off with haptic since fill visual changes`() {
        machine.dispatch(StateEvent.ShiftLongPress)
        val transition = machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
    }

    @Test
    fun `layer toggle flips layer with haptic and fill visual`() {
        val toSymbol = machine.dispatch(StateEvent.LayerToggleTap)
        assertEquals(Layer.SYMBOL, toSymbol.newState.layer)
        assertTrue(toSymbol.hapticFeedback)
        assertTrue(machine.isLockedVisual(KeyAction.LayerToggle))

        val toBase = machine.dispatch(StateEvent.LayerToggleTap)
        assertEquals(Layer.BASE, toBase.newState.layer)
        assertTrue(toBase.hapticFeedback)
        assertFalse(machine.isLockedVisual(KeyAction.LayerToggle))
    }

    @Test
    fun `romaji toggle flips input mode with haptic and fill visual`() {
        val toDirect = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.DIRECT_ALNUM, toDirect.newState.inputMode)
        assertTrue(toDirect.hapticFeedback)
        assertTrue(machine.isLockedVisual(KeyAction.RomajiToggle))

        val toRomaji = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.ROMAJI, toRomaji.newState.inputMode)
        assertFalse(machine.isLockedVisual(KeyAction.RomajiToggle))
    }

    @Test
    fun `romaji toggle long press flips zenkaku with haptic and fill visual`() {
        val toZenkaku = machine.dispatch(StateEvent.RomajiToggleLongPress)
        assertTrue(toZenkaku.newState.zenkaku)
        assertTrue(toZenkaku.hapticFeedback)
        assertTrue(machine.isLockedVisual(KeyAction.RomajiToggle))
    }

    @Test
    fun `non-toggle key actions are never shown as filled`() {
        machine.dispatch(StateEvent.LayerToggleTap)
        machine.dispatch(StateEvent.ShiftLongPress)
        assertFalse(machine.isLockedVisual(KeyAction.Char('a')))
        assertFalse(machine.isLockedVisual(KeyAction.Space))
        assertFalse(machine.isLockedVisual(KeyAction.Enter))
        assertFalse(machine.isLockedVisual(KeyAction.Backspace))
    }
}
