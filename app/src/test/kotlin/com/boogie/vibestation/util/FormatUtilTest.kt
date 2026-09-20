package com.boogie.vibestation.util

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for FormatUtil clock and speed labels, pinning the minute/hour rollover boundaries and the
 * default-Locale behavior the player UI depends on.
 */
class FormatUtilTest {

    private lateinit var originalLocale: Locale

    /** Pins the default Locale so digit and separator expectations do not depend on the host machine. */
    @BeforeTest
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    /** Restores the default Locale so other tests are unaffected. */
    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /**
     * Verifies zero and sub-second positions render as 0:00 (fractions are truncated, not rounded).
     */
    @Test
    fun formatTimeTruncatesToWholeSeconds() {
        assertEquals("0:00", FormatUtil.formatTime(0))
        assertEquals("0:00", FormatUtil.formatTime(999))
        assertEquals("0:01", FormatUtil.formatTime(1999))
    }

    /**
     * Verifies seconds are zero-padded while minutes are not, and that 59s rolls to 1:00.
     */
    @Test
    fun formatTimePadsSecondsButNotMinutes() {
        assertEquals("0:09", FormatUtil.formatTime(9_000))
        assertEquals("0:59", FormatUtil.formatTime(59_000))
        assertEquals("1:00", FormatUtil.formatTime(60_000))
        assertEquals("3:05", FormatUtil.formatTime(185_000))
        assertEquals("59:59", FormatUtil.formatTime(3_599_000))
    }

    /**
     * Verifies positions of an hour or more switch to H:MM:SS with padded minutes.
     */
    @Test
    fun formatTimeShowsHoursFromOneHour() {
        assertEquals("1:00:00", FormatUtil.formatTime(3_600_000))
        assertEquals("1:01:01", FormatUtil.formatTime(3_661_000))
        assertEquals("2:30:45", FormatUtil.formatTime(9_045_000))
        assertEquals("10:00:00", FormatUtil.formatTime(36_000_000))
    }

    /**
     * Verifies formatSpeed always shows two decimals with an x suffix.
     */
    @Test
    fun formatSpeedShowsTwoDecimals() {
        assertEquals("1.00x", FormatUtil.formatSpeed(1f))
        assertEquals("1.25x", FormatUtil.formatSpeed(1.25f))
        assertEquals("0.50x", FormatUtil.formatSpeed(0.5f))
        assertEquals("2.00x", FormatUtil.formatSpeed(2f))
    }

    /**
     * Verifies both formatters follow the default Locale's decimal separator, as the original
     * MainActivity code did (Locale.getDefault()).
     */
    @Test
    fun formattersFollowDefaultLocale() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("1,25x", FormatUtil.formatSpeed(1.25f))
        assertEquals("1:05", FormatUtil.formatTime(65_000))
    }
}
