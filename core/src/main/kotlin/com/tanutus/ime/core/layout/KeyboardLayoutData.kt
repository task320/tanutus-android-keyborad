package com.tanutus.ime.core.layout

import com.tanutus.ime.core.state.InputMode

/** The two toggleable layers described in docs/keyboard-spec.md. */
enum class Layer {
    BASE,
    SYMBOL,
}

/** What a key does when tapped. Purely descriptive — no Android types here. */
sealed interface KeyAction {
    data class Char(val char: kotlin.Char) : KeyAction

    /** A key whose unshifted/shifted glyphs are two distinct characters (e.g. `-`/`_`, `'`/`"`). */
    data class ShiftPair(val base: kotlin.Char, val shifted: kotlin.Char) : KeyAction

    /**
     * A punctuation mark that always commits directly (ending any active romaji composition
     * first), rather than being fed into [com.tanutus.ime.core.conversion.KanaConverter] like
     * [Char] is in romaji mode — see [com.tanutus.ime.TanutusImeService.onPunctuationKey].
     */
    data class Punctuation(val char: kotlin.Char) : KeyAction

    data object Backspace : KeyAction

    data object Shift : KeyAction

    data object LayerToggle : KeyAction

    data object Space : KeyAction

    data object RomajiToggle : KeyAction

    data object Enter : KeyAction
}

data class KeyDef(
    val id: String,
    val label: String,
    val action: KeyAction,
    val widthWeight: Float = 1f,
)

data class KeyRow(val keys: List<KeyDef>)

data class KeyboardLayout(val layer: Layer, val rows: List<KeyRow>)

/**
 * Static layer definitions matching docs/keyboard-spec.md.
 *
 * Row 4 has two variants selected by [InputMode] (see [FUNCTION_ROW_ALNUM] /
 * [FUNCTION_ROW_ROMAJI]): Shift is only meaningful in direct-alnum input, where it actually
 * changes the committed character (romaji composition ignores it — kana has no case, see
 * [com.tanutus.ime.TanutusImeService.onKeyChar]), so it's hidden entirely during romaji
 * composition rather than shown as a dead key. Each variant is a single shared instance
 * referenced from both [Layer.BASE] and [Layer.SYMBOL] layouts, so that "row 4 never moves
 * when switching *layers*" stays a structural (data-identity) guarantee for a given input
 * mode, even though it now also varies across input modes.
 */
object KeyboardLayouts {
    private fun charKey(id: String, char: Char): KeyDef = KeyDef(id, char.toString(), KeyAction.Char(char))

    val FUNCTION_ROW_ALNUM: KeyRow =
        KeyRow(
            listOf(
                KeyDef("shift", "Shift", KeyAction.Shift),
                KeyDef("layer_toggle", "#12", KeyAction.LayerToggle),
                KeyDef("space", " ", KeyAction.Space, widthWeight = 3f),
                KeyDef("romaji_toggle", "A/あ", KeyAction.RomajiToggle),
                KeyDef("enter", "Enter", KeyAction.Enter),
            ),
        )

    /**
     * 句点/読点 flank the space key (kuten to its left, touten to its right) rather than
     * living on a letter row, since they're only needed while composing Japanese — i.e.
     * exactly when this romaji-mode row (as opposed to [FUNCTION_ROW_ALNUM]) is shown.
     */
    val FUNCTION_ROW_ROMAJI: KeyRow =
        KeyRow(
            listOf(
                KeyDef("layer_toggle", "#12", KeyAction.LayerToggle),
                KeyDef("kuten", "。", KeyAction.Punctuation('。')),
                KeyDef("space", " ", KeyAction.Space, widthWeight = 3f),
                KeyDef("touten", "、", KeyAction.Punctuation('、')),
                KeyDef("romaji_toggle", "A/あ", KeyAction.RomajiToggle),
                KeyDef("enter", "Enter", KeyAction.Enter),
            ),
        )

    private val BACKSPACE_KEY = KeyDef("backspace", "⌫", KeyAction.Backspace)

    private val BASE_ROWS_PREFIX: List<KeyRow> =
        listOf(
            KeyRow(
                "qwertyuiop".map { charKey("key_$it", it) } + BACKSPACE_KEY,
            ),
            KeyRow("asdfghjkl".map { charKey("key_$it", it) }),
            KeyRow("zxcvbnm".map { charKey("key_$it", it) }),
        )

    private val SYMBOL_ROWS_PREFIX: List<KeyRow> =
        listOf(
            KeyRow(
                "1234567890".map { charKey("key_$it", it) } + BACKSPACE_KEY,
            ),
            KeyRow(
                listOf(
                    charKey("key_paren_open", '('),
                    charKey("key_paren_close", ')'),
                    charKey("key_bracket_open", '['),
                    charKey("key_bracket_close", ']'),
                    charKey("key_brace_open", '{'),
                    charKey("key_brace_close", '}'),
                    KeyDef("key_hyphen", "-", KeyAction.ShiftPair('-', '_')),
                    charKey("key_slash", '/'),
                    charKey("key_colon", ':'),
                ),
            ),
            KeyRow(
                listOf(
                    charKey("key_hash", '#'),
                    charKey("key_asterisk", '*'),
                    charKey("key_plus", '+'),
                    charKey("key_gt", '>'),
                    charKey("key_backtick", '`'),
                    charKey("key_tilde", '~'),
                    KeyDef("key_quote", "'", KeyAction.ShiftPair('\'', '"')),
                ),
            ),
        )

    val BASE_LAYOUT_ALNUM: KeyboardLayout = KeyboardLayout(Layer.BASE, BASE_ROWS_PREFIX + FUNCTION_ROW_ALNUM)
    val BASE_LAYOUT_ROMAJI: KeyboardLayout = KeyboardLayout(Layer.BASE, BASE_ROWS_PREFIX + FUNCTION_ROW_ROMAJI)
    val SYMBOL_LAYOUT_ALNUM: KeyboardLayout = KeyboardLayout(Layer.SYMBOL, SYMBOL_ROWS_PREFIX + FUNCTION_ROW_ALNUM)
    val SYMBOL_LAYOUT_ROMAJI: KeyboardLayout = KeyboardLayout(Layer.SYMBOL, SYMBOL_ROWS_PREFIX + FUNCTION_ROW_ROMAJI)

    fun layoutFor(layer: Layer, inputMode: InputMode): KeyboardLayout =
        when (layer to inputMode) {
            Layer.BASE to InputMode.DIRECT_ALNUM -> BASE_LAYOUT_ALNUM
            Layer.BASE to InputMode.ROMAJI -> BASE_LAYOUT_ROMAJI
            Layer.SYMBOL to InputMode.DIRECT_ALNUM -> SYMBOL_LAYOUT_ALNUM
            else -> SYMBOL_LAYOUT_ROMAJI
        }
}
