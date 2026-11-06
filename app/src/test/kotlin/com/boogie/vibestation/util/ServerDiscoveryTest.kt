package com.boogie.vibestation.util

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the decisions around finding the web server on the network. */
class ServerDiscoveryTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    @BeforeTest
    fun setUp() = server.start()

    @AfterTest
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun found(id: String, url: String) = DiscoveredServer(id, "VibeStation ($id)", url)

    private fun attrs(vararg pairs: Pair<String, String?>) = pairs.associate { (k, v) -> k to v?.toByteArray() }

    // serverId

    /** The id comes from the announcement's id attribute, trimmed. */
    @Test
    fun readsTheServerIdFromTheAnnouncement() {
        assertEquals("abc-123", ServerDiscovery.serverId(attrs("id" to " abc-123 ", "v" to "2")))
    }

    /** An announcement without a usable id is not one of ours. */
    @Test
    fun ignoresAnnouncementsWithoutAnId() {
        assertNull(ServerDiscovery.serverId(attrs("v" to "2")))
        assertNull(ServerDiscovery.serverId(attrs("id" to "   ")))
        assertNull(ServerDiscovery.serverId(mapOf("id" to null)))
    }

    // candidateUrls

    /** IPv4 addresses come first, because they are the ones that work on a home network. */
    @Test
    fun triesIpv4BeforeIpv6() {
        val urls = ServerDiscovery.candidateUrls(
            listOf(InetAddress.getByName("2001:db8::5"), InetAddress.getByName("192.168.1.5")),
            1337
        )
        assertEquals(listOf("http://192.168.1.5:1337", "http://[2001:db8:0:0:0:0:0:5]:1337"), urls)
    }

    /** Link-local, loopback and multicast addresses are announced by the server's machine but never reachable from a phone. */
    @Test
    fun dropsAddressesAPhoneCannotUse() {
        val urls = ServerDiscovery.candidateUrls(
            listOf(
                InetAddress.getByName("fe80::8f7f:5a60:5996:d664"),
                InetAddress.getByName("169.254.10.10"),
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("224.0.0.251"),
                InetAddress.getByName("192.168.50.162"),
                InetAddress.getByName("192.168.50.162")
            ),
            1337
        )
        assertEquals(listOf("http://192.168.50.162:1337"), urls)
    }

    /** With nothing usable announced there is nothing to try. */
    @Test
    fun noUsableAddressMeansNoCandidates() {
        assertTrue(ServerDiscovery.candidateUrls(listOf(InetAddress.getByName("fe80::1")), 1337).isEmpty())
        assertTrue(ServerDiscovery.candidateUrls(emptyList(), 1337).isEmpty())
    }

    // isVibeStation

    /** The web server's session answer, which needs no password, identifies it. */
    @Test
    fun recognisesTheWebServer() {
        server.enqueue(MockResponse().setBody("""{"authRequired":true,"setupRequired":false,"authenticated":false}"""))
        val base = server.url("/").toString().trimEnd('/')
        assertTrue(ServerDiscovery.isVibeStation(client, base))
        assertEquals("/api/session", server.takeRequest().path)
    }

    /** Anything else listening on that address is not offered. */
    @Test
    fun rejectsOtherThingsOnThatAddress() {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("<html>router admin</html>"))
        assertFalse(ServerDiscovery.isVibeStation(client, base))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"authRequired":true}"""))
        assertFalse(ServerDiscovery.isVibeStation(client, base))
        server.enqueue(MockResponse().setBody("""{"something":"else"}"""))
        assertFalse(ServerDiscovery.isVibeStation(client, base))
    }

    /** A dead or malformed address is simply not a server. */
    @Test
    fun deadOrMalformedAddressesAreNotServers() {
        val base = server.url("/").toString().trimEnd('/')
        server.shutdown()
        assertFalse(ServerDiscovery.isVibeStation(client, base))
        assertFalse(ServerDiscovery.isVibeStation(client, "not a url"))
    }

    // decide

    /** With nothing found there is nothing to do. */
    @Test
    fun nothingFoundChangesNothing() {
        assertEquals(ServerDiscovery.Decision.NoChange, ServerDiscovery.decide(emptyList(), "", "", emptySet()))
        assertEquals(ServerDiscovery.Decision.NoChange, ServerDiscovery.decide(emptyList(), "a", "http://x:1", emptySet()))
    }

    /** A first-time search offers what it found. */
    @Test
    fun offersServersWhenNoneIsSaved() {
        val pi = found("a", "http://192.168.1.5:1337")
        assertEquals(ServerDiscovery.Decision.Offer(listOf(pi)), ServerDiscovery.decide(listOf(pi), "", "", emptySet()))
    }

    /** The server the app already uses, saved by hand before ids existed, just gets its id remembered. */
    @Test
    fun adoptsTheIdOfAServerAlreadyInUse() {
        val pi = found("a", "http://192.168.1.5:1337")
        val decision = ServerDiscovery.decide(listOf(pi), "", "http://192.168.1.5:1337/", emptySet())
        assertEquals(ServerDiscovery.Decision.Adopt(pi), decision)
    }

    /** The saved server at the saved address needs no prompt, even when another server is around. */
    @Test
    fun staysQuietWhenTheSavedServerIsWhereItWas() {
        val pi = found("a", "http://192.168.1.5:1337")
        val other = found("b", "http://192.168.1.9:1337")
        val decision = ServerDiscovery.decide(listOf(other, pi), "a", "http://192.168.1.5:1337", emptySet())
        assertEquals(ServerDiscovery.Decision.NoChange, decision)
    }

    /** The saved server turning up at a new address is followed without asking. */
    @Test
    fun followsTheSavedServerToANewAddress() {
        val moved = found("a", "http://192.168.1.77:1337")
        val decision = ServerDiscovery.decide(listOf(moved), "a", "http://192.168.1.5:1337", emptySet())
        assertEquals(ServerDiscovery.Decision.Moved(moved), decision)
    }

    /** A different server than the saved one is offered, a declined one is not offered again. */
    @Test
    fun offersOtherServersButNotDeclinedOnes() {
        val other = found("b", "http://192.168.1.9:1337")
        val decision = ServerDiscovery.decide(listOf(other), "a", "http://192.168.1.5:1337", emptySet())
        assertEquals(ServerDiscovery.Decision.Offer(listOf(other)), decision)
        assertEquals(ServerDiscovery.Decision.NoChange, ServerDiscovery.decide(listOf(other), "a", "http://192.168.1.5:1337", setOf("b")))
    }

    // recover

    /** After a failed sync the same server at a different address is the answer. */
    @Test
    fun recoversTheSameServerAtANewAddress() {
        val moved = found("a", "http://192.168.1.77:1337")
        assertEquals(moved, ServerDiscovery.recover(listOf(found("b", "http://192.168.1.9:1337"), moved), "a", "http://192.168.1.5:1337"))
    }

    /** No saved id, another server only, or the same address all mean there is nothing to recover. */
    @Test
    fun recoversNothingWithoutAMatch() {
        val pi = found("a", "http://192.168.1.5:1337")
        assertNull(ServerDiscovery.recover(listOf(pi), "", "http://192.168.1.5:1337"))
        assertNull(ServerDiscovery.recover(listOf(found("b", "http://192.168.1.9:1337")), "a", "http://192.168.1.5:1337"))
        assertNull(ServerDiscovery.recover(listOf(pi), "a", "http://192.168.1.5:1337/"))
    }
}
