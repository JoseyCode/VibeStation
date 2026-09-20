package com.boogie.vibestation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.boogie.vibestation.models.Song
import java.util.concurrent.Executors

/**
 * Foreground service that handles audio playback using Android's MediaPlayer.
 * Integrates with MediaSessionCompat to provide system notification controls,
 * lock-screen media actions, and coordinates audio focus requests with the OS.
 */
class AudioService : Service() {

    /**
     * Callback triggered when playback state changes or the active track changes.
     */
    interface ServiceCallback {
        /** Triggered when the service changes active track, passing metadata and art. */
        fun onTrackChanged(song: Song, albumArt: Bitmap?)

        /** Triggered when active playback starts or pauses. */
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    /**
     * Local Binder implementation returning this Service instance to bound Activities.
     */
    inner class LocalBinder : Binder() {
        /** The running service instance. */
        val service: AudioService
            get() = this@AudioService
    }

    private val serviceBinder = LocalBinder()
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { stopSelf() }

    private val queue = PlaybackQueue()
    private var equalizerInstance: Equalizer? = null

    /** Callback notified of track and playback state changes. */
    var callback: ServiceCallback? = null

    /** Song loaded into the player, or null before the first track is chosen. */
    var currentSong: Song? = null
        private set

    /** Decimated album art of the active song; written by the artwork thread, read on the main thread. */
    @Volatile
    var currentArt: Bitmap? = null
        private set

    /** Playback rate multiplier; changing it applies immediately to a playing track. */
    var playbackSpeed: Float = 1.0f
        set(value) {
            field = value
            if (mediaPlayer.isPlaying) applyPlaybackSpeed(value)
        }

    /** Audio session ID for visualizer attachment. */
    val audioSessionId: Int
        get() = mediaPlayer.audioSessionId

    /** Elapsed playback offset in milliseconds. */
    val currentPosition: Int
        get() = mediaPlayer.currentPosition

    /** Total duration of the active song in milliseconds. */
    val duration: Int
        get() = mediaPlayer.duration

    /** True while the MediaPlayer is actively playing. */
    val isPlaying: Boolean
        get() = mediaPlayer.isPlaying

