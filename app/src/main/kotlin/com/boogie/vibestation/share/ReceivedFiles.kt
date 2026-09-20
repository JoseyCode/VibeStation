package com.boogie.vibestation.share

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Where incoming files wait between the radio finishing and the session storing them. Nearby hands over
 * an anonymous stream that must be drained as it arrives, so the bytes go to a private temp file first;
 * that keeps [ShareTransport] stream-based and avoids the unnamed copies Nearby's own file payloads leave
 * in the public Downloads folder.
 */
internal object ReceivedFiles {

    /** Start of every temp file name, so leftovers of a killed app can be found and removed. */
    const val PREFIX = "share-"

    /** End of every temp file name. */
    const val SUFFIX = ".part"

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Drains [input] into a new temp file.
     *
     * @param input    Stream to read until its end; not closed here.
     * @param dir      Private directory for the temp file.
     * @param maxBytes Most bytes accepted; a longer stream is an error, since the peer is untrusted.
     * @return The temp file, which the caller must delete or hand to [openAndDelete].
     * @throws IOException If reading or writing fails or the stream is longer than [maxBytes]; no file is left.
     */
    fun drain(input: InputStream, dir: File, maxBytes: Long): File {
        val file = File.createTempFile(PREFIX, SUFFIX, dir)
        var succeeded = false
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("File too large")
                    output.write(buffer, 0, read)
                }
            }
            succeeded = true
            return file
        } finally {
            if (!succeeded) file.delete()
        }
    }

    /**
     * Opens [file] for reading and deletes it when the returned stream is closed.
     *
     * @param file A file made by [drain].
     * @return A stream over its bytes.
     * @throws IOException If the file cannot be opened; it is deleted in that case.
     */
    fun openAndDelete(file: File): InputStream {
        val stream = try {
            file.inputStream()
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.delete()
                }
            }
        }
    }
}
