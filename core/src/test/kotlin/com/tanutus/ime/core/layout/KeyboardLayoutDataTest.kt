package com.tanutus.ime.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutDataTest {
    @Test
    fun `row 4 is the identical shared instance on both layers`() {
        // This is the invariant KeyboardView's fixed-rect drawing trick depends on: row 4
        // (and Backspace, checked separately below) must never visually move when the layer
        // switches, and referential equality is what guarantees that at the data level.
        assertSame(KeyboardLayouts.BASE_LAYOUT.rows[3], KeyboardLayouts.SYMBOL_LAYOUT.rows[3])
        assertSame(KeyboardLayouts.FUNCTION_ROW, KeyboardLayouts.BASE_LAYOUT.rows[3])
    }

    @Test
    fun `both layers have the same key counts per row`() {
        val base = KeyboardLayouts.BASE_LAYOUT.rows.map { it.keys.size }
        val symbol = KeyboardLayouts.SYMBOL_LAYOUT.rows.map { it.keys.size }
        assertEquals(listOf(11, 9, 7, 5), base)
        assertEquals(listOf(11, 9, 7, 5), symbol)
    }

    @Test
    fun `backspace is the last key of row 1 on both layers`() {
        assertEquals(KeyAction.Backspace, KeyboardLayouts.BASE_LAYOUT.rows[0].keys.last().action)
        assertEquals(KeyAction.Backspace, KeyboardLayouts.SYMBOL_LAYOUT.rows[0].keys.last().action)
    }

    @Test
    fun `function row order matches the spec left-to-right`() {
        val actions = KeyboardLayouts.FUNCTION_ROW.keys.map { it.action }
        assertEquals(
            listOf(KeyAction.Shift, KeyAction.LayerToggle, KeyAction.Space, KeyAction.RomajiToggle, KeyAction.Enter),
            actions,
        )
    }

    @Test
    fun `symbol layer shift pairs match spec`() {
        val row2 = KeyboardLayouts.SYMBOL_LAYOUT.rows[1].keys
        val hyphen = row2.first { it.id == "key_hyphen" }.action
        assertEquals(KeyAction.ShiftPair('-', '_'), hyphen)

        val row3 = KeyboardLayouts.SYMBOL_LAYOUT.rows[2].keys
        val quote = row3.first { it.id == "key_quote" }.action
        assertEquals(KeyAction.ShiftPair('\'', '"'), quote)
    }

    @Test
    fun `layoutFor returns the matching layout`() {
        assertSame(KeyboardLayouts.BASE_LAYOUT, KeyboardLayouts.layoutFor(Layer.BASE))
        assertSame(KeyboardLayouts.SYMBOL_LAYOUT, KeyboardLayouts.layoutFor(Layer.SYMBOL))
    }

    @Test
    fun `all key ids within a layout are unique`() {
        for (layout in listOf(KeyboardLayouts.BASE_LAYOUT, KeyboardLayouts.SYMBOL_LAYOUT)) {
            val ids = layout.rows.flatMap { it.keys.map { key -> key.id } }
            assertTrue("duplicate key ids in $layout: $ids", ids.size == ids.toSet().size)
        }
    }
}
