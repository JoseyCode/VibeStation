package com.boogie.vibestation.share

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

/** In-memory [ShareHost] that records what the session stores. */
internal class FakeHost : ShareHost {
    var library = mutableListOf<LocalTrack>()
    var free = Long.MAX_VALUE
    var existingNames = mutableListOf<String>()
    var cover: String? = null
    var failAddPlaylist = false
    val failTitles = mutableSetOf<String>()
    val stored = mutableListOf<Pair<ShareTrack, List<Byte>>>()
    val playlists = mutableListOf<JSONObject>()
    val coversRequested = mutableListOf<String>()
    private var nextId = 100

    override fun localTracks(): List<LocalTrack> = library

    override fun freeBytes(): Long = free

    override fun storeTrack(track: ShareTrack, input: InputStream): LocalTrack {
        if (track.title in failTitles) throw IOException("disk on fire")
        stored += track to input.readBytes().toList()
        return LocalTrack((nextId++).toString(), track.title, track.artist, track.durationMs)
    }

    override fun existingPlaylistNames(): List<String> = existingNames

    override fun storeCover(encoded: String): String? {
        coversRequested += encoded
        return cover
    }

    override fun addPlaylist(entry: JSONObject) {
        if (failAddPlaylist) throw IOException("prefs locked")
        playlists += entry
    }
}

/** Wires a [ShareSession] to a [FakeShareTransport] and [FakeHost], running everything inline. */
internal class SessionHarness(myNonce: String = "00000001") {
    val transport = FakeShareTransport()
    val host = FakeHost()
    val states = mutableListOf<ShareState>()
    var pickRequests = 0
    val me = ShareHello(VERSION, myNonce, "Me")
    val peer = ShareHello(VERSION, "ffffffff", "Peer")
    val session = ShareSession(
        transport, host, me,
        Executor { task ->
            task.run()
        },
        object : ShareSession.Listener {
            override fun onState(state: ShareState) {
                states += state
            }

            override fun onPickRequested() {
                pickRequests++
            }
        }
    )

    val state: ShareState get() = session.state

    /** Messages the session has sent so far, parsed. */
    fun sent(): List<ShareMessage> = transport.messages.map(ShareMessage::parse)

    /** Runs pairing to the Locked state with a peer that has the larger nonce, so the peer requests. */
    fun connect() {
        session.start()
        transport.simulateConnectionInitiated(PEER, peer.encode(), CODE)
        session.yes()
        transport.simulateConnected(PEER)
    }

    companion object {
        const val VERSION = 21
        const val PEER = "peer-1"
        const val CODE = "4821"

        fun track(title: String, size: Long = 10) =
            ShareTrack(title, "Artist", "Album", 1000, size, "$title.mp3", "audio/mpeg")

        fun manifest(kind: ShareKind = ShareKind.PLAYLIST, vararg titles: String, name: String = "Mix") =
            ShareManifest(kind, name, "desc", "", titles.map { track(it) })

        fun bytes(vararg values: Int): InputStream = ByteArrayInputStream(values.map { it.toByte() }.toByteArray())
    }
}
