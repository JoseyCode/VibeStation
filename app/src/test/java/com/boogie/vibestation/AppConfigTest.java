package com.boogie.vibestation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit contract tests verifying AppConfig version formatting and presence.
 */
public class AppConfigTest {

    /**
     * Verifies that the APP_VERSION constant follows standard semver format.
     */
    @Test
    public void testAppVersionFormat() {
        assertNotNull(AppConfig.APP_VERSION);
        assertTrue("APP_VERSION should follow semantic versioning",
                AppConfig.APP_VERSION.matches("^\\d+\\.\\d+\\.\\d+$"));
        assertEquals("2.5.3", AppConfig.APP_VERSION);
    }
}
