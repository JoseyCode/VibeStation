package com.boogie.vibestation

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PorterDuff
import android.Manifest
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.collection.LruCache
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.ArtUtil
import com.boogie.vibestation.util.MediaMetadataUtil
import com.boogie.vibestation.util.MusicLibraryUtil
import com.boogie.vibestation.util.PlaylistUtil
import com.boogie.vibestation.views.CircularProgressView
import com.boogie.vibestation.views.ParticleView
import com.boogie.vibestation.views.VisualizerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.Locale
import kotlin.math.floor

/**
 * Main Activity for VibeStation. Coordinates UI, media lists (songs, albums, playlists),
 * binds to the background AudioService, manages the visualizer view, handles search querying,
 * handles runtime permissions, and imports/exports playlists.
 */
class MainActivity : AppCompatActivity(), AudioService.ServiceCallback {

    // Audio Playback Content Lists
    private val allSongs = ArrayList<Song>()
    private val allAlbums = ArrayList<Album>()
    private val allPlaylists = ArrayList<Playlist>()
    private val displaySongs = ArrayList<Song>()
    private val displayAlbums = ArrayList<Album>()
    private val displayPlaylists = ArrayList<Playlist>()
    private val displayDetailSongs = ArrayList<Song>()

    // Playlist Target References
    private var activePlaylistForImage: Playlist? = null
    private var activeAlbumForImage: Album? = null
    private var currentOpenPlaylist: Playlist? = null

    // Selection Queue
    private var isSelectionMode = false
    private val selectedSongs = HashSet<Song>()

    // UI Widgets
    private lateinit var albumsGridView: GridView
    private lateinit var playlistsGridView: RecyclerView
    private lateinit var libraryListView: ListView
    private lateinit var detailSongsListView: ListView

    private lateinit var playlistsPageContainer: View
    private lateinit var bottomPlayerContainer: View
    private lateinit var fullPlayerScreenContainer: View
    private lateinit var expandedDetailsContainer: View
    private lateinit var topBarContainer: View
    private lateinit var selectionBarContainer: View

    private lateinit var selectionCountTextView: TextView
    private lateinit var detailTitleTextView: TextView
    private lateinit var miniTitleTextView: TextView
    private lateinit var miniArtistTextView: TextView
    private lateinit var fullTitleTextView: TextView
    private lateinit var fullArtistTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView

    private lateinit var miniArtImageView: ImageView
    private lateinit var fullArtImageView: ImageView
    private lateinit var detailCoverImageView: ImageView

    private lateinit var miniPlayButton: ImageButton
    private lateinit var fullPlayButton: ImageButton
    private lateinit var deleteSelectionButton: ImageButton
    private lateinit var speedButton: Button

    private lateinit var seekBarView: SeekBar
    private lateinit var searchEditText: EditText
    private val tabSearchStates = HashMap<Int, String>()
    private var currentTabId = R.id.nav_albums
    private lateinit var audioVisualizerView: VisualizerView

    // Not every layout variant necessarily includes these decorative views
    private var particleView: ParticleView? = null
    private var circularProgress: CircularProgressView? = null
    private var fireAlbums: MutableSet<String> = HashSet()

    // View Adapters
    private var librarySongAdapter: SongAdapter? = null
    private var albumListAdapter: AlbumAdapter? = null
    private var playlistListAdapter: PlaylistAdapter? = null
    private var detailSongListAdapter: DetailSongAdapter? = null

    // Service Management
    private var audioService: AudioService? = null
    private var isBound = false

    /** The bound service, or null while unbound. */
    private val boundService: AudioService?
        get() = audioService.takeIf { isBound }

    // Async & Cache Management
    private val seekHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val libraryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var sharedPreferences: SharedPreferences
    private var audioVisualizer: Visualizer? = null

    // File / Image Launchers
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backupFileLauncher: ActivityResultLauncher<String>
    private lateinit var restoreFileLauncher: ActivityResultLauncher<Array<String>>

    /**
     * Connection handler for the bound AudioService, enabling callback registration and
     * initial state synchronization when connection is established.
     */
    private val serviceConnection = object : ServiceConnection {
        /**
         * Retrieves the service binder, registers callbacks, and initializes
         * playback controls if a track is already active.
         */
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val boundAudioService = (service as AudioService.LocalBinder).service
            audioService = boundAudioService
            boundAudioService.callback = this@MainActivity
            isBound = true

            boundAudioService.currentSong?.let {
                onTrackChanged(it, boundAudioService.currentArt)
                onPlaybackStateChanged(boundAudioService.isPlaying)
            }
            speedButton.text = formatSpeed(boundAudioService.playbackSpeed)
        }

