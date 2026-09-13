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

/** How a row's keys are sized and placed across the keyboard's width. */
enum class RowAlignment {
    /** Keys divide the full width in proportion to their weight (row 1, the function row). */
    STRETCH,

    /**
     * Keys are drawn at row 1's per-unit key width and the whole block is centered, leaving
     * equal empty margins on both sides — so a shorter row lines up with row 1's columns
     * instead of stretching its keys to fill the width.
     */
    UNIT_CENTERED,

    /**
     * Sized like [UNIT_CENTERED], but the first and last keys are pinned to the left and right
     * edges and only the keys between them are centered. This is what puts Shift and Backspace
     * at the same screen position on both layers even though layer 2's row 3 holds one more key
     * between them than layer 1's does.
     */
    UNIT_EDGES,
}

data class KeyRow(val keys: List<KeyDef>, val alignment: RowAlignment = RowAlignment.STRETCH)

data class KeyboardLayout(val layer: Layer, val rows: List<KeyRow>)

/**
 * Static layer definitions matching docs/keyboard-spec.md.
 *
 * Row 3 is bookended by Shift on the left and Backspace on the right with the letters (or
 * symbols) centered between them, the familiar phone-QWERTY shape. Moving Backspace out of
 * row 1 lets row 1 be exactly the ten letter keys, and every other row is then sized against
 * that one key width — see [RowAlignment].
 *
 * Shift living in row 3 rather than row 4 also frees the two slots flanking the space key for
 * punctuation in *both* input modes (direct-alnum had no comma or period at all before), and
 * keeps Shift reachable while composing romaji, where layer 2's [KeyAction.ShiftPair] keys
 * (`_`, `"`) genuinely need it.
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
     * Row 3's left-edge key on both layers. Layer 2's row 3 holds one more key than layer 1's,
     * so it is [RowAlignment.UNIT_EDGES] — not equal key counts — that keeps Shift from moving
     * when the layer toggles.
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
                KeyDef("romaji_toggle", "あ/A", KeyAction.RomajiToggle),
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
                KeyDef("romaji_toggle", "あ/A", KeyAction.RomajiToggle),
                ENTER_KEY,
            ),
        )

    /** Row 3's right-edge key on both layers — the mirror of [SHIFT_KEY]. */
    private val BACKSPACE_KEY = KeyDef("backspace", "⌫", KeyAction.Backspace)

    private val BASE_ROWS_PREFIX: List<KeyRow> =
        listOf(
            KeyRow("qwertyuiop".map { charKey("key_$it", it) }),
            KeyRow("asdfghjkl".map { charKey("key_$it", it) }, RowAlignment.UNIT_CENTERED),
            KeyRow(
                listOf(SHIFT_KEY) + "zxcvbnm".map { charKey("key_$it", it) } + BACKSPACE_KEY,
                RowAlignment.UNIT_EDGES,
            ),
        )

    private fun pairKey(id: String, base: Char, shifted: Char): KeyDef =
        KeyDef(id, base.toString(), KeyAction.ShiftPair(base, shifted))

    /**
     * Shifted glyphs for the digit row, index-aligned with "1234567890" — the US layout's own
     * top row. Reusing that mapping rather than inventing one means the shifted symbols are
     * already in the muscle memory of anyone who types on a physical keyboard.
     */
    private const val SHIFTED_DIGITS = "!@#\$%^&*()"

    /**
     * Layer 2 covers all 32 ASCII punctuation marks (bar `,` and `.`, which live on row 4) by
     * giving most keys a Shift partner, the same mechanism `-`/`_` already used. Pairings follow
     * the US layout wherever both characters exist on one of its keys, so nothing new has to be
     * memorised; the Markdown-frequent member of each pair is the unshifted one.
     *
     * That ordering is why two pairs are deliberately flipped relative to a US keyboard:
     * `>` (blockquote) and `|` (tables) are everyday Markdown while `<` and `\` are not, so they
     * take the unshifted side. Row 2 then holds exactly the ten symbols a Markdown document
     * uses most, reachable without Shift at all.
     */
    private val SYMBOL_ROWS_PREFIX: List<KeyRow> =
        listOf(
            KeyRow("1234567890".mapIndexed { i, digit -> pairKey("key_$digit", digit, SHIFTED_DIGITS[i]) }),
            KeyRow(
                listOf(
                    charKey("key_paren_open", '('),
                    charKey("key_paren_close", ')'),
                    pairKey("key_bracket_open", '[', '{'),
                    pairKey("key_bracket_close", ']', '}'),
                    charKey("key_asterisk", '*'),
                    pairKey("key_backtick", '`', '~'),
                    pairKey("key_gt", '>', '<'),
                    pairKey("key_pipe", '|', '\\'),
                    // The hyphen doubles as the chōonpu "ー" while composing romaji (see
                    // TanutusImeService.onShiftPairKey), and # is the Markdown heading key, so
                    // these two are the row's most-used keys when writing Japanese notes —
                    // hence the right end, nearest the thumb, with # outermost.
                    pairKey("key_hyphen", '-', '_'),
                    charKey("key_hash", '#'),
                ),
                RowAlignment.UNIT_CENTERED,
            ),
            KeyRow(
                listOf(
                    SHIFT_KEY,
                    pairKey("key_slash", '/', '?'),
                    pairKey("key_colon", ':', ';'),
                    pairKey("key_equals", '=', '+'),
                    pairKey("key_quote", '\'', '"'),
                    // Both also reachable as Shift+1 and Shift+7, but they earn their own keys:
                    // ! opens every image link and & every URL query, the same reasoning that
                    // keeps ( and ) on row 2 despite Shift+9/Shift+0.
                    charKey("key_bang", '!'),
                    charKey("key_amp", '&'),
                    charKey("key_at", '@'),
                    BACKSPACE_KEY,
                ),
                RowAlignment.UNIT_EDGES,
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
