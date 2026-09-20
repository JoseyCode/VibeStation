package com.boogie.vibestation.util

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Song
import io.mockk.clearStaticMockk
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for MediaMetadataUtil scan/notify coordination, album deletion, and error reporting.
 * The main-thread Handler runs posts inline and Toast/MediaScannerConnection are mocked. The jaudiotagger
 * tag-writing itself needs real audio files and is not covered.
 */
class MediaMetadataUtilTest {

    companion object {
        /** The object builds its main-thread Handler once at first use, so the mock must outlive every test. */
        @JvmStatic
        @BeforeClass
        fun mockMainThread() {
            mockkStatic(Looper::class, Toast::class, MediaScannerConnection::class)
            every { Looper.getMainLooper() } returns mockk()
            mockkConstructor(Handler::class)
            every { anyConstructed<Handler>().post(any()) } answers {
                firstArg<Runnable>().run()
                true
            }
        }

        @JvmStatic
        @AfterClass
        fun unmock() {
            unmockkAll()
        }
    }

    private val context = mockk<Context>()
    private val toasts = CopyOnWriteArrayList<String>()
    private val toastShown = CountDownLatch(1)
    private val scanned = CopyOnWriteArrayList<List<String>>()

    @BeforeTest
    fun setUp() {
        clearStaticMockk(Toast::class, MediaScannerConnection::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } answers {
            val text = secondArg<CharSequence>().toString()
            mockk<Toast>().also {
                every { it.show() } answers {
                    toasts += text
                    toastShown.countDown()
                }
            }
        }
        // Completes every scan immediately, like a fast MediaScanner.
        every { MediaScannerConnection.scanFile(any(), any(), any(), any()) } answers {
            val paths = secondArg<Array<String>>()
            scanned += paths.toList()
            val listener = lastArg<MediaScannerConnection.OnScanCompletedListener>()
            paths.forEach { listener.onScanCompleted(it, null) }
        }
    }

    private fun song(id: String, path: String?) = Song(id, id, "Artist", path, "alb", "Album", 1, 0L)

    private fun album(vararg songs: Song) = Album("alb", "Album", "Artist", 0L).apply { this.songs.addAll(songs) }

    private fun tempFile() = File.createTempFile("track", ".mp3").apply { deleteOnExit() }

    // scanFilesAndNotify

    /**
     * Verifies an empty path list skips the scanner and reports completion straight away.
     */
    @Test
    fun scanWithNoPathsNotifiesImmediately() {
        var completed = 0

        MediaMetadataUtil.scanFilesAndNotify(context, emptyArray(), "Nothing to scan") { completed++ }

        assertEquals(1, completed)
        assertEquals(listOf("Nothing to scan"), toasts)
        verify(exactly = 0) { MediaScannerConnection.scanFile(any(), any(), any(), any()) }
    }

    /**
     * Verifies completion fires exactly once, only after the last file reports back from the scanner.
     */
    @Test
    fun scanNotifiesOnlyAfterEveryFileIsScanned() {
        val listeners = mutableListOf<MediaScannerConnection.OnScanCompletedListener>()
        every { MediaScannerConnection.scanFile(any(), any(), any(), any()) } answers {
            listeners += lastArg<MediaScannerConnection.OnScanCompletedListener>()
        }
        var completed = 0

        MediaMetadataUtil.scanFilesAndNotify(context, arrayOf("/a.mp3", "/b.mp3", "/c.mp3"), "Done") { completed++ }
        val listener = listeners.single()

        listener.onScanCompleted("/a.mp3", null)
        listener.onScanCompleted("/b.mp3", null)
        assertEquals(0, completed)
        assertTrue(toasts.isEmpty())

        listener.onScanCompleted("/c.mp3", null)
        assertEquals(1, completed)
        assertEquals(listOf("Done"), toasts)
    }

    // deleteAlbum

    /**
     * Verifies files on disk are removed, absent files and pathless songs are tolerated, and a rescan
     * of the album's known paths follows before the success callback.
     */
    @Test
    fun deleteAlbumRemovesFilesAndRescans() {
        val present = tempFile()
        val alreadyGone = File("/definitely/not/here.mp3")
        val finished = CountDownLatch(1)
        val target = album(song("1", present.absolutePath), song("2", alreadyGone.path), song("3", null))

        MediaMetadataUtil.deleteAlbum(context, target) { finished.countDown() }

        assertTrue(finished.await(10, TimeUnit.SECONDS), "delete did not finish")
        assertFalse(present.exists())
        assertEquals(listOf(listOf(present.absolutePath, alreadyGone.path)), scanned)
        assertEquals(listOf("Album deleted"), toasts)
    }

    // error reporting

    private fun awaitToast(): String {
        assertTrue(toastShown.await(10, TimeUnit.SECONDS), "no toast was shown")
        return toasts.last()
    }

    /**
     * Verifies a song without a file path is reported to the user instead of crashing the worker thread.
     */
    @Test
    fun updateSongMetadataReportsMissingPath() {
        var completed = false

        MediaMetadataUtil.updateSongMetadata(context, song("1", null), "T", "A", "B") { completed = true }

        assertEquals("Failed to update metadata: Song has no file path", awaitToast())
        assertFalse(completed)
    }

    /**
     * Verifies an unreadable audio file surfaces a failure toast, and the success callback is not run.
     */
    @Test
    fun updateAlbumMetadataReportsUnreadableFile() {
        var completed = false
        val target = album(song("1", "/definitely/not/here.mp3"))

        MediaMetadataUtil.updateAlbumMetadata(context, target, "New Album", "New Artist") { completed = true }

        assertTrue(awaitToast().startsWith("Failed to update metadata: "))
        assertFalse(completed)
        assertTrue(scanned.isEmpty())
    }

    /**
     * Verifies a cover that cannot be read from its URI ends the update quietly: nothing is edited or scanned.
     * A second job on the same single-thread executor acts as a barrier proving the first has finished.
     */
    @Test
    fun updateAlbumArtStopsWhenImageUnreadable() {
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null
        var completed = false
        val barrier = CountDownLatch(1)

        MediaMetadataUtil.updateAlbumArt(context, album(song("1", "/x.mp3")), uri) { completed = true }
        MediaMetadataUtil.deleteAlbum(context, album()) { barrier.countDown() }

        assertTrue(barrier.await(10, TimeUnit.SECONDS))
        assertFalse(completed)
        assertEquals(listOf("Album deleted"), toasts)
        assertTrue(scanned.isEmpty())
    }

    /**
     * Verifies a readable cover still fails cleanly, with a cover-specific message, when a track has no path.
     */
    @Test
    fun updateAlbumArtReportsMissingTrackPath() {
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns byteArrayOf(1, 2, 3).inputStream()
        var completed = false

        MediaMetadataUtil.updateAlbumArt(context, album(song("1", null)), uri) { completed = true }

        assertEquals("Failed to update cover: Song has no file path", awaitToast())
        assertFalse(completed)
    }
}
