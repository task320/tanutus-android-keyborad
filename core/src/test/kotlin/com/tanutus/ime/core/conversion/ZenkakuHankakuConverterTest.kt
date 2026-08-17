package com.tanutus.ime.core.conversion

import org.junit.Assert.assertEquals
import org.junit.Test

class ZenkakuHankakuConverterTest {
    @Test
    fun `ascii printable range round-trips through zenkaku and back`() {
        for (code in 0x21..0x7E) {
            val original = code.toChar()
            val zenkaku = ZenkakuHankakuConverter.toZenkaku(original)
            assertEquals(code + 0xFEE0, zenkaku.code)
            assertEquals(original, ZenkakuHankakuConverter.toHankaku(zenkaku))
        }
    }

    @Test
    fun `space maps to ideographic space and back`() {
        assertEquals('　', ZenkakuHankakuConverter.toZenkaku(' '))
        assertEquals(' ', ZenkakuHankakuConverter.toHankaku('　'))
    }

    @Test
    fun `digits and symbols convert as a string`() {
        assertEquals("１２３", ZenkakuHankakuConverter.toZenkaku("123"))
        assertEquals("123", ZenkakuHankakuConverter.toHankaku("１２３"))
        assertEquals("ＡＢＣ　１", ZenkakuHankakuConverter.toZenkaku("ABC 1"))
    }

    @Test
    fun `non-ascii kana passes through unchanged`() {
        assertEquals("こんにちは", ZenkakuHankakuConverter.toZenkaku("こんにちは"))
        assertEquals("こんにちは", ZenkakuHankakuConverter.toHankaku("こんにちは"))
    }
}