        /** Triggered if the service connection is unexpectedly lost. */
        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }

    /**
     * Recurring Runnable task updating the seek bar progress slider and time label.
     */
    private val updateSeekBarTask = object : Runnable {
        override fun run() {
            val service = boundService ?: return
            if (!service.isPlaying) return
            val currentProgressMs = service.currentPosition
            val duration = service.duration
            seekBarView.progress = currentProgressMs
            currentTimeTextView.text = formatTime(currentProgressMs)
            if (duration > 0) {
                circularProgress?.progress = currentProgressMs.toFloat() / duration
            }
            seekHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Initializes activity layouts, configures views, registers activity launchers,
     * starts/binds AudioService, registers the back-button handler, and triggers permission checks.
     *
     * @param savedInstanceState Saved instance state bundle.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        sharedPreferences = getSharedPreferences("RetroPrefs", MODE_PRIVATE)
        fireAlbums = HashSet(sharedPreferences.getStringSet("fireAlbums", emptySet()).orEmpty())
        applyRefreshRate(sharedPreferences.getBoolean("120hz", true))

        setupViews()
        setupAdapters()
        setupLaunchers()

        // Launch and bind background Audio Service
        val serviceIntent = Intent(this, AudioService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // System back navigation handling: collapse player panels or clear selections first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isSelectionMode -> clearSelection()
                    fullPlayerScreenContainer.visibility == View.VISIBLE -> fullPlayerScreenContainer.visibility = View.GONE
                    expandedDetailsContainer.visibility == View.VISIBLE -> {
                        expandedDetailsContainer.visibility = View.GONE
                        currentOpenPlaylist = null
                    }
                    searchEditText.text.isNotEmpty() -> searchEditText.setText("")
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        checkPermissions()
    }

    /**
     * Triggers a subtle tactile haptic vibration keypress event on the targeted view.
     *
     * @param view View triggering the haptic feedback.
     */
    private fun triggerHapticFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /**
     * Configures the display refresh rate. If 120Hz option is enabled, it queries
     * and selects the highest refresh rate mode supported by the hardware display.
     *
     * @param is120Hz Active boolean status flag.
     */
    @Suppress("DEPRECATION")
    private fun applyRefreshRate(is120Hz: Boolean) {
        val windowAttributes = window.attributes
        windowAttributes.preferredDisplayModeId = if (is120Hz) {
            windowManager.defaultDisplay.supportedModes.maxByOrNull { it.refreshRate }?.modeId
                ?: windowAttributes.preferredDisplayModeId
        } else {
            0
        }
        window.attributes = windowAttributes
    }

    /**
     * Connects all layout widgets to their XML views, binds click listeners for playback
     * controls, settings panel, playlist generation dialog, navigation menus, search filtering,
     * and seek bar changes.
     */
    private fun setupViews() {
        albumsGridView = findViewById(R.id.gridAlbums)
        libraryListView = findViewById(R.id.listLibrary)
        setupAppVersionDisplay()
        setupLibraryHeader()

        playlistsGridView = findViewById(R.id.gridPlaylists)
        playlistsGridView.layoutManager = GridLayoutManager(this, 2)
        playlistsPageContainer = findViewById(R.id.pagePlaylists)
        expandedDetailsContainer = findViewById(R.id.expandedDetailsView)
        detailSongsListView = findViewById(R.id.listDetailSongs)
        detailTitleTextView = findViewById(R.id.txtDetailTitle)
        detailCoverImageView = findViewById(R.id.imgDetailCover)
        detailCoverImageView.setOnLongClickListener { v ->
            triggerHapticFeedback(v)
            downloadImageFromImageView(detailCoverImageView, detailTitleTextView.text.toString())
            true
        }
        bottomPlayerContainer = findViewById(R.id.bottomPlayer)
        fullPlayerScreenContainer = findViewById(R.id.fullPlayerScreen)
        miniTitleTextView = findViewById(R.id.txtMiniTitle)
        miniArtistTextView = findViewById(R.id.txtMiniArtist)
        miniArtImageView = findViewById(R.id.imgMiniArt)
        miniPlayButton = findViewById(R.id.btnMiniPlay)
        fullTitleTextView = findViewById(R.id.txtFullTitle)
        fullArtistTextView = findViewById(R.id.txtFullArtist)
        fullArtImageView = findViewById(R.id.imgFullArt)
        fullArtImageView.setOnLongClickListener { v ->
            triggerHapticFeedback(v)
            downloadImageFromImageView(fullArtImageView, fullTitleTextView.text.toString())
            true
        }
        fullPlayButton = findViewById(R.id.btnFullPlay)
        seekBarView = findViewById(R.id.seekBar)
        speedButton = findViewById(R.id.btnSpeed)
        setupSpeedControls()

        currentTimeTextView = findViewById(R.id.txtCurrentTime)
        totalTimeTextView = findViewById(R.id.txtTotalTime)
        searchEditText = findViewById(R.id.editSearch)
        audioVisualizerView = findViewById(R.id.visualizerView)
        particleView = findViewById(R.id.particleView)
        circularProgress = findViewById(R.id.circularProgress)

        topBarContainer = findViewById(R.id.topBar)
        selectionBarContainer = findViewById(R.id.selectionBar)
        selectionCountTextView = findViewById(R.id.txtSelectionCount)
        deleteSelectionButton = findViewById(R.id.btnDeleteSelection)

        setupSelectionControls()
        setupPlaybackControls()
        setupFullPlayerGestures()
        setupNavigationAndSearch()

        findViewById<View>(R.id.btnCreatePlaylist).setOnClickListener { view ->
            triggerHapticFeedback(view)
            showCreatePlaylistDialog { newPlaylist ->
                filterData(searchEditText.text.toString())
                activePlaylistForImage = newPlaylist
                imagePickerLauncher.launch(arrayOf("image/*"))
            }
        }

        setupSeekBarListener()
    }

    /** Binds the application version display in the top bar using the tracked AppConfig constant. */
    private fun setupAppVersionDisplay() {
        findViewById<TextView?>(R.id.txtAppVersion)?.text = AppConfig.APP_VERSION
    }

    /** Adds the shuffle-all header control button to the primary library list view. */
    private fun setupLibraryHeader() {
        val libraryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 30, 30, 30)
        }
        val shuffleButton = MaterialButton(this).apply {
            text = "Shuffle All"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(BUTTON_DARK_COLOR)
            cornerRadius = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { v ->
                triggerHapticFeedback(v)
                if (displaySongs.isNotEmpty()) {
                    playAudio(displaySongs.shuffled(), 0)
                }
            }
        }
        libraryHeader.addView(shuffleButton)
        libraryListView.addHeaderView(libraryHeader)
    }

    /** Configures quick cycle click listener and long-click fine-tuning dialog for playback speed. */
    private fun setupSpeedControls() {
        speedButton.setOnClickListener { v ->
            triggerHapticFeedback(v)
            val service = boundService ?: return@setOnClickListener
            var nextSpeed = (floor(service.playbackSpeed * 4.0) / 4.0).toFloat() + 0.25f
            if (nextSpeed > 2.51f) nextSpeed = 0.25f
            service.playbackSpeed = nextSpeed
            speedButton.text = formatSpeed(nextSpeed)
        }
        speedButton.setOnLongClickListener { v ->
            triggerHapticFeedback(v)
            boundService?.let { showFineTuneSpeedDialog(it) }
            true
        }
    }

    /** Formats a playback speed multiplier for button and dialog labels, e.g. "1.25x". */
    private fun formatSpeed(speed: Float): String = String.format(Locale.getDefault(), "%.2fx", speed)

    /**
     * Displays a dialog containing a continuous slider to adjust playback speed precisely.
     *
     * @param service Bound service whose playback speed is being adjusted.
     */
    private fun showFineTuneSpeedDialog(service: AudioService) {
        val matchWidth = LinearLayout.LayoutParams.MATCH_PARENT
        val wrapHeight = LinearLayout.LayoutParams.WRAP_CONTENT

        val speedIndicator = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            text = "Speed: ${formatSpeed(service.playbackSpeed)}"
        }

        val speedBar = SeekBar(this).apply {
            max = 225
            progress = ((service.playbackSpeed - 0.25f) * 100).toInt()
        }
        val resetButton = Button(this).apply {
            text = "Reset"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(BUTTON_DARK_COLOR)
        }
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 30, 0, 0)
            addView(speedBar, LinearLayout.LayoutParams(0, wrapHeight, 1f))
            addView(resetButton)
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            addView(speedIndicator, LinearLayout.LayoutParams(matchWidth, wrapHeight))
            addView(rowLayout, LinearLayout.LayoutParams(matchWidth, wrapHeight))
        }

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val newSpeed = 0.25f + progress / 100f
                speedButton.text = formatSpeed(newSpeed)
                speedIndicator.text = "Speed: ${formatSpeed(newSpeed)}"
                service.playbackSpeed = newSpeed
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        resetButton.setOnClickListener { speedBar.progress = 75 }

        AlertDialog.Builder(this)
            .setTitle("Fine-tune Speed")
            .setView(rootLayout)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Binds action listeners to multi-selection mode toolbar buttons. */
    private fun setupSelectionControls() {
        findViewById<View>(R.id.btnCancelSelection).setOnClickListener { view ->
            triggerHapticFeedback(view)
            clearSelection()
        }
        findViewById<View>(R.id.btnAddSelection).setOnClickListener { view ->
            triggerHapticFeedback(view)
            showBatchAddToPlaylistDialog()
        }
        deleteSelectionButton.setOnClickListener { view ->
            triggerHapticFeedback(view)
            batchDeleteFromPlaylist()
        }
    }

    /** Binds transport controls and full player sheet expand/collapse listeners. */
    private fun setupPlaybackControls() {
        findViewById<View>(R.id.btnNext).setOnClickListener { view ->
            triggerHapticFeedback(view)
            boundService?.playNext()
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener { view ->
            triggerHapticFeedback(view)
            boundService?.playPrev()
        }
        val togglePlayPause = View.OnClickListener { view ->
            triggerHapticFeedback(view)
            boundService?.togglePlayPause()
        }
        miniPlayButton.setOnClickListener(togglePlayPause)
        fullPlayButton.setOnClickListener(togglePlayPause)
        bottomPlayerContainer.setOnClickListener { view ->
            triggerHapticFeedback(view)
            fullPlayerScreenContainer.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.btnCollapsePlayer).setOnClickListener { view ->
            triggerHapticFeedback(view)
            fullPlayerScreenContainer.visibility = View.GONE
        }
    }

    /** True while the device is held in landscape orientation. */
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Registers double-tap and tap gestures on full player container in landscape orientation. */
    private fun setupFullPlayerGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLandscape) return false
                boundService?.togglePlayPause()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLandscape) return false
                // Left half skips back, right half skips forward
                if (e.x < fullPlayerScreenContainer.width / 2.0f) {
                    boundService?.playPrev()
                } else {
                    boundService?.playNext()
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        fullPlayerScreenContainer.setOnTouchListener { _, event ->
            if (isLandscape) {
                gestureDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }
    }

    /** Sets up bottom navigation tab switching, search input listeners, and sorting button. */
    private fun setupNavigationAndSearch() {
        findViewById<View>(R.id.btnSettings).setOnClickListener { view ->
            triggerHapticFeedback(view)
            showMainSettingsDialog()
        }

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNavigationView.setOnItemSelectedListener { menuItem ->
            triggerHapticFeedback(bottomNavigationView)
            fullPlayerScreenContainer.visibility = View.GONE
            albumsGridView.visibility = View.GONE
            libraryListView.visibility = View.GONE
            playlistsPageContainer.visibility = View.GONE
            expandedDetailsContainer.visibility = View.GONE
            clearSelection()
            currentOpenPlaylist = null

            tabSearchStates[currentTabId] = searchEditText.text.toString()
            val itemId = menuItem.itemId
            currentTabId = itemId

            when (itemId) {
                R.id.nav_albums -> albumsGridView.visibility = View.VISIBLE
                R.id.nav_library -> libraryListView.visibility = View.VISIBLE
                R.id.nav_playlists -> playlistsPageContainer.visibility = View.VISIBLE
            }

            searchEditText.setText(tabSearchStates[itemId] ?: "")
            true
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(query: CharSequence, start: Int, before: Int, count: Int) {
                filterData(query.toString())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<View>(R.id.btnSort).setOnClickListener { view ->
            triggerHapticFeedback(view)
            AlertDialog.Builder(this)
                .setTitle("Sort")
                .setItems(arrayOf("A-Z", "Z-A", "Newest")) { _, which ->
                    sortData(which)
                    filterData(searchEditText.text.toString())
                }
                .show()
        }
    }

    /** Binds progress changes on the full player seek bar to AudioService seek operations. */
    private fun setupSeekBarListener() {
        seekBarView.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val service = boundService
                if (!fromUser || service == null) return
                service.seekTo(progress)
                currentTimeTextView.text = formatTime(progress)
                if (service.duration > 0) {
                    circularProgress?.progress = progress.toFloat() / service.duration
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    /**
     * Initializes the system Visualizer object using the bound AudioService session ID.
     * Binds FFT capture listeners to pipe frequency data to the visualizer wave view.
     */
    private fun setupVisualizer() {
        val sessionId = boundService?.audioSessionId ?: return
        if (sessionId == 0) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        audioVisualizer?.let {
            try {
                it.setEnabled(false)
            } catch (ignored: Exception) {
            }
            try {
                it.release()
            } catch (ignored: Exception) {
            }
        }
        audioVisualizer = null

        try {
            // Assigned before configuring so a partial failure still leaves it releasable in onDestroy
            val visualizer = Visualizer(sessionId)
            audioVisualizer = visualizer
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1])
            visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, bytes: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fftData: ByteArray?, samplingRate: Int) {
                    audioVisualizerView.updateVisualizer(fftData)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            visualizer.setEnabled(true)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Service callback invoked when active track shifts. Updates text views, metadata art,
     * reinitializes visualizer attachments, and generates dynamic background color palettes using Android Palette.
     *
     * @param song     The new Song record metadata.
     * @param albumArt Bitmap art retrieved from metadata.
     */
    override fun onTrackChanged(song: Song, albumArt: Bitmap?) {
        miniTitleTextView.text = song.title
        miniArtistTextView.text = song.artist
        fullTitleTextView.text = song.title
        fullArtistTextView.text = song.artist

        setupVisualizer()

        // Feed low-res image bounds directly to prevent dynamic grey image flickering
        loadArtAsync(fullArtImageView, song.path, false, QUALITY_HIGH, albumArt)

        val showVisualizer = sharedPreferences.getBoolean("show_visualizer", true)
        audioVisualizerView.visibility = if (showVisualizer) View.VISIBLE else View.GONE

        if (albumArt != null) {
            miniArtImageView.setImageBitmap(albumArt)

            // Generate an adaptive UI theme gradient matching the track artwork colors
            Palette.from(albumArt).generate { palette ->
                if (palette == null) return@generate
                val vibrantColor = palette.getVibrantColor(Color.WHITE)
                applyAccentColor(vibrantColor)

                if (sharedPreferences.getBoolean("adaptive_bg", true)) {
                    val dominantColor = palette.getDominantColor(0xFF111111.toInt())
                    val darkMutedColor = palette.getDarkMutedColor(Color.BLACK)
                    fullPlayerScreenContainer.background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(dominantColor, darkMutedColor, Color.BLACK)
                    )
                } else {
                    fullPlayerScreenContainer.setBackgroundColor(Color.BLACK)
                }
            }
        } else {
            loadArtAsync(miniArtImageView, song.path, false, QUALITY_LOW, null)
            applyAccentColor(Color.WHITE)
            fullPlayerScreenContainer.setBackgroundColor(Color.BLACK)
        }

        boundService?.let {
            seekBarView.max = it.duration
            totalTimeTextView.text = formatTime(it.duration)
        }
    }

    /** Tints the seek thumb, visualizer, particles, and progress ring with the track's accent color. */
    private fun applyAccentColor(color: Int) {
        seekBarView.thumb.setTint(color)
        audioVisualizerView.color = color
        particleView?.particleColor = color
        circularProgress?.color = color
    }

    /**
     * Service callback invoked when audio changes playback status (e.g. pauses or starts).
     * Automatically coordinates seekBar updating timers.
     *
     * @param isPlaying True if actively playing, false if paused.
     */
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause_bubbly else R.drawable.ic_play_bubbly
        miniPlayButton.setImageResource(icon)
        fullPlayButton.setImageResource(icon)

        seekHandler.removeCallbacks(updateSeekBarTask)
        if (isPlaying) {
            seekHandler.postDelayed(updateSeekBarTask, 500)
        }
    }

    /**
     * Submits a playback queue and starting track offset to the bound AudioService.
     *
     * @param queue    List of Songs representing the queue.
     * @param position Starting index position.
     */
    private fun playAudio(queue: List<Song>, position: Int) {
        val service = boundService
        if (service != null) {
            service.setQueueAndPlay(queue, position)
        } else {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggles long-press batch action selection modes for songs. Manages selected
     * track state sets and switches layout action toolbars.
     *
     * @param song Song target toggled.
     */
    private fun toggleSelectionMode(song: Song) {
        if (!isSelectionMode) {
            isSelectionMode = true
            topBarContainer.visibility = View.GONE
            selectionBarContainer.visibility = View.VISIBLE
        }

        if (!selectedSongs.remove(song)) {
            selectedSongs.add(song)
        }

        if (selectedSongs.isEmpty()) {
            clearSelection()
        } else {
            selectionCountTextView.text = String.format(Locale.getDefault(), "%d Selected", selectedSongs.size)
            deleteSelectionButton.visibility = if (currentOpenPlaylist != null) View.VISIBLE else View.GONE
            refreshAllAdapters()
        }
    }

    /**
     * Resets active batch choice selections, clearing selection tracking sets and
     * swapping the action toolbar layout back to normal search mode.
     */
    private fun clearSelection() {
        isSelectionMode = false
        selectedSongs.clear()
        selectionBarContainer.visibility = View.GONE
        topBarContainer.visibility = View.VISIBLE
        refreshAllAdapters()
    }

    /** Refreshes view state adapters for all collection lists on screen. */
    private fun refreshAllAdapters() {
        librarySongAdapter?.notifyDataSetChanged()
        detailSongListAdapter?.notifyDataSetChanged()
        albumListAdapter?.notifyDataSetChanged()
        playlistListAdapter?.notifyDataSetChanged()
    }

    /**
     * Displays a dialog prompting the user for a new playlist name and optional description.
     *
     * @param onCreated Callback executed after the new playlist is persisted.
     */
    private fun showCreatePlaylistDialog(onCreated: (Playlist) -> Unit) {
        val nameField = EditText(this).apply { hint = "Playlist Name" }
        val descField = EditText(this).apply { hint = "Playlist Description" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameField)
            addView(descField)
        }

        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val enteredName = nameField.text.toString().trim()
                val newPlaylist = Playlist(enteredName.ifEmpty { "New Playlist" }, null)
                newPlaylist.description = descField.text.toString().trim()
                allPlaylists.add(newPlaylist)
                savePlaylists()
                onCreated(newPlaylist)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Displays a dialog selection list prompting users to assign all currently selected
     * tracks to an existing playlist or create a new playlist with them.
     */
    private fun showBatchAddToPlaylistDialog() {
        if (selectedSongs.isEmpty()) return
        val options = arrayOf("(Create Playlist...)") + allPlaylists.map { it.name }

        AlertDialog.Builder(this)
            .setTitle("Add ${selectedSongs.size} songs to...")
            .setItems(options) { _, which ->
                if (which == 0) {
                    showCreatePlaylistDialog { newPlaylist ->
                        newPlaylist.songs.addAll(selectedSongs)
                        savePlaylists()
                        filterData("")
                        Toast.makeText(this, "Created & Added ${selectedSongs.size} songs!", Toast.LENGTH_SHORT).show()
                        clearSelection()
                    }
                } else {
                    addSelectionToPlaylist(allPlaylists[which - 1])
                }
            }
            .show()
    }

    /**
     * Adds the selected songs to [targetPlaylist], skipping tracks it already contains by ID,
     * then reports the added/skipped counts.
     */
    private fun addSelectionToPlaylist(targetPlaylist: Playlist) {
        var addedCount = 0
        var duplicateCount = 0
        for (selectedSong in selectedSongs) {
            if (targetPlaylist.songs.any { it.id == selectedSong.id }) {
                duplicateCount++
            } else {
                targetPlaylist.songs.add(selectedSong)
                addedCount++
            }
        }
        savePlaylists()
        filterData("")
        var message = "Added $addedCount songs to ${targetPlaylist.name}"
        if (duplicateCount > 0) {
            message += " ($duplicateCount duplicates skipped)"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        clearSelection()
    }

    /**
     * Removes selected songs from the active playlist folder, updates persistent data
     * settings representation, and refreshes list layout adapters.
     */
    private fun batchDeleteFromPlaylist() {
        val playlist = currentOpenPlaylist ?: return
        if (selectedSongs.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Remove ${selectedSongs.size} songs?")
            .setPositiveButton("Yes") { _, _ ->
                val selectedIds = selectedSongs.mapTo(HashSet()) { it.id }
                playlist.songs.removeAll { it.id in selectedIds }
                savePlaylists()
                filterData("")
                clearSelection()
                openDetailView(playlist.name, playlist.songs, true, playlist, null)
            }
            .setNegativeButton("No", null)
            .show()
    }

    /**
     * Instantiates view adapters, assigns adapters to lists/grids on screen,
     * and sets click/long-click selectors.
     */
    private fun setupAdapters() {
        val songAdapter = SongAdapter(displaySongs)
        librarySongAdapter = songAdapter
        libraryListView.adapter = songAdapter

        val albumAdapter = AlbumAdapter()
        albumListAdapter = albumAdapter
        albumsGridView.adapter = albumAdapter
        albumsGridView.setOnItemClickListener { _, view, position, _ ->
            triggerHapticFeedback(view)
            val album = displayAlbums[position]
            openDetailView(album.name, album.songs, false, null, album)
        }
        albumsGridView.setOnItemLongClickListener { _, view, position, _ ->
            triggerHapticFeedback(view)
            showAlbumOptionsDialog(displayAlbums[position], view)
            true
        }

        val playlistAdapter = PlaylistAdapter()
        playlistListAdapter = playlistAdapter
        playlistsGridView.adapter = playlistAdapter

        val dragDirections = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(dragDirections, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition

                // Mirror the move into the master list so the order survives filtering
                val allFrom = allPlaylists.indexOf(displayPlaylists[from])
                val allTo = allPlaylists.indexOf(displayPlaylists[to])
                if (allFrom != -1 && allTo != -1) {
                    allPlaylists.add(allTo, allPlaylists.removeAt(allFrom))
                }
                displayPlaylists.add(to, displayPlaylists.removeAt(from))
                playlistListAdapter?.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                savePlaylists()
            }
        }).attachToRecyclerView(playlistsGridView)

        val detailAdapter = DetailSongAdapter()
        detailSongListAdapter = detailAdapter
        detailSongsListView.adapter = detailAdapter
    }

    /**
     * Shows the long-press action menu for an album grid card.
     *
     * @param album Album the menu applies to.
     * @param itemView Grid card view, used to locate its cover art for downloads.
     */
    private fun showAlbumOptionsDialog(album: Album, itemView: View) {
        val options = arrayOf("Convert to Playlist", "Edit Metadata", "Change Cover Art", "Download Image", "Delete Album")
        AlertDialog.Builder(this)
            .setTitle(album.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val newPlaylist = Playlist(album.name, null)
                        newPlaylist.songs.addAll(album.songs)
                        allPlaylists.add(newPlaylist)
                        savePlaylists()
                        filterData(searchEditText.text.toString())
                        Toast.makeText(this, "Playlist created from Album", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showEditAlbumMetadataDialog(album)
                    2 -> {
                        activeAlbumForImage = album
                        imagePickerLauncher.launch(arrayOf("image/*"))
                    }
                    3 -> itemView.findViewById<ImageView?>(R.id.imgGridArt)?.let { downloadImageFromImageView(it, album.name) }
                    4 -> AlertDialog.Builder(this)
                        .setTitle("Delete Album")
                        .setMessage("Are you sure you want to physically delete all songs in this album?")
                        .setPositiveButton("Yes") { _, _ -> deleteAlbum(album) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    /**
     * Asynchronously decodes and loads artwork bitmaps on a fixed thread pool.
     * Uses cache lookup mechanisms (LruCache) to prevent redundant file/retriever operations
     * and prevents flickering by validating image view tracking tags.
     *
     * @param imageView         Target container to display image.
     * @param artworkPath       Source path (local audio file path or URI string).
     * @param isUri             True if path points to content provider URI or base64 data.
     * @param qualityMode       Sample size scaling divisor factor (QUALITY_LOW/MED/HIGH).
     * @param preloadedFallback Fallback bitmap to show while decoding progresses.
     */
    private fun loadArtAsync(imageView: ImageView, artworkPath: String?, isUri: Boolean, qualityMode: Int, preloadedFallback: Bitmap?) {
        if (artworkPath.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_albums_bubbly)
            return
        }
        val cacheKey = "${artworkPath}_$qualityMode"
        val cachedBitmap = artworkCache.get(cacheKey)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            return
        }

        // The tag lets the async result detect that a recycled view has since moved on to other art
        imageView.tag = artworkPath

        if (preloadedFallback != null) {
            imageView.setImageBitmap(preloadedFallback)
        } else if (imageView !== fullArtImageView && imageView !== miniArtImageView) {
            imageView.setImageResource(R.drawable.ic_albums_bubbly)
        }

        imageExecutor.execute {
            val decodedBitmap = ArtUtil.decodeArtworkBitmap(contentResolver, artworkPath, isUri, qualityMode)
            if (decodedBitmap != null) artworkCache.put(cacheKey, decodedBitmap)
            mainHandler.post {
                if (artworkPath == imageView.tag) {
                    if (decodedBitmap != null) {
                        imageView.setImageBitmap(decodedBitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_albums_bubbly)
                    }
                }
            }
        }
    }

    /** ViewHolder caching references to a song item's subviews to reduce findViewById calls. */
    private class SongViewHolder(itemView: View) {
        val selectionCheckBox: CheckBox = itemView.findViewById(R.id.chkSelect)
        val titleTextView: TextView = itemView.findViewById(R.id.txtTitle)
        val artistTextView: TextView = itemView.findViewById(R.id.txtArtist)
        val artworkImageView: ImageView = itemView.findViewById(R.id.imgArt)
    }

    /**
     * Binds track information, cover art, and interaction listeners to an item_song view.
     *
     * @param position Adapter item index.
     * @param convertView Recycled view instance.
     * @param parent Container view group.
     * @param songs List of songs backing the adapter.
     * @return Bound view hierarchy.
     */
    private fun bindSongItemView(position: Int, convertView: View?, parent: ViewGroup, songs: List<Song>): View {
        val itemView = convertView
            ?: layoutInflater.inflate(R.layout.item_song, parent, false).also { it.tag = SongViewHolder(it) }
        val viewHolder = itemView.tag as SongViewHolder

        val currentSong = songs[position]
        viewHolder.titleTextView.text = currentSong.title
        viewHolder.artistTextView.text = currentSong.artist
        loadArtAsync(viewHolder.artworkImageView, currentSong.path, false, QUALITY_LOW, null)

        if (isSelectionMode) {
            viewHolder.selectionCheckBox.visibility = View.VISIBLE
            viewHolder.selectionCheckBox.isChecked = currentSong in selectedSongs
        } else {
            viewHolder.selectionCheckBox.visibility = View.GONE
        }

        itemView.setOnClickListener { clickedView ->
            triggerHapticFeedback(clickedView)
            if (isSelectionMode) {
                toggleSelectionMode(currentSong)
            } else {
                playAudio(songs, position)
            }
        }
        itemView.setOnLongClickListener { clickedView ->
            triggerHapticFeedback(clickedView)
            if (isSelectionMode) {
                toggleSelectionMode(currentSong)
            } else {
                AlertDialog.Builder(this)
                    .setItems(arrayOf("Select", "Edit Metadata", "Download Image")) { _, which ->
                        when (which) {
                            0 -> toggleSelectionMode(currentSong)
                            1 -> showEditSongMetadataDialog(currentSong)
                            2 -> downloadImageFromImageView(viewHolder.artworkImageView, currentSong.title)
                        }
                    }
                    .show()
            }
            true
        }
        return itemView
    }

    /** Adapter for displaying songs in the primary library list view. */
    private inner class SongAdapter(private val songsList: ArrayList<Song>) :
        ArrayAdapter<Song>(this@MainActivity, R.layout.item_song, songsList) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bindSongItemView(position, convertView, parent, songsList)
    }

    /** Adapter for displaying songs in the expanded details overlay sheet. */
    private inner class DetailSongAdapter : BaseAdapter() {
        override fun getCount(): Int = displayDetailSongs.size

        override fun getItem(position: Int): Any = displayDetailSongs[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bindSongItemView(position, convertView, parent, displayDetailSongs)
    }

    /** Adapter for rendering Album grid card layouts. */
    private inner class AlbumAdapter : BaseAdapter() {
        override fun getCount(): Int = displayAlbums.size

        override fun getItem(position: Int): Any = displayAlbums[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemView = convertView ?: layoutInflater.inflate(R.layout.item_grid, parent, false)
            val currentAlbum = displayAlbums[position]
            itemView.findViewById<TextView>(R.id.txtGridTitle).text = currentAlbum.name
            itemView.findViewById<TextView>(R.id.txtGridSub).text = currentAlbum.artist

            itemView.findViewById<ImageView?>(R.id.imgFireIndicator)?.visibility =
                if (currentAlbum.isFire) View.VISIBLE else View.GONE

            loadArtAsync(
                itemView.findViewById(R.id.imgGridArt),
                currentAlbum.songs.firstOrNull()?.path,
                false,
                QUALITY_MED,
                null
            )
            return itemView
        }
    }

    /** Adapter for rendering Playlist grid card layouts. */
    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(R.id.txtGridTitle)
            val subText: TextView = view.findViewById(R.id.txtGridSub)
            val artImageView: ImageView = view.findViewById(R.id.imgGridArt)
            val fireIndicator: ImageView? = view.findViewById(R.id.imgFireIndicator)

            init {
                view.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        triggerHapticFeedback(view)
                        val playlist = displayPlaylists[pos]
                        openDetailView(playlist.name, playlist.songs, true, playlist, null)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(layoutInflater.inflate(R.layout.item_grid, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val currentPlaylist = displayPlaylists[position]
            holder.titleText.text = currentPlaylist.name
            holder.subText.text = String.format(Locale.getDefault(), "%d songs", currentPlaylist.songs.size)
            holder.fireIndicator?.visibility = if (currentPlaylist.isFire) View.VISIBLE else View.GONE

            val coverUri = currentPlaylist.imageUri
            if (!coverUri.isNullOrEmpty()) {
                loadArtAsync(holder.artImageView, coverUri, true, QUALITY_MED, null)
            } else if (currentPlaylist.songs.isNotEmpty()) {
                loadArtAsync(holder.artImageView, currentPlaylist.songs[0].path, false, QUALITY_MED, null)
            } else {
                holder.artImageView.setBackgroundColor(BUTTON_DARK_COLOR)
                holder.artImageView.setImageResource(R.drawable.ic_albums_bubbly)
            }
        }

        override fun getItemCount(): Int = displayPlaylists.size
    }

    /**
     * Filters lists for songs, albums, and playlists based on text search queries.
     *
     * @param query Search query string input.
     */
    private fun filterData(query: String) {
        MusicLibraryUtil.filterData(query, allSongs, allAlbums, allPlaylists, displaySongs, displayAlbums, displayPlaylists)
        refreshAllAdapters()
    }

    /**
     * Reads shared system external storage files using a ContentResolver.
     * Constructs the local database maps, scans playlists from SharedPreferences,
     * sorts tracks, and updates lists on the UI thread.
     */
    private fun loadMusic() {
        libraryExecutor.execute {
            val albumMap = HashMap<String, Album>()
            val tempSongs = MusicLibraryUtil.queryMediaStoreSongs(contentResolver, fireAlbums, albumMap)

            val tempAlbums = ArrayList(albumMap.values)
            for (album in tempAlbums) {
                album.songs.sortBy { it.trackNumber }
            }

            val songIdMap = tempSongs.associateBy { it.id }
            val songNameMap = tempSongs.associateBy { "${it.title}_${it.artist}".lowercase(Locale.getDefault()) }
            val tempPlaylists = PlaylistUtil.parsePlaylists(sharedPreferences, songIdMap, songNameMap)

            tempSongs.sortWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            tempAlbums.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }

            runOnUiThread {
                allSongs.clear()
                allSongs.addAll(tempSongs)
                allAlbums.clear()
                allAlbums.addAll(tempAlbums)
                allPlaylists.clear()
                allPlaylists.addAll(tempPlaylists)

                filterData(searchEditText.text.toString())
            }
        }
    }

    /** Serializes playlist collection structures to persistent SharedPreferences storage. */
    private fun savePlaylists() {
        PlaylistUtil.savePlaylists(sharedPreferences, allPlaylists)
    }

    /**
     * Sorts song and album datasets alphabetically or by add date.
     *
     * @param sortType Sort selector key index (0: A-Z, 1: Z-A, 2: Newest).
     */
    private fun sortData(sortType: Int) {
        MusicLibraryUtil.sortData(sortType, allSongs, allAlbums)
    }

    /**
     * Animates and reveals the detailed tracks list view for an album or playlist.
     *
     * @param viewTitle      Heading title text to display (e.g. Playlist or Album name).
     * @param songsList      List of contained track Song objects.
     * @param isPlaylist     Boolean specifying if this is a playlist or album details view.
     * @param playlistObject Associated playlist metadata wrapper if applicable.
     * @param albumObject    Associated album metadata wrapper if applicable.
     */
    private fun openDetailView(viewTitle: String, songsList: List<Song>, isPlaylist: Boolean, playlistObject: Playlist?, albumObject: Album?) {
        expandedDetailsContainer.visibility = View.VISIBLE
        detailTitleTextView.text = viewTitle
        currentOpenPlaylist = if (isPlaylist) playlistObject else null

        val btnDetailOptions = findViewById<ImageView>(R.id.btnDetailOptions)
        val txtDetailDescription = findViewById<TextView>(R.id.txtDetailDescription)
        if (isPlaylist && playlistObject != null) {
            btnDetailOptions.visibility = View.VISIBLE
            if (playlistObject.description.isNotEmpty()) {
                txtDetailDescription.visibility = View.VISIBLE
                txtDetailDescription.text = playlistObject.description
            } else {
                txtDetailDescription.visibility = View.GONE
            }
            btnDetailOptions.setOnClickListener { v ->
                triggerHapticFeedback(v)
                showPlaylistDetailOptionsDialog(playlistObject, txtDetailDescription)
            }
        } else {
            btnDetailOptions.visibility = View.GONE
            txtDetailDescription.visibility = View.GONE
        }

        val playlistCover = if (isPlaylist) playlistObject?.imageUri else null
        if (!playlistCover.isNullOrBlank()) {
            loadArtAsync(detailCoverImageView, playlistCover, true, QUALITY_HIGH, null)
        } else if (songsList.isNotEmpty()) {
            loadArtAsync(detailCoverImageView, songsList[0].path, false, QUALITY_HIGH, null)
        } else {
            detailCoverImageView.setImageResource(R.drawable.ic_albums_bubbly)
        }

        displayDetailSongs.clear()
        displayDetailSongs.addAll(songsList)
        detailSongListAdapter?.notifyDataSetChanged()

        findViewById<View>(R.id.btnDetailPlayAll).setOnClickListener { view ->
            triggerHapticFeedback(view)
            if (songsList.isNotEmpty()) {
                playAudio(ArrayList(songsList), 0)
            }
        }
        findViewById<View>(R.id.btnDetailShuffle).setOnClickListener { view ->
            triggerHapticFeedback(view)
            if (songsList.isNotEmpty()) {
                playAudio(songsList.shuffled(), 0)
            }
        }

        setupDetailFireToggle(findViewById(R.id.btnDetailFireToggle), isPlaylist, playlistObject, albumObject)

        if (isSelectionMode) {
            deleteSelectionButton.visibility = if (currentOpenPlaylist != null) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        // Image quality modes: BitmapFactory sample-size divisors
        const val QUALITY_LOW = 8
        const val QUALITY_MED = 2
        const val QUALITY_HIGH = 1

        /** Dark grey used for secondary buttons and empty-cover placeholders. */
        val BUTTON_DARK_COLOR = 0xFF333333.toInt()

        /** Bitmap cache sized to 25% of the runtime's max heap; shared across activity instances. */
        val artworkCache: LruCache<String, Bitmap> by lazy {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            object : LruCache<String, Bitmap>(maxMemoryKb / 4) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
            }
        }
    }

    /**
     * Displays a dialog containing management options for an active playlist in detail view.
     *
     * @param playlist Target playlist to manage.
     * @param txtDetailDescription View displaying playlist description text.
     */
    private fun showPlaylistDetailOptionsDialog(playlist: Playlist, txtDetailDescription: TextView) {
        val options = arrayOf("Edit Details", "Change Cover", "Download Image", "Delete")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPlaylistDialog(playlist, txtDetailDescription)
                    1 -> {
                        activePlaylistForImage = playlist
                        imagePickerLauncher.launch(arrayOf("image/*"))
                    }
                    2 -> downloadImageFromImageView(detailCoverImageView, playlist.name)
                    3 -> {
                        allPlaylists.remove(playlist)
                        savePlaylists()
                        filterData(searchEditText.text.toString())
                        expandedDetailsContainer.visibility = View.GONE
                    }
                }
            }.show()
    }

    /**
     * Displays input dialog to update playlist title and description metadata.
     *
     * @param playlist Target playlist to edit.
     * @param txtDetailDescription View displaying playlist description text.
     */
    private fun showEditPlaylistDialog(playlist: Playlist, txtDetailDescription: TextView) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameField = EditText(this).apply {
            hint = "Playlist Name"
            setText(playlist.name)
        }
        val descField = EditText(this).apply {
            hint = "Playlist Description"
            setText(playlist.description)
        }
        layout.addView(nameField)
        layout.addView(descField)

        AlertDialog.Builder(this)
            .setTitle("Edit Details")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                playlist.name = nameField.text.toString()
                playlist.description = descField.text.toString()
                detailTitleTextView.text = playlist.name

                if (playlist.description.isNotEmpty()) {
                    txtDetailDescription.visibility = View.VISIBLE
                    txtDetailDescription.text = playlist.description
                } else {
                    txtDetailDescription.visibility = View.GONE
                }

                savePlaylists()
                filterData(searchEditText.text.toString())
            }.show()
    }

    /**
     * Binds click handling and visual feedback animation to the detail view favorite/fire toggle.
     *
     * @param fireToggle Target fire toggle button.
     * @param isPlaylist True if the details sheet represents a playlist.
     * @param playlist Associated playlist if applicable.
     * @param album Associated album if applicable.
     */
    private fun setupDetailFireToggle(fireToggle: ImageButton, isPlaylist: Boolean, playlist: Playlist?, album: Album?) {
        val isFire = if (isPlaylist && playlist != null) playlist.isFire else (album != null && album.isFire)
        fireToggle.setColorFilter(if (isFire) Color.parseColor("#FF9800") else Color.WHITE)

        fireToggle.setOnClickListener { v ->
            triggerHapticFeedback(v)
            var newFire = false
            if (isPlaylist && playlist != null) {
                playlist.isFire = !playlist.isFire
                newFire = playlist.isFire
            } else if (!isPlaylist && album != null) {
                album.isFire = !album.isFire
                newFire = album.isFire
                if (newFire) fireAlbums.add(album.albumId) else fireAlbums.remove(album.albumId)
                sharedPreferences.edit().putStringSet("fireAlbums", fireAlbums).apply()
            }
            savePlaylists()
            fireToggle.setColorFilter(if (newFire) Color.parseColor("#FF9800") else Color.WHITE)
            v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                sortData(sharedPreferences.getInt("sort_type", 0))
                filterData(searchEditText.text.toString())
            }.start()
        }
    }

    /**
     * Queries and requests OS access permissions needed for media retrieval,
     * notification dispatches, and audio recording (required for the visualizer FFT API).
     */
    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO
            )
        }
        ActivityCompat.requestPermissions(this, permissions, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadMusic()
    }

    /**
     * Converts raw millisecond values to standard MM:SS time string formats.
     *
     * @param positionMs Position value in milliseconds.
     * @return           Formatted time string.
     */
    private fun formatTime(positionMs: Int): String {
        val hours = positionMs / (1000 * 60 * 60)
        val minutes = (positionMs / (1000 * 60)) % 60
        val seconds = (positionMs / 1000) % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Downloads and exports the artwork bitmap displayed in an ImageView to user gallery.
     *
     * @param view ImageView holding the artwork tag.
     * @param title Title used for naming the image file.
     */
    private fun downloadImageFromImageView(view: ImageView, title: String) {
        val artworkPath = view.tag as? String
        if (artworkPath.isNullOrEmpty()) {
            Toast.makeText(this, "No image available to download", Toast.LENGTH_SHORT).show()
            return
        }

        imageExecutor.execute {
            val isUri = artworkPath.startsWith("content://") || artworkPath.startsWith("data:image/")
            val decodedBitmap = ArtUtil.decodeArtworkBitmap(contentResolver, artworkPath, isUri, 1)
            if (decodedBitmap == null) {
                mainHandler.post { Toast.makeText(this, "No valid high-res image found", Toast.LENGTH_SHORT).show() }
                return@execute
            }

            val saved = ArtUtil.saveBitmapToGallery(contentResolver, decodedBitmap, title)
            mainHandler.post {
                Toast.makeText(this, if (saved) "Image saved to Pictures!" else "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Configures document picker contracts and activity launchers for choosing custom playlist cover images,
     * exporting playlist backups, and restoring backups.
     */
    private fun setupLaunchers() {
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { documentUri ->
            handleImagePickerResult(documentUri)
        }
        backupFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { documentUri ->
            PlaylistUtil.exportBackup(this, sharedPreferences, documentUri)
        }
        restoreFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { documentUri ->
            PlaylistUtil.restoreBackup(this, sharedPreferences, documentUri) { loadMusic() }
        }
    }

    /**
     * Updates active playlist or album cover image reference with the selected document URI.
     *
     * @param documentUri Selected image document URI.
     */
    private fun handleImagePickerResult(documentUri: Uri?) {
        if (documentUri == null) return
        try {
            contentResolver.takePersistableUriPermission(documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
        }

        val playlist = activePlaylistForImage
        val album = activeAlbumForImage
        if (playlist != null) {
            playlist.imageUri = documentUri.toString()
            savePlaylists()
            filterData("")
        } else if (album != null) {
            updateAlbumArt(album, documentUri)
        }
    }

    /**
     * Displays a text dialog input window for configuring the Raspberry Pi sync server IP destination.
     */
    private fun showSetIpDialog() {
        val inputField = EditText(this)
        inputField.setText(sharedPreferences.getString("sync_server_url", ""))
        AlertDialog.Builder(this)
            .setTitle("Sync Server URL")
            .setView(inputField)
            .setPositiveButton("Save") { _, _ ->
                val url = inputField.text.toString().trim()
                sharedPreferences.edit().putString("sync_server_url", url).apply()
                Toast.makeText(this, "Server URL Saved!", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private var syncProgressDialog: AlertDialog? = null
    private var syncStatusTextView: TextView? = null
    private var syncProgressBar: ProgressBar? = null

    /**
     * Coordinates the background SyncManager execution process, revealing progress updates
     * and handling server network connection results inside dialog elements.
     */
    @Suppress("DEPRECATION")
    private fun runSync() {
        val serverUrl = sharedPreferences.getString("sync_server_url", "") ?: ""
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please set server IP first!", Toast.LENGTH_SHORT).show()
            return
        }

        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val statusView = TextView(this).apply {
            text = "Preparing synchronization..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }
        syncStatusTextView = statusView
        layout.addView(statusView)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (15 * density).toInt() }
            indeterminateDrawable?.setColorFilter(0xFFa084dc.toInt(), PorterDuff.Mode.SRC_IN)
            progressDrawable?.setColorFilter(0xFFa084dc.toInt(), PorterDuff.Mode.SRC_IN)
        }
        syncProgressBar = progressBar
        layout.addView(progressBar)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Library Sync")
            .setView(layout)
            .setCancelable(false)
            .create()
        syncProgressDialog = dialog
        dialog.show()

        SyncManager.startSync(this, serverUrl, allSongs, object : SyncManager.SyncCallback {
            override fun onProgress(progress: Int, max: Int, message: String) {
                runOnUiThread {
                    if (syncProgressDialog?.isShowing == true) {
                        syncStatusTextView?.text = message
                        syncProgressBar?.let { bar ->
                            if (max > 0) {
                                bar.isIndeterminate = false
                                bar.max = max
                                bar.progress = progress
                            } else {
                                bar.isIndeterminate = true
                            }
                        }
                    }
                }
            }

            override fun onComplete(result: String) {
                runOnUiThread {
                    if (syncProgressDialog?.isShowing == true) syncProgressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Sync Complete: $result", Toast.LENGTH_LONG).show()
                    loadMusic()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    if (syncProgressDialog?.isShowing == true) syncProgressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Sync Error: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    /** Persists whether the full player sheet is expanded so it survives rotation. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isPlayerExpanded", fullPlayerScreenContainer.visibility == View.VISIBLE)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean("isPlayerExpanded", false)) {
            fullPlayerScreenContainer.visibility = View.VISIBLE
        }
    }

    /**
     * Releases active visualizers, terminates bound service connections,
     * and shuts down image loading thread executors.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        audioVisualizer?.let {
            try {
                it.setEnabled(false)
            } catch (ignored: Exception) {
            }
            it.release()
        }
        audioVisualizer = null
        imageExecutor.shutdown()
        libraryExecutor.shutdown()
        seekHandler.removeCallbacks(updateSeekBarTask)
    }

    /**
     * Displays the parent settings list dialog window.
     */
    private fun showMainSettingsDialog() {
        val mainOptions = arrayOf(
            "Sync & Server Settings",
            "Visualizer & Display Options",
            "Backup & Portability Profiles",
            "Audio & Equalizer Options"
        )

        AlertDialog.Builder(this)
            .setTitle("VibeStation Settings")
            .setItems(mainOptions) { _, which ->
                when (which) {
                    0 -> showSyncSettingsDialog()
                    1 -> showVisualSettingsDialog()
                    2 -> showBackupSettingsDialog()
                    3 -> showEqualizerSettingsDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Displays the sync and server settings dialog options.
     */
    private fun showSyncSettingsDialog() {
        val syncOptions = arrayOf(
            "Sync Now (Raspberry Pi)",
            "Set Sync Server IP Address",
            "Copy Web URL"
        )

        AlertDialog.Builder(this)
            .setTitle("Sync & Server")
            .setItems(syncOptions) { _, which ->
                when (which) {
                    0 -> runSync()
                    1 -> showSetIpDialog()
                    2 -> {
                        val serverUrl = sharedPreferences.getString("sync_server_url", "") ?: ""
                        if (serverUrl.isNotEmpty()) {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Web URL", serverUrl)
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this, "Copied URL: $serverUrl", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "Please set server IP first!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showMainSettingsDialog() }
            .show()
    }

    /**
     * Displays the visualizer display and hardware refresh rate toggle configurations.
     */
    private fun showVisualSettingsDialog() {
        val is120 = sharedPreferences.getBoolean("120hz", true)
        val adaptiveBg = sharedPreferences.getBoolean("adaptive_bg", true)
        val showVis = sharedPreferences.getBoolean("show_visualizer", true)

        val visualOptions = arrayOf(
            if (showVis) "Disable Visualizer Wave" else "Enable Visualizer Wave",
            if (adaptiveBg) "Disable Adaptive Background" else "Enable Adaptive Background",
            if (is120) "Disable 120Hz Refresh Rate" else "Enable 120Hz Refresh Rate"
        )

        AlertDialog.Builder(this)
            .setTitle("Visualizer & Display")
            .setItems(visualOptions) { _, which ->
                when (which) {
                    0 -> {
                        val newValue = !showVis
                        sharedPreferences.edit().putBoolean("show_visualizer", newValue).apply()
                        audioVisualizerView.visibility = if (newValue) View.VISIBLE else View.GONE
                        Toast.makeText(this, "Visualizer " + if (newValue) "Enabled" else "Disabled", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val newValue = !adaptiveBg
                        sharedPreferences.edit().putBoolean("adaptive_bg", newValue).apply()
                        Toast.makeText(this, "Adaptive Background " + if (newValue) "Enabled" else "Disabled", Toast.LENGTH_SHORT).show()
                        val art = audioService?.currentArt
                        val song = audioService?.currentSong
                        if (art != null && song != null) {
                            onTrackChanged(song, art)
                        }
                    }
                    2 -> {
                        val newValue = !is120
                        sharedPreferences.edit().putBoolean("120hz", newValue).apply()
                        applyRefreshRate(newValue)
                        Toast.makeText(this, "120Hz " + if (newValue) "Enabled" else "Disabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Back") { _, _ -> showMainSettingsDialog() }
            .show()
    }

    /**
     * Displays import/export profile backup action dialog selections.
     */
    private fun showBackupSettingsDialog() {
        val backupOptions = arrayOf(
            "Export Playlists Backup",
            "Import Playlists Backup"
        )

        AlertDialog.Builder(this)
            .setTitle("Backup Profiles")
            .setItems(backupOptions) { _, which ->
                when (which) {
                    0 -> backupFileLauncher.launch("VibeStation_Backup.txt")
                    1 -> restoreFileLauncher.launch(arrayOf("text/plain"))
                }
            }
            .setNegativeButton("Back") { _, _ -> showMainSettingsDialog() }
            .show()
    }

    /**
     * Displays the equalizer settings dialog, allowing users to adjust frequency bands.
     */
    private fun showEqualizerSettingsDialog() {
        val equalizer = audioService?.equalizer
        if (equalizer == null) {
            Toast.makeText(this, "Equalizer not supported or audio not ready.", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_equalizer, null)

        val switchEqualizer = view.findViewById<SwitchCompat>(R.id.switchEqualizer)
        val bandsContainer = view.findViewById<LinearLayout>(R.id.equalizerBandsContainer)
        val btnReset = view.findViewById<Button>(R.id.btnResetEqualizer)
        val btnClose = view.findViewById<Button>(R.id.btnCloseEqualizer)

        val isEnabled = sharedPreferences.getBoolean("eq_enabled", false)
        try {
            equalizer.setEnabled(isEnabled)
        } catch (e: Exception) {
            // Ignore if setting enable fails
        }
        switchEqualizer.isChecked = isEnabled

        switchEqualizer.setOnCheckedChangeListener { _, isChecked ->
            try {
                equalizer.setEnabled(isChecked)
                sharedPreferences.edit().putBoolean("eq_enabled", isChecked).apply()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to toggle equalizer.", Toast.LENGTH_SHORT).show()
            }
        }

        val bandCount = equalizer.numberOfBands.toInt()
        val minEQLevel = equalizer.bandLevelRange[0].toInt()
        val maxEQLevel = equalizer.bandLevelRange[1].toInt()

        for (index in 0 until bandCount) {
            val band = index.toShort()

            val freqTextView = TextView(this).apply {
                text = "${equalizer.getCenterFreq(band) / 1000} Hz"
                setTextColor(Color.WHITE)
                setPadding(0, 16, 0, 0)
            }

            val seekBar = SeekBar(this)
            seekBar.max = maxEQLevel - minEQLevel

            var savedLevel = sharedPreferences.getInt("eq_band_$band", equalizer.getBandLevel(band).toInt())
            try {
                equalizer.setBandLevel(band, savedLevel.toShort())
            } catch (e: Exception) {
                savedLevel = equalizer.getBandLevel(band).toInt()
            }
            seekBar.progress = savedLevel - minEQLevel

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newLevel = (progress + minEQLevel).toShort()
                        try {
                            equalizer.setBandLevel(band, newLevel)
                            sharedPreferences.edit().putInt("eq_band_$band", newLevel.toInt()).apply()
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })

            bandsContainer.addView(freqTextView)
            bandsContainer.addView(seekBar)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnReset.setOnClickListener {
            for (index in 0 until bandCount) {
                try {
                    equalizer.setBandLevel(index.toShort(), 0.toShort())
                    sharedPreferences.edit().putInt("eq_band_$index", 0).apply()
                } catch (e: Exception) {
                    // Ignored
                }
            }
            dialog.dismiss()
            showEqualizerSettingsDialog()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
            showMainSettingsDialog()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /**
     * Checks if the app has MANAGE_EXTERNAL_STORAGE permission on Android 11+.
     * If not, prompts the user to grant it.
     *
     * @return True if granted or SDK < R, false otherwise.
     */
    private fun checkManageStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            return false
        }
        return true
    }

    /**
     * Shows a dialog to edit a song's metadata.
     *
     * @param song The song to edit.
     */
    private fun showEditSongMetadataDialog(song: Song) {
        if (!checkManageStoragePermission()) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val titleInput = EditText(this).apply {
            hint = "Song Title"
            setText(song.title)
        }
        layout.addView(titleInput)

        val artistInput = EditText(this).apply {
            hint = "Artist"
            setText(song.artist)
        }
        layout.addView(artistInput)

        val albumInput = EditText(this).apply {
            hint = "Album Name"
            setText(song.album)
        }
        layout.addView(albumInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Song Metadata")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = titleInput.text.toString()
                val newArtist = artistInput.text.toString()
                val newAlbum = albumInput.text.toString()

                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("This will permanently overwrite the metadata on the physical file. Are you sure?")
                    .setPositiveButton("Yes") { _, _ -> updateSongMetadata(song, newTitle, newArtist, newAlbum) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a dialog to edit an album's metadata.
     *
     * @param album The album to edit.
     */
    private fun showEditAlbumMetadataDialog(album: Album) {
        if (!checkManageStoragePermission()) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val albumInput = EditText(this).apply {
            hint = "Album Name"
            setText(album.name)
        }
        layout.addView(albumInput)

        val artistInput = EditText(this).apply {
            hint = "Album Artist"
            setText(album.artist)
        }
        layout.addView(artistInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Album Metadata")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newAlbum = albumInput.text.toString()
                val newArtist = artistInput.text.toString()

                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("This will permanently overwrite the metadata on all physical files in this album. Are you sure?")
                    .setPositiveButton("Yes") { _, _ -> updateAlbumMetadata(album, newAlbum, newArtist) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Updates physical file ID3 tags for a song using jaudiotagger and refreshes MediaStore.
     *
     * @param song The song model to update.
     * @param newTitle The new song title.
     * @param newArtist The new artist name.
     * @param newAlbum The new album name.
     */
    private fun updateSongMetadata(song: Song, newTitle: String, newArtist: String, newAlbum: String) {
        MediaMetadataUtil.updateSongMetadata(this, song, newTitle, newArtist, newAlbum) { loadMusic() }
    }

    /**
     * Updates physical file ID3 tags for all songs in an album using jaudiotagger.
     *
     * @param album The album model to update.
     * @param newAlbum The new album name.
     * @param newArtist The new artist name.
     */
    private fun updateAlbumMetadata(album: Album, newAlbum: String, newArtist: String) {
        MediaMetadataUtil.updateAlbumMetadata(this, album, newAlbum, newArtist) { loadMusic() }
    }

    /**
     * Updates physical file ID3 artwork tags for all songs in an album.
     *
     * @param album The album model to update.
     * @param imageUri The new image URI.
     */
    private fun updateAlbumArt(album: Album, imageUri: Uri) {
        activeAlbumForImage = null
        MediaMetadataUtil.updateAlbumArt(this, album, imageUri) { loadMusic() }
    }

    /**
     * Deletes all physical files associated with an album and triggers a MediaStore scan.
     *
     * @param album The album model to delete.
     */
    private fun deleteAlbum(album: Album) {
        MediaMetadataUtil.deleteAlbum(this, album) { loadMusic() }
    }
}
