package com.google.android.apps.inputmethod.libs.mozc.session

/**
 * Raw JNI binding for `libmozc.so`'s `android/jni/mozcjni.cc`.
 *
 * The package and class name are load-bearing: `mozcjni.cc` exports a single fixed symbol,
 * `Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJNI_initialize`, which is how
 * the JVM resolves `initialize()` below (this is the historical package of Mozc's own deleted
 * Android client, kept only so the prebuilt native library's symbol name matches). Calling
 * [initialize] registers the other three methods (`evalCommand`, `onPostLoad`,
 * `getDataVersion`) onto this same class via `RegisterNatives`, so they must exist here even
 * though they aren't resolved through the `Java_...` naming convention.
 */
internal object MozcJNI {
    init {
        System.loadLibrary("mozc")
    }

    @JvmStatic external fun initialize(): Boolean

    @JvmStatic external fun onPostLoad(userProfileDirectoryPath: String, dataFilePath: String?): Boolean

    @JvmStatic external fun evalCommand(input: ByteArray): ByteArray

    @JvmStatic external fun getDataVersion(): String
}
