package com.boogie.vibestation.share

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedFilesTest {
    private val dir = File.createTempFile("received-files", "").apply {
        delete()
        mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun drainCopiesEveryByte() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val file = ReceivedFiles.drain(ByteArrayInputStream(data), dir, 1_000_000)
        assertContentEquals(data, file.readBytes())
        assertEquals(dir, file.parentFile)
    }

    @Test
    fun drainAcceptsExactlyTheLimit() {
        val file = ReceivedFiles.drain(ByteArrayInputStream(ByteArray(100)), dir, 100)
        assertEquals(100, file.length())
    }

    @Test
    fun drainRejectsAStreamOverTheLimitAndLeavesNothing() {
        assertFailsWith<IOException> { ReceivedFiles.drain(ByteArrayInputStream(ByteArray(101)), dir, 100) }
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun drainLeavesNothingWhenTheStreamFails() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("radio dropped")
        }
        assertFailsWith<IOException> { ReceivedFiles.drain(broken, dir, 100) }
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun drainOfEmptyStreamMakesAnEmptyFile() {
        assertEquals(0, ReceivedFiles.drain(ByteArrayInputStream(ByteArray(0)), dir, 10).length())
    }

    @Test
    fun openAndDeleteRemovesTheFileOnClose() {
        val file = ReceivedFiles.drain(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir, 10)
        val stream = ReceivedFiles.openAndDelete(file)
        assertContentEquals(byteArrayOf(1, 2, 3), stream.readBytes())
        assertTrue(file.exists())
        stream.close()
        assertFalse(file.exists())
    }

    @Test
    fun openAndDeleteThatCannotOpenThrowsAndStillRemovesTheFile() {
        // A directory cannot be opened as a stream, which stands in for an unreadable temp file.
        val unreadable = File(dir, "unreadable").apply { mkdir() }
        assertFailsWith<IOException> { ReceivedFiles.openAndDelete(unreadable) }
        assertFalse(unreadable.exists())
    }
}
