package com.boogie.vibestation.share

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests for the MediaStore file-detail reader. Cursors and the resolver are mocked. */
class ShareFileTest {

    private fun rowCursor(vararg rows: List<Any?>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        val names = listOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION
        )
        var row = -1
        every { cursor.moveToNext() } answers { ++row < rows.size }
        every { cursor.getColumnIndexOrThrow(any()) } answers { names.indexOf(firstArg<String>()) }
        every { cursor.getString(any()) } answers { rows[row][firstArg()] as String? }
        every { cursor.getLong(any()) } answers { rows[row][firstArg()] as Long }
        return cursor
    }

    /** Columns map onto ShareFile fields, and a null text column becomes an empty string. */
    @Test
    fun mapsRow() {
        val cursor = rowCursor(listOf("7", "a.mp3", null, 1234L, 5000L))
        cursor.moveToNext()

        assertEquals(ShareFile("7", "a.mp3", "", 1234, 5000), shareFileFromCursor(cursor))
    }

    /** Ids are looked up in chunks below the SQLite variable limit and results are merged by id. */
    @Test
    fun queriesInChunks() {
        val resolver = mockk<ContentResolver>()
        val args = mutableListOf<Int>()
        val argSlot = slot<Array<String>>()
        every { resolver.query(any(), any(), any(), capture(argSlot), any()) } answers {
            args += argSlot.captured.size
            rowCursor(listOf(argSlot.captured[0], "f.mp3", "audio/mpeg", 1L, 2L))
        }

        val result = queryShareFiles(resolver, (1..501).map { it.toString() } + "1")

        assertEquals(listOf(500, 1), args)
        assertEquals(setOf("1", "501"), result.keys)
        verify(exactly = 2) { resolver.query(any(), any(), any(), any(), any()) }
    }
}
