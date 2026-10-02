package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareFramingTest {

    private fun roundTrip(text: String): String? {
        val assembler = FrameAssembler()
        var result: String? = null
        ShareFraming.messageFrames(text).forEach { result = assembler.add(ShareFraming.parse(it) as ShareFrame.Chunk) }
        return result
    }

    @Test
    fun shortMessageIsOneLastFrame() {
        val frames = ShareFraming.messageFrames("hello")
        assertEquals(1, frames.size)
        val chunk = assertIs<ShareFrame.Chunk>(ShareFraming.parse(frames[0]))
        assertTrue(chunk.last)
        assertEquals("hello", String(chunk.data))
    }

    @Test
    fun longMessageSplitsIntoFullChunksThenRemainder() {
        val frames = ShareFraming.messageFrames("a".repeat(70_000))
        assertEquals(3, frames.size)
        val chunks = frames.map { assertIs<ShareFrame.Chunk>(ShareFraming.parse(it)) }
        assertEquals(listOf(false, false, true), chunks.map { it.last })
        assertEquals(listOf(30_000, 30_000, 10_000), chunks.map { it.data.size })
    }

    @Test
    fun everyFrameFitsUnderNearbyLimit() {
        ShareFraming.messageFrames("é".repeat(100_000)).forEach { assertTrue(it.size <= 32 * 1024) }
    }

    @Test
    fun exactMultipleOfChunkSizeHasNoEmptyTrailingFrame() {
        val frames = ShareFraming.messageFrames("a".repeat(60_000))
        assertEquals(2, frames.size)
        assertEquals(30_000, ShareFraming.parse(frames[1]).let { (it as ShareFrame.Chunk).data.size })
    }

    @Test
    fun emptyMessageStillProducesALastFrame() {
        val frames = ShareFraming.messageFrames("")
        assertEquals(1, frames.size)
        assertEquals("", roundTrip(""))
    }

    @Test
    fun multibyteCharactersSurviveSplitAcrossChunks() {
        // 3-byte characters: 30_000 is a multiple of 3, so offset by one ASCII byte to force a mid-character cut.
        val text = "x" + "€".repeat(40_000) + "🎵".repeat(5_000)
        assertEquals(text, roundTrip(text))
    }

    @Test
    fun fileHeaderRoundTrips() {
        val frame = ShareFraming.parse(ShareFraming.fileHeader(7, -1234567890123L))
        assertEquals(ShareFrame.FileHeader(7, -1234567890123L), frame)
    }

    @Test
    fun malformedFramesAreRejected() {
        assertFailsWith<IllegalArgumentException> { ShareFraming.parse(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { ShareFraming.parse(byteArrayOf(9, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { ShareFraming.parse(byteArrayOf(2, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { ShareFraming.parse(ByteArray(30_002)) }
        assertFailsWith<IllegalArgumentException> { ShareFraming.parse(ShareFraming.fileHeader(-1, 5)) }
    }

    @Test
    fun maximumChunkIsAccepted() {
        assertIs<ShareFrame.Chunk>(ShareFraming.parse(ByteArray(30_001)))
    }

    @Test
    fun assemblerReturnsNullUntilLastChunk() {
        val assembler = FrameAssembler()
        assertNull(assembler.add(ShareFrame.Chunk(false, "ab".toByteArray())))
        assertEquals("abcd", assembler.add(ShareFrame.Chunk(true, "cd".toByteArray())))
    }

    @Test
    fun assemblerStartsFreshAfterAMessage() {
        val assembler = FrameAssembler()
        assembler.add(ShareFrame.Chunk(true, "one".toByteArray()))
        assertEquals("two", assembler.add(ShareFrame.Chunk(true, "two".toByteArray())))
    }

    @Test
    fun assemblerRejectsOversizeAndForgetsThePartialMessage() {
        val assembler = FrameAssembler(maxBytes = 10)
        assembler.add(ShareFrame.Chunk(false, ByteArray(6)))
        assertFailsWith<IllegalArgumentException> { assembler.add(ShareFrame.Chunk(false, ByteArray(5))) }
        assertEquals("ok", assembler.add(ShareFrame.Chunk(true, "ok".toByteArray())))
    }

    @Test
    fun assemblerAcceptsExactlyTheLimit() {
        val assembler = FrameAssembler(maxBytes = 10)
        assertEquals(10, assembler.add(ShareFrame.Chunk(true, ByteArray(10) { 'a'.code.toByte() }))?.length)
    }

    @Test
    fun chunkContentIsPreserved() {
        val data = ByteArray(300) { it.toByte() }
        val frame = ShareFraming.parse(byteArrayOf(0) + data) as ShareFrame.Chunk
        assertContentEquals(data, frame.data)
    }
}
