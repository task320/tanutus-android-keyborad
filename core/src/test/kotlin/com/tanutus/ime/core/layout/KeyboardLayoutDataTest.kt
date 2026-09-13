package com.tanutus.ime.core.layout

import com.tanutus.ime.core.state.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutDataTest {
    private val ALL_LAYOUTS =
        listOf(
            KeyboardLayouts.BASE_LAYOUT_ALNUM,
            KeyboardLayouts.BASE_LAYOUT_ROMAJI,
            KeyboardLayouts.SYMBOL_LAYOUT_ALNUM,
            KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI,
        )

    @Test
    fun `row 4 is the identical shared instance on both layers, per input mode`() {
        // This is the invariant KeyboardView's fixed-rect drawing trick depends on: row 4
        // (and Backspace, checked separately below) must never visually move when the layer
        // switches, and referential equality is what guarantees that at the data level. Row 4
        // still differs *across* input modes (which punctuation flanks the space key), so this
        // is checked separately per mode rather than across all four layout combinations.
        assertSame(KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[3], KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[3])
        assertSame(KeyboardLayouts.FUNCTION_ROW_ALNUM, KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[3])

        assertSame(KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows[3], KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI.rows[3])
        assertSame(KeyboardLayouts.FUNCTION_ROW_ROMAJI, KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows[3])
    }

    @Test
    fun `both layers have the same key counts per row, per input mode`() {
        val alnumBase = KeyboardLayouts.BASE_LAYOUT_ALNUM.rows.map { it.keys.size }
        val alnumSymbol = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows.map { it.keys.size }
        assertEquals(listOf(10, 9, 9, 6), alnumBase)
        assertEquals(listOf(10, 10, 9, 6), alnumSymbol)

        val romajiBase = KeyboardLayouts.BASE_LAYOUT_ROMAJI.rows.map { it.keys.size }
        val romajiSymbol = KeyboardLayouts.SYMBOL_LAYOUT_ROMAJI.rows.map { it.keys.size }
        assertEquals(listOf(10, 9, 9, 6), romajiBase)
        assertEquals(listOf(10, 10, 9, 6), romajiSymbol)
    }

    @Test
    fun `the symbol layer can type every ASCII punctuation mark`() {
        // The whole point of pairing most of layer 2 with Shift: before it, ! $ % & ; = ? \ ^ |
        // were unreachable from any layer. Comma and period are the exceptions — they live on
        // row 4, which this layout's rows do not include.
        val reachable =
            KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows
                .flatMap { it.keys }
                .flatMap { key ->
                    when (val action = key.action) {
                        is KeyAction.Char -> listOf(action.char)
                        is KeyAction.ShiftPair -> listOf(action.base, action.shifted)
                        else -> emptyList()
                    }
                }.toSet()
        val asciiPunctuation = (' '.code..'~'.code).map { it.toChar() }.filter { !it.isLetterOrDigit() && !it.isWhitespace() }
        assertEquals(emptySet<Char>(), (asciiPunctuation - setOf(',', '.')).toSet() - reachable)
    }

    @Test
    fun `the digit row's shift pairs follow the US layout`() {
        val digitRow = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[0].keys
        assertEquals(
            "1234567890".zip("!@#\$%^&*()").map { (base, shifted) -> KeyAction.ShiftPair(base, shifted) },
            digitRow.map { it.action },
        )
    }

    @Test
    fun `at sign sits immediately left of backspace on the symbol layer`() {
        val row3 = KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[2].keys
        assertEquals(listOf(KeyAction.Char('@'), KeyAction.Backspace), row3.takeLast(2).map { it.action })
    }

    @Test
    fun `row 2 holds the everyday Markdown symbols unshifted`() {
        // ">" and "|" are on the unshifted side even though a US keyboard shifts them: they are
        // blockquote and table syntax, typed constantly, while "<" and "\" barely appear.
        // The hyphen (= the chōonpu while composing romaji) and # stay at the right end.
        val row2Unshifted =
            KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[1].keys.map { key ->
                when (val action = key.action) {
                    is KeyAction.Char -> action.char
                    is KeyAction.ShiftPair -> action.base
                    else -> error("unexpected action on row 2: $action")
                }
            }
        assertEquals("()[]*`>|-#".toList(), row2Unshifted)
    }

    @Test
    fun `row 3 is bookended by shift and backspace on every layout`() {
        // Shift moved out of row 4 so that comma/period could flank the space key in
        // direct-alnum mode; Backspace moved out of row 1 so row 1 is exactly ten letter keys.
        // Both layers pin them to the row's edges (RowAlignment.UNIT_EDGES), which is what
        // keeps them at the same screen position even though the rows between differ in length.
        for (layout in ALL_LAYOUTS) {
            assertEquals("row 3 of $layout", KeyAction.Shift, layout.rows[2].keys.first().action)
            assertEquals("row 3 of $layout", KeyAction.Backspace, layout.rows[2].keys.last().action)
            assertEquals("row 3 of $layout", RowAlignment.UNIT_EDGES, layout.rows[2].alignment)
        }
    }

    @Test
    fun `rows 2 and 3 are sized against row 1's key width, not stretched`() {
        for (layout in ALL_LAYOUTS) {
            assertEquals("row 1 of $layout", RowAlignment.STRETCH, layout.rows[0].alignment)
            assertEquals("row 2 of $layout", RowAlignment.UNIT_CENTERED, layout.rows[1].alignment)
        }
    }

    @Test
    fun `row 1 is exactly the ten letter or digit keys`() {
        // Backspace used to be an eleventh key here; moving it to row 3 is what lets these ten
        // widen to a clean tenth of the keyboard, the unit every other row is sized against.
        assertEquals(
            "qwertyuiop".map { KeyAction.Char(it) },
            KeyboardLayouts.BASE_LAYOUT_ALNUM.rows[0].keys.map { it.action },
        )
        assertEquals("1234567890".toList(), KeyboardLayouts.SYMBOL_LAYOUT_ALNUM.rows[0].keys.map { it.label.single() })
    }

    @Test
    fun `function row order matches the spec left-to-right`() {
        assertEquals(
            listOf(
                KeyAction.LayerToggle,
                KeyAction.Char(','),
                KeyAction.Space,
                KeyAction.Char('.'),
                KeyAction.RomajiToggle,
                KeyAction.Enter,
            ),
            KeyboardLayouts.FUNCTION_ROW_ALNUM.keys.map { it.action },
        )
    }

    @Test
    fun `romaji function row swaps in kuten and touten flanking space`() {
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
        for (layout in ALL_LAYOUTS) {
            val ids = layout.rows.flatMap { it.keys.map { key -> key.id } }
            assertTrue("duplicate key ids in $layout: $ids", ids.size == ids.toSet().size)
        }
    }
}
