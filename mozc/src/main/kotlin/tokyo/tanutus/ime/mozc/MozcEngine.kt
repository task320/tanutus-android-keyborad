package tokyo.tanutus.ime.mozc

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
     *
     * Uses `AssetManager.open()` (a decompressing stream), not `openFd()`: AAPT compresses an
     * 18MB asset like this one by default, and `openFd()` — which hands back a raw, seekable
     * file descriptor into the APK — only works for entries stored uncompressed. Copying to a
     * `.tmp` file and renaming only after a full copy also makes this self-healing: a process
     * killed mid-copy leaves no file at [dataFile]'s path, so the next launch retries cleanly.
     */
    private fun extractDataFileIfNeeded(context: Context): File {
        val dataFile = File(context.filesDir, DATA_ASSET_NAME)
        if (dataFile.exists()) return dataFile

        val tempFile = File(context.filesDir, "$DATA_ASSET_NAME.tmp")
        context.assets.open(DATA_ASSET_NAME).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        check(tempFile.renameTo(dataFile)) { "Failed to move $tempFile to $dataFile" }
        return dataFile
    }
}
