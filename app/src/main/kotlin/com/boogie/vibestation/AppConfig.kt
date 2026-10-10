package com.boogie.vibestation

/**
 * Application-wide configuration constants and release identifiers.
 */
object AppConfig {

    /**
     * Release version string displayed across UI headers. Sourced from `versionName` in
     * `app/build.gradle.kts` so the UI and the APK can never drift; never hardcode it here.
     */
    val APP_VERSION: String = BuildConfig.VERSION_NAME
}
