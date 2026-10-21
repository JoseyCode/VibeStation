package com.boogie.vibestation.util

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A VibeStation web server found on the local network.
 *
 * @property id Stable id the server announces, which survives restarts and address changes.
 * @property name Display name the server announces.
 * @property baseUrl Address the app can sync with, such as `http://192.168.1.5:1337`.
 */
data class DiscoveredServer(val id: String, val name: String, val baseUrl: String)

/**
 * The decisions around finding the web server on the network, apart from the radio itself
 * ([NsdServerFinder]): which announced addresses are worth trying, how to tell a real server from a
 * stale or stray announcement, and what to do with the result.
 */
object ServerDiscovery {
    /** Service type the web server announces over mDNS (`_vibestation._tcp`). */
    const val SERVICE_TYPE = "_vibestation._tcp."

    /** Name of the announcement attribute carrying the server id. */
    const val ID_ATTRIBUTE = "id"

    private const val PROBE_TIMEOUT_SECONDS = 2L
    private const val SESSION_PATH = "/api/session"
    private const val SESSION_FIELD = "authRequired"

    /** What the app should do about a search result. */
    sealed interface Decision {
        /** Nothing to change or ask. */
        data object NoChange : Decision

        /**
         * Servers the user has not picked yet; ask which one to use.
         *
         * @property servers The servers to choose from.
         */
        data class Offer(val servers: List<DiscoveredServer>) : Decision

        /**
         * The saved server answered at a new address; switch to it without asking.
         *
         * @property server The saved server at its new address.
         */
        data class Moved(val server: DiscoveredServer) : Decision

        /**
         * The saved address is this server; remember its id so it can be found again later.
         *
         * @property server The server already in use.
         */
        data class Adopt(val server: DiscoveredServer) : Decision
    }

    /**
     * Reads the server id from an announcement's attributes.
     *
     * @param attributes Attributes of the resolved service.
     * @return The id, or null when it is missing or blank (not one of ours).
     */
    fun serverId(attributes: Map<String, ByteArray?>): String? =
        attributes[ID_ATTRIBUTE]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Turns the addresses a server announced into URLs worth trying, most likely first. A machine announces
     * every interface it has, including Docker bridges and link-local IPv6 addresses a phone cannot use,
     * so unusable kinds are dropped and IPv4 goes first.
     *
     * @param addresses Addresses from the announcement.
     * @param port Port from the announcement.
     * @return Base URLs without a trailing slash.
     */
    fun candidateUrls(addresses: List<InetAddress>, port: Int): List<String> = addresses
        .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isLinkLocalAddress || it.isMulticastAddress }
        .sortedByDescending { it is Inet4Address }
        .map { address ->
            val host = address.hostAddress.orEmpty().substringBefore('%')
            if (address is Inet4Address) "http://$host:$port" else "http://[$host]:$port"
        }
        .distinct()

    /**
     * Checks that [baseUrl] really is a VibeStation server, using the session route that needs no password.
     *
     * @param client HTTP client to use; its timeouts are shortened for the check.
     * @param baseUrl Address to try.
     * @return True when it answers like the web server.
     */
    fun isVibeStation(client: OkHttpClient, baseUrl: String): Boolean {
        val quick = client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        return try {
            quick.newCall(Request.Builder().url("$baseUrl$SESSION_PATH").build()).execute().use { response ->
                response.isSuccessful && JSONObject(response.body?.string().orEmpty()).has(SESSION_FIELD)
            }
        } catch (ignored: IOException) {
            false
        } catch (ignored: JSONException) {
            false
        } catch (ignored: IllegalArgumentException) {
            false
        }
    }

    /**
     * Decides what a search result means for the saved server.
     *
     * @param found Servers that answered the search.
     * @param savedId Id of the server saved earlier, or blank when none or unknown.
     * @param savedUrl Address saved earlier, or blank.
     * @param declinedIds Ids the user already said "not now" to in this session.
     * @return The action to take.
     */
    fun decide(found: List<DiscoveredServer>, savedId: String, savedUrl: String, declinedIds: Set<String>): Decision {
        val saved = if (savedId.isNotBlank()) found.firstOrNull { it.id == savedId } else null
        val untracked = savedId.isBlank() && savedUrl.isNotBlank()
        val inUse = if (untracked) found.firstOrNull { sameAddress(it.baseUrl, savedUrl) } else null
        val offers = found.filter { it.id != savedId && it.id !in declinedIds }
        return when {
            saved != null && sameAddress(saved.baseUrl, savedUrl) -> Decision.NoChange
            saved != null -> Decision.Moved(saved)
            inUse != null -> Decision.Adopt(inUse)
            offers.isEmpty() -> Decision.NoChange
            else -> Decision.Offer(offers)
        }
    }

    /**
     * Finds the saved server at a new address after a sync could not reach it.
     *
     * @param found Servers that answered the search.
     * @param savedId Id of the saved server; blank means there is nothing to recognise.
     * @param savedUrl Address that no longer answers.
     * @return The same server at a different address, or null.
     */
    fun recover(found: List<DiscoveredServer>, savedId: String, savedUrl: String): DiscoveredServer? {
        if (savedId.isBlank()) return null
        return found.firstOrNull { it.id == savedId && !sameAddress(it.baseUrl, savedUrl) }
    }

    private fun sameAddress(a: String, b: String): Boolean = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
}
