package com.tanutus.ime.core.layout

import com.tanutus.ime.core.state.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutDataTest {
    @Test
    fun `row 4 is the identical shared instance on both layers, per input mode`() {
        // This is the invariant KeyboardView's fixed-rect drawing trick depends on: row 4
        // (and Backspace, checked separately below) must never visually move when the layer
        // switches, and referential equality is what guarantees that at the data level. Row 4
        // does differ *across* input modes (Shift is hidden during romaji), so this is checked
        // separately per mode rather than across all four layout combinations.
        assertSame(KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[3], KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[3])
        assertSame(KeyboardLayouts.FUNCTION_ROW_ALNUM, KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[3])

        assertSame(KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows[3], KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI.rows[3])
        assertSame(KeyboardLayouts.FUNCTION_ROW_ROMAJI, KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows[3])
    }

    @Test
    fun `both layers have the same key counts per row, per input mode`() {
        val alnumBase = KeyboardLayouts.BASE_LAYOUT_ALNUM.rows.map { it.keys.size }
        val alnumSymbol = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows.map { it.keys.size }
        assertEquals(listOf(11, 9, 7, 5), alnumBase)
        assertEquals(listOf(11, 9, 8, 5), alnumSymbol)

        val romajiBase = KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows.map { it.keys.size }
        val romajiSymbol = KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI.rows.map { it.keys.size }
        assertEquals(listOf(11, 9, 7, 6), romajiBase)
        assertEquals(listOf(11, 9, 8, 6), romajiSymbol)
    }

    @Test
    fun `backspace is the last key of row 1 on both layers`() {
        assertEquals(KeyAction.Backspace, KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[0].keys.last().action)
        assertEquals(KeyAction.Backspace, KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[0].keys.last().action)
    }

    @Test
    fun `function row order matches the spec left-to-right`() {
        assertEquals(
            listOf(KeyAction.Shift, KeyAction.LayerToggle, KeyAction.Space, KeyAction.RomajiToggle, KeyAction.Enter),
            KeyboardLayouts.FUNCTION_ROW_ALNUM.keys.map { it.action },
        )
    }

    @Test
    fun `romaji function row hides shift but adds kuten and touten flanking space`() {
        assertEquals(
            listOf(
                KeyAction.LayerToggle,
                KeyAction.Punctuation('。'),
                KeyAction.Space,
                KeyAction.Punctuation('、'),
                KeyAction.RomajiToggle,
                KeyAction.Enter,
            ),
            KeyboardLayouts.FUNCTION_ROW_ROMAJI.keys.map { it.action },
        )
    }

    @Test
    fun `symbol layer shift pairs match spec`() {
        val row2 = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[1].keys
        val hyphen = row2.first { it.id == "key_hyphen" }.action
        assertEquals(KeyAction.ShiftPair('-', '_'), hyphen)

        val row3 = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[2].keys
        val quote = row3.first { it.id == "key_quote" }.action
        assertEquals(KeyAction.ShiftPair('\'', '"'), quote)
    }

    @Test
    fun `layoutFor returns the matching layout for every layer and input mode combination`() {
        assertSame(KeyboardLayouts.BASE_LAYOUT_ALNUM, KeyboardLayouts.layoutFor(Layer.BASE, InputMode.DIRECT_ALNUM))
        assertSame(KeyboardLayouts.BASE_LAYOUT_ROMAJI, KeyboardLayouts.layoutFor(Layer.BASE, InputMode.ROMAJI))
        assertSame(KeyboardLayouts.SYMBOL_LAYOUT_ALNUM, KeyboardLayouts.layoutFor(Layer.SYMBOL, InputMode.DIRECT_ALNUM))
        assertSame(KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI, KeyboardLayouts.layoutFor(Layer.SYMBOL, InputMode.ROMAJI))
    }

    @Test
    fun `all key ids within a layout are unique`() {
        val layouts =
            listOf(
                KeyboardLayouts.BASE_LAYOUT_ALNUM,
                KeyboardLayouts.BASE_LAYOUT_ROMAJI,
                KeyboardLayouts.SYMBOL_LAYOUT_ALNUM,
                KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI,
            )
        for (layout in layouts) {
            val ids = layout.rows.flatMap { it.keys.map { key -> key.id } }
            assertTrue("duplicate key ids in $layout: $ids", ids.size == ids.toSet().size)
        }
    }
}
