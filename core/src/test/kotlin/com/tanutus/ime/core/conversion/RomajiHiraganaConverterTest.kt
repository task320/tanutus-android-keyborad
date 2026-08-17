package com.tanutus.ime.core.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RomajiHiraganaConverterTest {
    private lateinit var converter: RomajiHiraganaConverter

    @Before
    fun setUp() {
        converter = RomajiHiraganaConverter()
    }

    private fun type(text: String): Composition {
        var last = converter.currentComposition()
        for (char in text) last = converter.input(char)
        return last
    }

    @Test
    fun `single vowel resolves as typed`() {
        assertEquals("あ", type("a").text)
    }

    @Test
    fun `consonant plus vowel resolves as typed`() {
        assertEquals("か", type("ka").text)
    }

    @Test
    fun `youon resolves on longest match`() {
        assertEquals("きょ", type("kyo").text)
    }

    @Test
    fun `sha shu sho use sh digraph`() {
        assertEquals("しゃ", type("sha").text)
    }

    @Test
    fun `dakuon resolves`() {
        assertEquals("が", type("ga").text)
    }

    @Test
    fun `handakuon resolves`() {
        assertEquals("ぱ", type("pa").text)
    }

    @Test
    fun `doubled consonant produces sokuon`() {
        // "kitte" -> き + っ + て
        assertEquals("きって", type("kitte").text)
    }

    @Test
    fun `doubled consonant across a digraph`() {
        // "kicchi" -> き + っ + ち
        assertEquals("きっち", type("kicchi").text)
    }

    @Test
    fun `nn resolves to n mid-word`() {
        // "konna" -> こんな (n followed by a consonant resolves early)
        assertEquals("こんな", type("konna").text)
    }

    @Test
    fun `n followed by vowel resolves as a table entry not as n`() {
        // "na" must resolve to な, not ん + literal "a"
        assertEquals("な", type("na").text)
    }

    @Test
    fun `trailing bare n stays pending until commit`() {
        type("ho")
        val pending = converter.input('n')
        // Still pending: a trailing single "n" is a valid prefix (na, ni, ...), so it waits.
        assertEquals("ほn", pending.text)
        assertTrue(converter.hasActiveComposition())

        val committed = converter.commit()
        assertEquals("ほん", committed)
        assertFalse(converter.hasActiveComposition())
    }

    @Test
    fun `commit flushes a full word and resets state`() {
        type("konnichiha")
        val committed = converter.commit()
        assertEquals("こんにちは", committed)
        assertFalse(converter.hasActiveComposition())
        assertEquals("", converter.currentComposition().text)
    }

    @Test
    fun `dropLast undoes a whole youon segment at once`() {
        type("kyo")
        val afterDrop = converter.dropLast()
        assertEquals("", afterDrop.text)
        assertFalse(converter.hasActiveComposition())
    }

    @Test
    fun `dropLast on pending buffer removes one raw character`() {
        converter.input('k')
        val afterDrop = converter.dropLast()
        assertEquals("", afterDrop.text)
        assertFalse(converter.hasActiveComposition())
    }

    @Test
    fun `unrecognized input falls back to literal passthrough`() {
        // "q" is not part of the placeholder table and not a prefix of any entry.
        assertEquals("q", type("q").text)
    }

    @Test
    fun `reset clears all state`() {
        type("konnichiha")
        converter.reset()
        assertFalse(converter.hasActiveComposition())
        assertEquals("", converter.currentComposition().text)
    }
}
