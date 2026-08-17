package com.tanutus.ime.core.conversion

/**
 * ASCII <-> fullwidth (zenkaku) conversion via the standard Unicode offset
 * (U+0021..U+007E <-> U+FF01..U+FF5E), with the space character special-cased
 * to the IDEOGRAPHIC SPACE (U+3000) since it falls outside that offset range.
 *
 * Applies to direct-alphanumeric-mode input and layer 2's digits/symbols, per
 * docs/keyboard-spec.md. Kana/kanji text is already full-width and never
 * passes through this converter.
 */
object ZenkakuHankakuConverter {
    private const val ASCII_PRINTABLE_START = 0x21
    private const val ASCII_PRINTABLE_END = 0x7E
    private const val FULLWIDTH_OFFSET = 0xFEE0
    private const val HALFWIDTH_SPACE = ' '
    private const val FULLWIDTH_SPACE = '　'

    fun toZenkaku(input: String): String = input.map { toZenkaku(it) }.joinToString("")

    fun toZenkaku(char: Char): Char =
        when {
            char == HALFWIDTH_SPACE -> FULLWIDTH_SPACE
            char.code in ASCII_PRINTABLE_START..ASCII_PRINTABLE_END -> char + FULLWIDTH_OFFSET
            else -> char
        }

    fun toHankaku(input: String): String = input.map { toHankaku(it) }.joinToString("")

    fun toHankaku(char: Char): Char =
        when {
            char == FULLWIDTH_SPACE -> HALFWIDTH_SPACE
            char.code - FULLWIDTH_OFFSET in ASCII_PRINTABLE_START..ASCII_PRINTABLE_END -> char - FULLWIDTH_OFFSET
            else -> char
        }
}
