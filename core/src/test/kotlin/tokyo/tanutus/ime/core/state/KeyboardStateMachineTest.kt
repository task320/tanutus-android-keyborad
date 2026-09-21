package tokyo.tanutus.ime.core.state

import tokyo.tanutus.ime.core.layout.KeyAction
import tokyo.tanutus.ime.core.layout.Layer
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
    fun `shift tap from off enters momentary without haptic but with its own fill`() {
        val transition = machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.MOMENTARY, transition.newState.shift)
        // The tick is reserved for locking/unlocking; arming one character is not that, even
        // though the key now tints to show it.
        assertFalse(transition.hapticFeedback)
        assertEquals(KeyFill.SHIFT_MOMENTARY, machine.visualStateFor(KeyAction.Shift).fill)
    }

    @Test
    fun `momentary, locked and off are three distinct shift fills`() {
        assertEquals(KeyFill.NORMAL, machine.visualStateFor(KeyAction.Shift).fill)

        machine.dispatch(StateEvent.ShiftTap)
        assertEquals(KeyFill.SHIFT_MOMENTARY, machine.visualStateFor(KeyAction.Shift).fill)

        machine.dispatch(StateEvent.ShiftDoubleTap)
        assertEquals(KeyFill.SHIFT_LOCKED, machine.visualStateFor(KeyAction.Shift).fill)
    }

    @Test
    fun `committing a character clears the momentary fill`() {
        machine.dispatch(StateEvent.ShiftTap)
        machine.dispatch(StateEvent.CharacterCommitted)
        assertEquals(KeyFill.NORMAL, machine.visualStateFor(KeyAction.Shift).fill)
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
        machine.dispatch(StateEvent.ShiftDoubleTap)
        val transition = machine.dispatch(StateEvent.CharacterCommitted)
        assertEquals(ShiftState.LOCKED, transition.newState.shift)
    }

    @Test
    fun `shift double tap engages lock with haptic and updates fill visual`() {
        val transition = machine.dispatch(StateEvent.ShiftDoubleTap)
        assertEquals(ShiftState.LOCKED, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
        assertEquals(KeyFill.SHIFT_LOCKED, machine.visualStateFor(KeyAction.Shift).fill)
    }

    @Test
    fun `shift double tap again disengages lock with haptic`() {
        machine.dispatch(StateEvent.ShiftDoubleTap)
        val transition = machine.dispatch(StateEvent.ShiftDoubleTap)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
        assertEquals(KeyFill.NORMAL, machine.visualStateFor(KeyAction.Shift).fill)
    }

    @Test
    fun `shift tap while locked exits to off with haptic since fill visual changes`() {
        machine.dispatch(StateEvent.ShiftDoubleTap)
        val transition = machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.OFF, transition.newState.shift)
        assertTrue(transition.hapticFeedback)
    }

    @Test
    fun `layer toggle flips layer with haptic and fill visual`() {
        val toSymbol = machine.dispatch(StateEvent.LayerToggleTap)
        assertEquals(Layer.SYMBOL, toSymbol.newState.layer)
        assertTrue(toSymbol.hapticFeedback)
        assertEquals(KeyFill.LOCKED, machine.visualStateFor(KeyAction.LayerToggle).fill)

        val toBase = machine.dispatch(StateEvent.LayerToggleTap)
        assertEquals(Layer.BASE, toBase.newState.layer)
        assertTrue(toBase.hapticFeedback)
        assertEquals(KeyFill.NORMAL, machine.visualStateFor(KeyAction.LayerToggle).fill)
    }

    @Test
    fun `romaji toggle flips input mode with haptic and fill visual`() {
        val toDirect = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.DIRECT_ALNUM, toDirect.newState.inputMode)
        assertTrue(toDirect.hapticFeedback)
        assertEquals(KeyFill.LOCKED, machine.visualStateFor(KeyAction.RomajiToggle).fill)

        val toRomaji = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.ROMAJI, toRomaji.newState.inputMode)
        assertEquals(KeyFill.NORMAL, machine.visualStateFor(KeyAction.RomajiToggle).fill)
    }

    @Test
    fun `returning to romaji mode drops a locked or momentary shift`() {
        machine.dispatch(StateEvent.RomajiToggleTap) // -> DIRECT_ALNUM
        machine.dispatch(StateEvent.ShiftDoubleTap) // -> LOCKED

        val backToRomaji = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.ROMAJI, backToRomaji.newState.inputMode)
        assertEquals(ShiftState.OFF, backToRomaji.newState.shift)
    }

    @Test
    fun `switching to direct-alnum mode does not touch an already-off shift`() {
        val toDirect = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(ShiftState.OFF, toDirect.newState.shift)
    }

    @Test
    fun `a shift armed during romaji input does not follow the user into direct-alnum`() {
        // Layer 1's letters ignore shift while composing romaji, so tapping Shift there changes
        // nothing on screen (a momentary shift draws no fill either). Carrying it across the
        // mode toggle made the whole keyboard jump to uppercase with no visible cause.
        machine.dispatch(StateEvent.ShiftTap)
        assertEquals(ShiftState.MOMENTARY, machine.state.shift)

        val toDirect = machine.dispatch(StateEvent.RomajiToggleTap)
        assertEquals(InputMode.DIRECT_ALNUM, toDirect.newState.inputMode)
        assertEquals(ShiftState.OFF, toDirect.newState.shift)
    }

    @Test
    fun `romaji toggle long press latches zenkaku without touching the input mode`() {
        // A long press suppresses the key's ordinary tap, so unlike Shift's double-tap this
        // never has a preceding RomajiToggleTap to undo — the input mode must stay put.
        val latched = machine.dispatch(StateEvent.RomajiToggleLongPress)

        assertTrue(latched.newState.zenkaku)
        assertEquals(InputMode.ROMAJI, latched.newState.inputMode)
        assertTrue(latched.hapticFeedback)
        // Zenkaku and direct-alnum are independent states on the same key, so zenkaku gets the
        // outline channel and leaves the fill alone — otherwise turning on full-width looks
        // identical to switching to direct alphanumeric. See KeyVisualState.
        assertEquals(
            KeyVisualState(fill = KeyFill.NORMAL, outlined = true),
            machine.visualStateFor(KeyAction.RomajiToggle),
        )
    }

    @Test
    fun `a second long press unlatches zenkaku again`() {
        machine.dispatch(StateEvent.RomajiToggleLongPress)
        val unlatched = machine.dispatch(StateEvent.RomajiToggleLongPress)

        assertFalse(unlatched.newState.zenkaku)
        assertEquals(KeyVisualState(), machine.visualStateFor(KeyAction.RomajiToggle))
    }

    @Test
    fun `direct-alnum and zenkaku together are distinguishable from either one alone`() {
        machine.dispatch(StateEvent.RomajiToggleLongPress) // zenkaku on, still romaji
        assertEquals(
            KeyVisualState(fill = KeyFill.NORMAL, outlined = true),
            machine.visualStateFor(KeyAction.RomajiToggle),
        )

        machine.dispatch(StateEvent.RomajiToggleTap) // now also direct-alnum
        assertEquals(
            KeyVisualState(fill = KeyFill.LOCKED, outlined = true),
            machine.visualStateFor(KeyAction.RomajiToggle),
        )
    }

    @Test
    fun `non-toggle key actions are never shown as filled`() {
        machine.dispatch(StateEvent.LayerToggleTap)
        machine.dispatch(StateEvent.ShiftDoubleTap)
        assertEquals(KeyVisualState(), machine.visualStateFor(KeyAction.Char('a')))
        assertEquals(KeyVisualState(), machine.visualStateFor(KeyAction.Space))
        assertEquals(KeyVisualState(), machine.visualStateFor(KeyAction.Enter))
        assertEquals(KeyVisualState(), machine.visualStateFor(KeyAction.Backspace))
    }
}
