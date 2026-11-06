package com.boogie.vibestation

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.boogie.vibestation.util.DiscoveredServer
import com.boogie.vibestation.util.NsdServerFinder
import com.boogie.vibestation.util.ServerDiscovery

/**
 * The prompts around finding the web server automatically: offering a server that was found, asking
 * for its password, and following the saved server when it turns up at a new address. The decisions
 * are in [ServerDiscovery]; this class only shows dialogs and saves the answers. The app looks only
 * when the sync settings are used, never in the background.
 *
 * @param activity Activity that shows the dialogs.
 * @param prefs Preferences holding the saved server address, id and password.
 * @param finder Searches the network.
 */
class SyncDiscoveryController(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val finder: NsdServerFinder = NsdServerFinder(activity)
) {
    private val declinedIds = mutableSetOf<String>()

    private val savedUrl get() = prefs.getString(URL_KEY, "").orEmpty()
    private val savedId get() = prefs.getString(ID_KEY, "").orEmpty()

    /**
     * Searches when the sync settings open, and only speaks up when something needs the user: a new server
     * to offer, or the saved server that moved. Results are dropped if the settings were closed meanwhile.
     *
     * @param stillOpen Whether the sync settings are still on screen when the search ends.
     * @param reportNothing Tell the user when nothing was found (for a search they asked for).
     */
    fun search(stillOpen: () -> Boolean, reportNothing: Boolean) {
        if (reportNothing) toast("Searching for VibeStation on this network...")
        finder.find(SEARCH_MS) { found ->
            if (stillOpen()) handle(found, reportNothing)
        }
    }

    /**
     * Looks for the saved server at a new address after a sync could not reach it.
     *
     * @param onDone Called with true when the saved address was updated, so syncing can be tried again.
     */
    fun recoverAddress(onDone: (Boolean) -> Unit) {
        if (savedId.isBlank()) return onDone(false)
        finder.find(RECOVERY_SEARCH_MS) { found ->
            val server = ServerDiscovery.recover(found, savedId, savedUrl)
            if (server != null) {
                prefs.edit().putString(URL_KEY, server.baseUrl).apply()
                toast("Found your server at its new address: ${server.baseUrl}")
            }
            onDone(server != null)
        }
    }

    /** Forgets the saved server id when the address was typed in by hand, since it may be a different server now. */
    fun addressEdited(oldUrl: String, newUrl: String) {
        if (!oldUrl.trim().trimEnd('/').equals(newUrl.trim().trimEnd('/'), ignoreCase = true)) {
            prefs.edit().remove(ID_KEY).apply()
        }
    }

    private fun handle(found: List<DiscoveredServer>, reportNothing: Boolean) {
        when (val decision = ServerDiscovery.decide(found, savedId, savedUrl, declinedIds)) {
            is ServerDiscovery.Decision.Adopt -> prefs.edit().putString(ID_KEY, decision.server.id).apply()
            is ServerDiscovery.Decision.Moved -> {
                prefs.edit().putString(URL_KEY, decision.server.baseUrl).apply()
                toast("Your server is now at ${decision.server.baseUrl}")
            }
            is ServerDiscovery.Decision.Offer -> offer(decision.servers)
            ServerDiscovery.Decision.NoChange -> if (reportNothing) {
                toast(if (found.isEmpty()) "No VibeStation server found on this network" else "Already using the server found here")
            }
        }
    }

    private fun offer(servers: List<DiscoveredServer>) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("VibeStation found")
            .setNegativeButton("Not now") { _, _ -> declinedIds += servers.map { it.id } }
        if (servers.size == 1) {
            val server = servers.first()
            builder.setMessage("\"${server.name}\" is on your network at ${server.baseUrl}.\n\nUse it for syncing?")
                .setPositiveButton("Use it") { _, _ -> use(server) }
        } else {
            val labels = servers.map { "${it.name}\n${it.baseUrl}" }.toTypedArray()
            builder.setItems(labels) { _, which -> use(servers[which]) }
        }
        builder.show()
    }

    private fun use(server: DiscoveredServer) {
        val sameServer = savedId == server.id
        prefs.edit().putString(URL_KEY, server.baseUrl).putString(ID_KEY, server.id).apply()
        askForPassword(prefill = if (sameServer) prefs.getString(PASSWORD_KEY, "").orEmpty() else "")
    }

    private fun askForPassword(prefill: String) {
        val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val field = EditText(activity).apply {
            hint = "Server password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefill)
        }
        val layout = LinearLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(field, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        AlertDialog.Builder(activity)
            .setTitle("Server password")
            .setMessage("Enter the password you chose in the VibeStation web player.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString(PASSWORD_KEY, field.text.toString()).apply()
                toast("Server saved. You can sync now.")
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    /** Preference keys and timings shared with [MainActivity]. */
    companion object {
        /** Preference key of the saved server address. */
        const val URL_KEY = "sync_server_url"

        /** Preference key of the saved server id (what lets the app recognise the server at a new address). */
        const val ID_KEY = "sync_server_id"

        /** Preference key of the saved server password. */
        const val PASSWORD_KEY = "sync_server_password"

        private const val SEARCH_MS = 4_000L
        private const val RECOVERY_SEARCH_MS = 5_000L
        private const val PADDING_DP = 20
    }
}
