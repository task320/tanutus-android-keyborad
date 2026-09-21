package tokyo.tanutus.ime.theme

import android.content.Context
import androidx.core.content.ContextCompat
import tokyo.tanutus.ime.R
import tokyo.tanutus.ime.core.layout.Layer

/** Resolved colors for one draw pass of [tokyo.tanutus.ime.view.KeyboardView]. */
data class KeyboardColors(
    val background: Int,
    val keyBackgroundNormal: Int,
    val keyBackgroundLocked: Int,
    val keyBackgroundShiftLocked: Int,
    val keyBackgroundShiftMomentary: Int,
    val keyOutlineZenkaku: Int,
    val keyTextNormal: Int,
    val keyTextLocked: Int,
)

/**
 * Resolves the per-layer background color and the shared normal/locked key colors described
 * in docs/keyboard-spec.md ("視覚的フィードバックの統一ルール"). The exact hex values in
 * colors.xml are placeholders the spec explicitly leaves open; this is the single place that
 * would need to change to retune them.
 */
object KeyboardThemeProvider {
    fun themeFor(layer: Layer, context: Context): KeyboardColors {
        val backgroundRes =
            when (layer) {
                Layer.BASE -> R.color.layer_base_background
                Layer.SYMBOL -> R.color.layer_symbol_background
            }
        return KeyboardColors(
            background = ContextCompat.getColor(context, backgroundRes),
            keyBackgroundNormal = ContextCompat.getColor(context, R.color.key_background_normal),
            keyBackgroundLocked = ContextCompat.getColor(context, R.color.key_background_locked),
            keyBackgroundShiftLocked = ContextCompat.getColor(context, R.color.key_background_shift_locked),
            keyBackgroundShiftMomentary = ContextCompat.getColor(context, R.color.key_background_shift_momentary),
            keyOutlineZenkaku = ContextCompat.getColor(context, R.color.key_outline_zenkaku),
            keyTextNormal = ContextCompat.getColor(context, R.color.key_text_normal),
            keyTextLocked = ContextCompat.getColor(context, R.color.key_text_locked),
        )
    }
}