    /**
     * Equalizer attached to this audio session, created on first access.
     * Null if the device does not support it.
     */
    val equalizer: Equalizer?
        get() {
            if (equalizerInstance == null) {
                equalizerInstance = try {
                    Equalizer(0, mediaPlayer.audioSessionId)
                } catch (e: Exception) {
                    null
                }
            }
            return equalizerInstance
        }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action && isPlaying) {
                togglePlayPause()
            }
        }
    }

    /** Pauses playback when focus is lost to another app. */
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if ((focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) &&
            mediaPlayer.isPlaying
        ) {
            togglePlayPause()
        }
    }

    override fun onBind(intent: Intent?): IBinder = serviceBinder

    /**
     * Prepares components at initialization: MediaPlayer, notification channel,
     * MediaSession registers, and AudioFocus change listeners.
     */
    override fun onCreate() {
        super.onCreate()
        mediaPlayer = MediaPlayer()

        // Configure for best audio quality (DAC routing preparation)
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        setupMediaSession()

        // Automatically load and play the next song when current track ends
        mediaPlayer.setOnCompletionListener { playNext() }
    }

    /**
     * Sets the active queue and starts playing a specific track within it.
     *
     * @param newQueue        Songs mapping the new queue list.
     * @param initialPosition Index position in list to begin playback at.
     */
    fun setQueueAndPlay(newQueue: List<Song>, initialPosition: Int) {
        queue.set(newQueue, initialPosition)
        playTrack()
    }

    /**
     * Prepares the MediaPlayer with the selected track URI from the queue.
     * Requests OS audio focus before initiating.
     */
    private fun playTrack() {
        val song = queue.current ?: return
        if (!requestFocus()) return

        currentSong = song

        try {
            mediaPlayer.reset()
            val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id.toLong())
            mediaPlayer.setDataSource(this, trackUri)
            mediaPlayer.prepare()
            applyPlaybackSpeed(playbackSpeed)
            mediaPlayer.start()
            cancelTimeout()

            currentArt = null
            updateSystemPlayerAndUI()

            loadAlbumArtAndNotify(trackUri)
        } catch (exception: Exception) {
            Toast.makeText(this, "Error playing track", Toast.LENGTH_SHORT).show()
        }
    }

    /** Applies [speed] to the MediaPlayer; some devices/formats reject playback params. */
    private fun applyPlaybackSpeed(speed: Float) {
        try {
            mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Alternates playback state (Play/Pause) based on current MediaPlayer state.
     */
    fun togglePlayPause() {
        if (currentSong == null) return
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            startTimeout()
        } else if (requestFocus()) {
            mediaPlayer.start()
            cancelTimeout()
        }
        updateSystemPlayerAndUI()
    }

    /** Starts the 10-minute inactivity timeout. */
    private fun startTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, INACTIVITY_TIMEOUT_MS)
    }

    /** Cancels the inactivity timeout. */
    private fun cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    /**
     * Retrieves the upcoming songs in the queue to be used for cache preloading.
     * Wraps around the end of the queue.
     */
    fun getUpcomingSongs(count: Int): List<Song> = queue.upcoming(count)

    /** Skips to the next song in the active queue list (wraps around on end). */
    fun playNext() {
        if (!queue.advance()) return
        playTrack()
    }

    /**
     * Skips to the previous song in the active queue list (wraps around on start).
     * Restarts the current song instead if it has played for more than 3 seconds.
     */
    fun playPrev() {
        if (queue.isEmpty) return
        if (mediaPlayer.currentPosition > RESTART_THRESHOLD_MS) {
            playTrack()
            return
        }
        queue.retreat()
        playTrack()
    }

    /**
     * Seeks to a designated time offset position in the current track.
     *
     * @param positionMs Target track location in milliseconds.
     */
    fun seekTo(positionMs: Int) {
        mediaPlayer.seekTo(positionMs)
    }

    /** Handles service start action intents dispatched from notifications. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrev()
        }
        return START_NOT_STICKY
    }

    /**
     * Reads track album artwork on a background thread. Decimates the image resolution
     * to prevent UI lags or Binder payload size constraint exceptions when sending to notification.
     *
     * @param trackUri Shared content resolver URI pointing to the track file path.
     */
    private fun loadAlbumArtAndNotify(trackUri: Uri) {
        artworkExecutor.execute {
            currentArt = null
            val metadataRetriever = MediaMetadataRetriever()
            try {
                metadataRetriever.setDataSource(this, trackUri)
                metadataRetriever.embeddedPicture?.let { pictureData ->
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = 2 } // Decimate for notification speed
                    currentArt = BitmapFactory.decodeByteArray(pictureData, 0, pictureData.size, decodeOptions)
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    metadataRetriever.release()
                } catch (ignored: Exception) {
                }
            }

            mainHandler.post { updateSystemPlayerAndUI() }
        }
    }

    /**
     * Syncs playback changes across the system Media Session (for lock screens),
     * updates the foreground notification, and alerts bound Activity callbacks.
     */
    private fun updateSystemPlayerAndUI() {
        val song = currentSong ?: return
        val art = currentArt
        val isPlaying = mediaPlayer.isPlaying

        // 1. Send update to Media Session (Lock screen controls)
        val playbackState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(playbackState, mediaPlayer.currentPosition.toLong(), 1.0f)
                .build()
        )

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.duration.toLong())
        if (art != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        // 2. Refresh Foreground Notification
        startForeground(NOTIFICATION_ID, buildNotification(song, art, isPlaying))

        // 3. Notify Activity UI
        callback?.let {
            it.onTrackChanged(song, art)
            it.onPlaybackStateChanged(isPlaying)
        }
    }

    /**
     * Builds the system media style notification showing player action buttons
     * (Prev, Play/Pause, Next) and active metadata.
     *
     * @param song      Song shown in the notification.
     * @param art       Album art shown as the large icon, if loaded.
     * @param isPlaying Active status indicating play or pause buttons.
     * @return          Formed System Notification.
     */
    private fun buildNotification(song: Song, art: Bitmap?, isPlaying: Boolean): Notification {
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause_bubbly else R.drawable.ic_play_bubbly

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_bubbly)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(art)
            .setContentIntent(appPendingIntent)
            .addAction(R.drawable.ic_prev_bubbly, "Prev", servicePendingIntent(ACTION_PREV))
            .addAction(playPauseIcon, "Play/Pause", servicePendingIntent(ACTION_PLAY_PAUSE))
            .addAction(R.drawable.ic_next_bubbly, "Next", servicePendingIntent(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** PendingIntent delivering [action] back to this service from a notification button. */
    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, AudioService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Requests audio focus from the OS AudioManager. Adapts API levels (pre-Oreo vs modern Oreo+).
     *
     * @return True if focus was successfully granted, false otherwise.
     */
    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Sets up MediaSessionCompat options and callback listener definitions. */
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VibeStation")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = togglePlayPause()

            override fun onPause() = togglePlayPause()

            override fun onSkipToNext() = playNext()

            override fun onSkipToPrevious() = playPrev()

            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
        })
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        mediaSession.isActive = true
    }

    /** Registers low-priority Notification Channels required by Android 8.0+ Oreo APIs. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            notificationChannel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(notificationChannel)
        }
    }

    /**
     * Service teardown callback. Releases MediaPlayer resources,
     * destroys active MediaSessions, and terminates thread executors.
     */
    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        super.onDestroy()
        cancelTimeout()
        equalizerInstance?.release()
        mediaPlayer.release()
        mediaSession.release()
        artworkExecutor.shutdown()
    }

    /** Intent actions carried by notification buttons and handled in [onStartCommand]. */
    companion object {
        /** Toggles between playing and paused. */
        const val ACTION_PLAY_PAUSE = "com.boogie.vibestation.ACTION_PLAY_PAUSE"

        /** Skips to the next song. */
        const val ACTION_NEXT = "com.boogie.vibestation.ACTION_NEXT"

        /** Returns to the previous song, or restarts the current one. */
        const val ACTION_PREV = "com.boogie.vibestation.ACTION_PREV"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vibe_channel"
        private const val CHANNEL_NAME = "VibeStation Playback"
        private const val INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000L
        private const val RESTART_THRESHOLD_MS = 3000
    }
}
