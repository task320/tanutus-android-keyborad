package com.tanutus.ime.core.layout

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
 * [FUNCTION_ROW] is a single shared instance referenced by both [BASE_LAYOUT] and
 * [SYMBOL_LAYOUT] so that "row 4 never moves when switching layers" is a structural
 * (data-identity) guarantee rather than something that merely happens to look right.
 */
object KeyboardLayouts {
    private fun charKey(id: String, char: Char): KeyDef = KeyDef(id, char.toString(), KeyAction.Char(char))

    val FUNCTION_ROW: KeyRow =
        KeyRow(
            listOf(
                KeyDef("shift", "Shift", KeyAction.Shift),
                KeyDef("layer_toggle", "#12", KeyAction.LayerToggle),
                KeyDef("space", " ", KeyAction.Space, widthWeight = 3f),
                KeyDef("romaji_toggle", "A/あ", KeyAction.RomajiToggle),
                KeyDef("enter", "Enter", KeyAction.Enter),
            ),
        )

    private val BACKSPACE_KEY = KeyDef("backspace", "⌫", KeyAction.Backspace)

    val BASE_LAYOUT: KeyboardLayout =
        KeyboardLayout(
            layer = Layer.BASE,
            rows =
                listOf(
                    KeyRow(
                        "qwertyuiop".map { charKey("key_$it", it) } + BACKSPACE_KEY,
                    ),
                    KeyRow("asdfghjkl".map { charKey("key_$it", it) }),
                    KeyRow("zxcvbnm".map { charKey("key_$it", it) }),
                    FUNCTION_ROW,
                ),
        )

    val SYMBOL_LAYOUT: KeyboardLayout =
        KeyboardLayout(
            layer = Layer.SYMBOL,
            rows =
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
                    FUNCTION_ROW,
                ),
        )

    fun layoutFor(layer: Layer): KeyboardLayout =
        when (layer) {
            Layer.BASE -> BASE_LAYOUT
            Layer.SYMBOL -> SYMBOL_LAYOUT
        }
}
