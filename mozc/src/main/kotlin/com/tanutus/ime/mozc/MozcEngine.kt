package com.tanutus.ime.mozc

import android.content.Context
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import java.io.File

/**
 * Process-wide handle to the native Mozc session handler. `libmozc.so` keeps a single global
 * [SessionHandler](../../../../../../../../docs/mozc-integration-feasibility.md) instance, so
 * [ensureLoaded] is idempotent and safe to call from every [MozcKanaConverter] instance.
 */
object MozcEngine {
    private const val DATA_ASSET_NAME = "mozc.data"

    @Volatile private var loaded = false

    /** Loads `libmozc.so` and initializes the session handler, if not already done. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return

        val dataFile = extractDataFileIfNeeded(context)
        val userProfileDir = File(context.filesDir, "mozc_profile").apply { mkdirs() }

        check(MozcJNI.initialize()) { "MozcJNI.initialize() failed to register native methods" }
        check(MozcJNI.onPostLoad(userProfileDir.absolutePath, dataFile.absolutePath)) {
            "MozcJNI.onPostLoad() failed to create the Mozc session handler"
        }
        loaded = true
    }

    /** Sends one [Input]/[Output] round trip. Only valid after [ensureLoaded]. */
    fun evalCommand(inputBytes: ByteArray): ByteArray {
        check(loaded) { "MozcEngine.ensureLoaded() must be called before evalCommand()" }
        return MozcJNI.evalCommand(inputBytes)
    }

    /**
     * `libmozc.so` reads its dictionary from a plain file path, not an asset stream, so the
     * bundled asset is copied to app-private storage once and reused on subsequent launches.
     */
    private fun extractDataFileIfNeeded(context: Context): File {
        val dataFile = File(context.filesDir, DATA_ASSET_NAME)
        val assetSize = context.assets.openFd(DATA_ASSET_NAME).use { it.length }
        if (dataFile.exists() && dataFile.length() == assetSize) {
            return dataFile
        }
        context.assets.open(DATA_ASSET_NAME).use { input ->
            dataFile.outputStream().use { output -> input.copyTo(output) }
        }
        return dataFile
    }
}
