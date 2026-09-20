package com.boogie.vibestation.share

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps Share Mode alive while the screen is folded, rotated, or another app is in front: it owns the
 * [ShareSession] and the radio, runs as a foreground `dataSync` service so Android does not stop a transfer,
 * and switches itself off after [SEARCH_TIMEOUT_MS] with nobody to connect to. The screen binds to it, sends
 * the user's yes/no and offers in, and gets every state change back on the main thread.
 */
class ShareService : Service() {

    /** Hands the running service to the screen that binds to it. */
    inner class LocalBinder : Binder() {
        internal val service: ShareService get() = this@ShareService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val notifications = ShareNotifications(this)
    private lateinit var sessionExecutor: ExecutorService
    private lateinit var transportExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService
    private var session: ShareSession? = null
    private var searchTimeout: SearchTimeout? = null
    private var screen: ShareSession.Listener? = null

    /** The latest session state; readable from the main thread. */
    internal var state: ShareState = ShareState.Idle
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sessionExecutor = Executors.newSingleThreadExecutor()
        transportExecutor = Executors.newSingleThreadExecutor()
        ioExecutor = Executors.newCachedThreadPool()
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.enterForeground(this, ShareStatus.text(ShareState.Idle))
        if (session == null) begin()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        searchTimeout?.stop()
        session = null
        sessionExecutor.shutdown()
        transportExecutor.shutdown()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Sets who hears about state changes and asks for the item picker.
     *
     * @param listener The screen, or null when it goes away. It is told the current state immediately.
     */
    internal fun attach(listener: ShareSession.Listener?) {
        screen = listener
        listener?.onState(state)
    }

    /** The user dragged up. */
    internal fun yes() {
        session?.yes()
    }

    /** The user dragged down. */
    internal fun no() {
        session?.no()
    }

    /** Offers [offer] to the connected phone. */
    internal fun offer(offer: ShareOffer) {
        session?.offer(offer)
    }

    /** Leaves Share Mode. */
    internal fun end() {
        session?.stop()
    }

    private fun begin() {
        val incomingDir = File(cacheDir, "share")
        incomingDir.mkdirs()
        val transport = NearbyShareTransport(this, incomingDir, transportExecutor, ioExecutor)
        searchTimeout = SearchTimeout(SEARCH_TIMEOUT_MS, HandlerShareTimer(main), ::end)
        val listener = object : ShareSession.Listener {
            override fun onState(state: ShareState) {
                main.post { onSessionState(state) }
            }

            override fun onPickRequested() {
                main.post { screen?.onPickRequested() }
            }
        }
        session = ShareSession(transport, AndroidShareHost(this), ShareIdentity.hello(this), sessionExecutor, listener)
            .also { it.start() }
    }

    private fun onSessionState(new: ShareState) {
        state = new
        searchTimeout?.onState(new)
        screen?.onState(new)
        if (new is ShareState.Closed) {
            session = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            notifications.update(ShareStatus.text(new))
        }
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
