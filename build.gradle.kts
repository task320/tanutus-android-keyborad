// Root build file. Subprojects declare their own plugins independently
// via the version catalog (see gradle/libs.versions.toml) so that ":core"
// (pure Kotlin/JVM) can be configured and built without ever needing to
// resolve the Android Gradle Plugin, which is only published to Google's
// Maven repository and may not be reachable in every build environment.
