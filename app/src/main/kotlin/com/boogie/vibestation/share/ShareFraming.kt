package com.boogie.vibestation.share

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** One decoded unit of the byte-level wire format. */
internal sealed interface ShareFrame {
    /** A slice of a control message; [last] marks the final slice. */
    class Chunk(val last: Boolean, val data: ByteArray) : ShareFrame

    /** Announces that the file payload [payloadId] carries the track at manifest [index]. */
    data class FileHeader(val index: Int, val payloadId: Long) : ShareFrame
}

/**
 * The byte-level wire format on top of Nearby's payloads. Nearby caps a bytes payload at 32 KB, but a
 * manifest with a cover can be megabytes, so a message is cut into numbered-by-order chunks (bytes
 * payloads arrive in the order they were sent). A file payload arrives anonymous and possibly before or
 * after its header, so the header names it by payload id.
 */
internal object ShareFraming {

    /** Largest chunk of message data per payload, leaving room for the flag byte under Nearby's cap. */
    const val MAX_CHUNK = 30_000

    /** Largest message [FrameAssembler] will accept. */
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    private const val KIND_LAST = 0
    private const val KIND_MORE = 1
    private const val KIND_FILE = 2
    private const val HEADER_SIZE = 1 + Int.SIZE_BYTES + Long.SIZE_BYTES

    /**
     * Cuts a control message into payload-sized frames.
     *
     * @param text Message text.
     * @return One or more frames to send in order; the last one carries the end flag.
     */
    fun messageFrames(text: String): List<ByteArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val starts = if (bytes.isEmpty()) listOf(0) else (bytes.indices step MAX_CHUNK).toList()
        return starts.mapIndexed { i, start ->
            val kind = if (i == starts.lastIndex) KIND_LAST else KIND_MORE
            byteArrayOf(kind.toByte()) + bytes.copyOfRange(start, minOf(start + MAX_CHUNK, bytes.size))
        }
    }

    /**
     * Builds the header that names a file payload.
     *
     * @param index     Track position in the manifest.
     * @param payloadId Nearby payload id of the file.
     * @return The frame bytes.
     */
    fun fileHeader(index: Int, payloadId: Long): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(KIND_FILE.toByte()).putInt(index).putLong(payloadId).array()

    /**
     * Decodes a frame from the peer.
     *
     * @param bytes Payload bytes as received.
     * @return The frame.
     * @throws IllegalArgumentException If the bytes are empty, of an unknown kind, oversized, or malformed.
     */
    fun parse(bytes: ByteArray): ShareFrame {
        require(bytes.isNotEmpty()) { "Empty frame" }
        return when (bytes[0].toInt()) {
            KIND_LAST, KIND_MORE -> {
                require(bytes.size - 1 <= MAX_CHUNK) { "Chunk too large" }
                ShareFrame.Chunk(bytes[0].toInt() == KIND_LAST, bytes.copyOfRange(1, bytes.size))
            }
            KIND_FILE -> {
                require(bytes.size == HEADER_SIZE) { "Bad file header" }
                val buffer = ByteBuffer.wrap(bytes, 1, HEADER_SIZE - 1)
                val index = buffer.getInt()
                require(index >= 0) { "Negative index" }
                ShareFrame.FileHeader(index, buffer.getLong())
            }
            else -> throw IllegalArgumentException("Unknown frame kind")
        }
    }
}

/**
 * Puts chunks back together into a message, one assembler per peer.
 *
 * @param maxBytes Most bytes one message may total before it is rejected.
 */
internal class FrameAssembler(private val maxBytes: Int = ShareFraming.MAX_MESSAGE_BYTES) {
    private val buffer = ByteArrayOutputStream()

    /**
     * Adds a chunk.
     *
     * @param chunk The next chunk, in arrival order.
     * @return The complete message text once the last chunk arrives, otherwise null.
     * @throws IllegalArgumentException If the message would exceed the limit; the partial message is discarded.
     */
    fun add(chunk: ShareFrame.Chunk): String? {
        if (buffer.size().toLong() + chunk.data.size > maxBytes) {
            buffer.reset()
            throw IllegalArgumentException("Message too large")
        }
        buffer.write(chunk.data)
        if (!chunk.last) return null
        val text = String(buffer.toByteArray(), Charsets.UTF_8)
        buffer.reset()
        return text
    }
}
