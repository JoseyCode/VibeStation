package com.boogie.vibestation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit contract tests verifying AppConfig version formatting and presence.
 */
class AppConfigTest {

    /**
     * Verifies that the APP_VERSION constant follows standard semver format.
     */
    @Test
    fun appVersionFormat() {
        assertTrue(
            AppConfig.APP_VERSION.matches(Regex("""^\d+\.\d+\.\d+$""")),
            "APP_VERSION should follow semantic versioning"
        )
        assertEquals("3.0.0", AppConfig.APP_VERSION)
    }
}
