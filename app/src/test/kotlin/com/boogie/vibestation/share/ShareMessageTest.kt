package com.boogie.vibestation.share

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests for [ShareMessage] serialization and validation of peer input. */
class ShareMessageTest {

    private val track = ShareTrack("One More Time", "Daft Punk", "Discovery", 320_000, 9, "a.mp3", "audio/mpeg")
    private val manifest = ShareManifest(ShareKind.ALBUM, "Discovery", "", "", listOf(track))

    /** Every message type survives a round trip. */
    @Test
    fun roundTrips() {
        val messages = listOf(
            ShareMessage.Offer(manifest),
            ShareMessage.Accept(listOf(0, 2, 5)),
            ShareMessage.Accept(emptyList()),
            ShareMessage.Decline(DeclineReason.NO_SPACE),
            ShareMessage.Done(3, 1)
        )
        for (message in messages) assertEquals(message, ShareMessage.parse(message.toJson()))
    }

    /** Garbage, unknown types, and invalid values are rejected with IllegalArgumentException. */
    @Test
    fun invalidMessagesAreRejected() {
        val cases = listOf(
            "nope",
            "{}",
            """{"type":"explode"}""",
            """{"type":"accept"}""",
            """{"type":"accept","indices":[1,-2]}""",
            """{"type":"accept","indices":"x"}""",
            """{"type":"decline","reason":"RUDE"}""",
            """{"type":"done","stored":-1,"failed":0}""",
            """{"type":"offer","manifest":"{}"}""",
            """{"type":"offer"}"""
        )
        for (case in cases) assertFailsWith<IllegalArgumentException>(case) { ShareMessage.parse(case) }
    }

    /** An index list larger than any real manifest is refused. */
    @Test
    fun tooManyIndicesRejected() {
        val indices = (0..ShareManifest.MAX_TRACKS).joinToString(",")
        assertFailsWith<IllegalArgumentException> { ShareMessage.parse("""{"type":"accept","indices":[$indices]}""") }
    }

    /** An oversized unknown type name is not echoed back in full. */
    @Test
    fun unknownTypeMessageIsBounded() {
        val error = assertFailsWith<IllegalArgumentException> {
            ShareMessage.parse(JSONObject().put("type", "x".repeat(5000)).toString())
        }
        assertTrue((error.message ?: "").length < 500)
    }
}
