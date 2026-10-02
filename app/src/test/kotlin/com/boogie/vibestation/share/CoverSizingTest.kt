package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for [CoverSizing]. */
class CoverSizingTest {

    /** Small files are sent as they are; big files or huge pictures are scaled. */
    @Test
    fun originalIsKeptOnlyWhenSmall() {
        assertTrue(CoverSizing.keepsOriginal(CoverSizing.ORIGINAL_LIMIT_BYTES, 4096, 3000))
        assertFalse(CoverSizing.keepsOriginal(CoverSizing.ORIGINAL_LIMIT_BYTES + 1, 1000, 1000))
        assertFalse(CoverSizing.keepsOriginal(100_000, 4097, 100))
    }

    /** The decoder shrinks by powers of two but never below the wanted size. */
    @Test
    fun sampleSizeStopsBeforeTooSmall() {
        assertEquals(1, CoverSizing.sampleSize(1000, 800, 2048))
        assertEquals(1, CoverSizing.sampleSize(4000, 3000, 2048))
        assertEquals(2, CoverSizing.sampleSize(4096, 3000, 2048))
        assertEquals(4, CoverSizing.sampleSize(8192, 6000, 2048))
        assertEquals(4, CoverSizing.sampleSize(3000, 9000, 2048))
    }

    /** Scaling keeps the aspect ratio and never enlarges. */
    @Test
    fun fitKeepsAspectRatio() {
        assertEquals(1000 to 800, CoverSizing.fit(1000, 800, 2048))
        assertEquals(2048 to 1536, CoverSizing.fit(4096, 3072, 2048))
        assertEquals(1024 to 2048, CoverSizing.fit(2000, 4000, 2048))
        assertEquals(1 to 2048, CoverSizing.fit(1, 100_000, 2048))
    }
}
