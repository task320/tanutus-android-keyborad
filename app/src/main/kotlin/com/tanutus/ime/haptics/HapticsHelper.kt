package com.tanutus.ime.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Fires a short tick — and only a short tick — for state-change moments (layer toggle, shift
 * lock, romaji-mode toggle, zenkaku toggle), per docs/keyboard-spec.md. Callers gate this on
 * [com.tanutus.ime.core.state.Transition.hapticFeedback]; regular character keys never call it.
 */
class HapticsHelper(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun performStateChangeTick() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(TICK_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private companion object {
        const val TICK_DURATION_MS = 20L
    }
}
