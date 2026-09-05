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

/**
 * [centered]: when true, this row's keys are drawn at the *same* width as row 0's keys
 * (rather than stretching to fill the row, as every other row does) and the row is horizontally
 * centered, leaving equal empty margins on both sides. See [KeyboardLayouts.BASE_ROWS_PREFIX]'s
 * z-row for why.
 */
data class KeyRow(val keys: List<KeyDef>, val centered: Boolean = false)

data class KeyboardLayout(val layer: Layer, val rows: List<KeyRow>)

/**
 * Static layer definitions matching docs/keyboard-spec.md.
 *
 * Shift lives at the left end of row 3 rather than in row 4, which frees the two slots
 * flanking the space key for punctuation in *both* input modes — direct-alnum had no comma
 * or period at all before. Row 3 is where Shift sits on a physical QWERTY anyway, and keeping
 * it out of the mode-dependent row 4 is also what makes it reachable while composing romaji,
 * where layer 2's [KeyAction.ShiftPair] keys (`_`, `"`) genuinely need it.
 *
 * Row 4 has two variants selected by [InputMode] (see [FUNCTION_ROW_ALNUM] /
 * [FUNCTION_ROW_ROMAJI]). They are structurally identical now — same key count, same actions
 * in the same order — and differ only in which punctuation glyphs flank the space key
 * (`,`/`.` vs `。`/`、`). Each variant is a single shared instance referenced from both
 * [Layer.BASE] and [Layer.SYMBOL] layouts, so that "row 4 never moves when switching *layers*"
 * stays a structural (data-identity) guarantee for a given input mode.
 */
object KeyboardLayouts {
    private fun charKey(id: String, char: Char): KeyDef = KeyDef(id, char.toString(), KeyAction.Char(char))

    /**
     * One shared definition used by row 3 of both layers. Note this is *not* the positional
     * guarantee row 4 and Backspace carry: layer 2's row 3 holds one more key than layer 1's,
     * so Shift does shift sideways when the layer toggles.
     */
    private val SHIFT_KEY = KeyDef("shift", "Shift", KeyAction.Shift)

    /**
     * The label here is only the fallback for "no field focused yet": what actually gets drawn
     * is the focused field's IME action label ("検索", "送信", …), which is Android-specific and
     * so resolved outside this module — see com.tanutus.ime.editor.EditorInfoActionMapper and
     * KeyboardView.render's `enterLabel`.
     */
    private val ENTER_KEY = KeyDef("enter", "⏎", KeyAction.Enter)

    /**
     * Comma and period flank the space key, mirroring the 句点/読点 of [FUNCTION_ROW_ROMAJI].
     * They are plain [KeyAction.Char]s, not [KeyAction.Punctuation]: in direct-alnum mode there
     * is never a composition to join, and going through the ordinary character path is what
     * gets them converted to `，`/`．` when the zenkaku toggle is on (see
     * [com.tanutus.ime.TanutusImeService.commitLiteralChar]).
     */
    val FUNCTION_ROW_ALNUM: KeyRow =
        KeyRow(
            listOf(
                KeyDef("layer_toggle", "#12", KeyAction.LayerToggle),
                KeyDef("key_comma", ",", KeyAction.Char(',')),
                KeyDef("space", " ", KeyAction.Space, widthWeight = 3f),
                KeyDef("key_period", ".", KeyAction.Char('.')),
                KeyDef("romaji_toggle", "A/あ", KeyAction.RomajiToggle),
                ENTER_KEY,
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
                ENTER_KEY,
            ),
        )

    private val BACKSPACE_KEY = KeyDef("backspace", "⌫", KeyAction.Backspace)

    private val BASE_ROWS_PREFIX: List<KeyRow> =
        listOf(
            KeyRow(
                "qwertyuiop".map { charKey("key_$it", it) } + BACKSPACE_KEY,
            ),
            KeyRow("asdfghjkl".map { charKey("key_$it", it) }, centered = true),
            KeyRow(listOf(SHIFT_KEY) + "zxcvbnm".map { charKey("key_$it", it) }, centered = true),
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
                    SHIFT_KEY,
                    charKey("key_hash", '#'),
                    charKey("key_asterisk", '*'),
                    charKey("key_plus", '+'),
                    charKey("key_lt", '<'),
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
