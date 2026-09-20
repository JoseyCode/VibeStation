package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the advertised identity and the connection tie-break. */
class ShareHelloTest {

    private fun hello(nonce: String, name: String = "Pixel") = ShareHello(21, nonce, name)

    /** An encoded hello reads back unchanged. */
    @Test
    fun roundTrip() {
        val original = hello("0a1b2c3d", "Josey's Flip")
        assertEquals(original, ShareHello.decode(original.encode()))
    }

    /** The separator and control characters in a device name cannot corrupt the fields. */
    @Test
    fun deviceNameIsCleaned() {
        val decoded = ShareHello.decode(hello("0a1b2c3d", " a|b\nc ").encode())
        assertEquals("abc", decoded?.deviceName)
        assertEquals(30, ShareHello.decode(hello("0a1b2c3d", "n".repeat(99)).encode())?.deviceName?.length)
    }

    /** Anything that is not a well-formed VibeStation advertisement is ignored, never a crash. */
    @Test
    fun foreignAdvertisementsAreRejected() {
        val bad = listOf(
            "", "Bob's Phone", "VS1|21|0a1b2c3d", "VS2|21|0a1b2c3d|x", "VS1|abc|0a1b2c3d|x",
            "VS1|21|ZZZZZZZZ|x", "VS1|21|0a1b2c3|x", "VS1|21|0A1B2C3D|x"
        )
        for (raw in bad) assertNull(ShareHello.decode(raw), raw)
    }

    /** Exactly one of two phones with different nonces requests the connection. */
    @Test
    fun exactlyOneSideRequests() {
        val low = hello("00000001")
        val high = hello("ffffffff")
        assertTrue(low.shouldRequestTo(high))
        assertFalse(high.shouldRequestTo(low))
    }

    /** Created nonces are 8 hex digits and come from the supplied random source. */
    @Test
    fun createUsesRandomNonce() {
        val random = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.indices.forEach { bytes[it] = (it + 10).toByte() }
            }
        }
        val created = ShareHello.create(21, "Pixel", random)
        assertEquals("0a0b0c0d", created.nonce)
        assertEquals(21, created.versionCode)
    }
}
