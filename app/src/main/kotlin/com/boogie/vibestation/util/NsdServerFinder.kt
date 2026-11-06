package com.boogie.vibestation.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Looks for VibeStation web servers on the local network with Android's built-in service discovery.
 * It only searches when asked, for a limited time, and checks each announced address before reporting it
 * (see [ServerDiscovery.candidateUrls] and [ServerDiscovery.isVibeStation]). The decisions live in
 * [ServerDiscovery]; this class only drives the radio and so can only be tried on a real device.
 *
 * @param context Any context; the application context is kept.
 */
class NsdServerFinder(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    /**
     * Searches for [timeoutMs] milliseconds, then reports what was found.
     *
     * @param timeoutMs How long to listen for announcements.
     * @param onDone Called once on the main thread with the servers that answered, possibly none.
     */
    fun find(timeoutMs: Long, onDone: (List<DiscoveredServer>) -> Unit) {
        Search(onDone).start(timeoutMs)
    }

    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

    /** One search: resolves announcements one at a time (Android allows a single resolve at once) and probes them. */
    private inner class Search(private val onDone: (List<DiscoveredServer>) -> Unit) : NsdManager.DiscoveryListener {
        // Everything below is only touched on [worker], which runs one task at a time
        private val worker: ExecutorService = Executors.newSingleThreadExecutor()
        private val pending = ArrayDeque<NsdServiceInfo>()
        private val found = LinkedHashMap<String, DiscoveredServer>()
        private var resolving = false
        private var finished = false

        fun start(timeoutMs: Long) {
            try {
                nsd.discoverServices(ServerDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Could not start discovery", e)
                onWorker { finish() }
                return
            }
            main.postDelayed({ onWorker { finish() } }, timeoutMs)
        }

        private fun onWorker(task: () -> Unit) {
            try {
                worker.execute(task)
            } catch (ignored: RejectedExecutionException) {
                // Already finished
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            try {
                nsd.stopServiceDiscovery(this)
            } catch (ignored: IllegalArgumentException) {
                // Discovery never started or already stopped
            }
            val result = found.values.toList()
            main.post { onDone(result) }
            worker.shutdown()
        }

        private fun resolveNext() {
            if (resolving || finished) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(next, ResolveListener())
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Could not resolve ${next.serviceName}", e)
                resolving = false
                resolveNext()
            }
        }

        private fun handleResolved(info: NsdServiceInfo) {
            val id = ServerDiscovery.serverId(info.attributes)
            if (id != null && id !in found) {
                val urls = ServerDiscovery.candidateUrls(addressesOf(info), info.port)
                val reachable = urls.firstOrNull { ServerDiscovery.isVibeStation(client, it) }
                if (reachable != null) found[id] = DiscoveredServer(id, info.serviceName, reachable)
            }
        }

        private inner class ResolveListener : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                onWorker {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                onWorker {
                    if (!finished) handleResolved(serviceInfo)
                    resolving = false
                    resolveNext()
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            onWorker {
                pending.addLast(serviceInfo)
                resolveNext()
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery failed to start: $errorCode")
            onWorker { finish() }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    private companion object {
        const val TAG = "VibeDiscovery"
    }
}
