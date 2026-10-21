package com.boogie.vibestation.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for the track identity key shared with the sync server. */
class MatchKeyTest {

    /** Case, whitespace and punctuation differences must not change a track's key. */
    @Test
    fun ignoresCaseWhitespaceAndPunctuation() {
        assertEquals("getlucky_daftpunk", MatchKey.of("  Get Lucky! ", "Daft Punk"))
        assertEquals(MatchKey.of("Get Lucky", "Daft Punk"), MatchKey.of("get   lucky", "DAFT PUNK"))
    }

    /** Letters outside ASCII stay in the key, so accented titles still match. */
    @Test
    fun keepsUnicodeLettersAndDigits() {
        assertEquals("beyoncé_halo2", MatchKey.of("Beyoncé", "Halo 2"))
    }

    /** Fully blank metadata maps to one shared placeholder, as on the server. */
    @Test
    fun blankMetadataUsesPlaceholder() {
        assertEquals("unknown_track", MatchKey.of(" ", ""))
    }
}
