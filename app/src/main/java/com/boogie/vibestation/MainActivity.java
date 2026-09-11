package com.boogie.vibestation;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import androidx.appcompat.widget.SwitchCompat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.content.res.Configuration;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.PorterDuff;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.collection.LruCache;
import androidx.palette.graphics.Palette;
import android.provider.Settings;
import android.os.Environment;
import java.io.File;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.boogie.vibestation.models.Album;
import com.boogie.vibestation.models.Playlist;
import com.boogie.vibestation.models.Song;
import com.boogie.vibestation.util.ArtUtil;
import com.boogie.vibestation.util.MediaMetadataUtil;
import com.boogie.vibestation.util.MusicLibraryUtil;
import com.boogie.vibestation.util.PlaylistUtil;
import com.boogie.vibestation.views.CircularProgressView;
import com.boogie.vibestation.views.ParticleView;
import com.boogie.vibestation.views.VisualizerView;

/**
 * Main Activity for VibeStation. Coordinates UI, media lists (songs, albums, playlists),
 * binds to the background AudioService, manages the visualizer view, handles search querying,
 * handles runtime permissions, and imports/exports playlists.
 */
public class MainActivity extends AppCompatActivity implements AudioService.ServiceCallback {

    // Audio Playback Content Lists
    private final ArrayList<Song> allSongs = new ArrayList<>();
    private final ArrayList<Album> allAlbums = new ArrayList<>();
    private final ArrayList<Playlist> allPlaylists = new ArrayList<>();
    private final ArrayList<Song> displaySongs = new ArrayList<>();
    private final ArrayList<Album> displayAlbums = new ArrayList<>();
    private final ArrayList<Playlist> displayPlaylists = new ArrayList<>();
    private final ArrayList<Song> displayDetailSongs = new ArrayList<>();

    // Playlist Target References
    private Playlist activePlaylistForImage;
    private Album activeAlbumForImage;
    private Playlist currentOpenPlaylist;

    // Selection Queue
    private boolean isSelectionMode = false;
    private final HashSet<Song> selectedSongs = new HashSet<>();

    // UI Widgets
    private GridView albumsGridView;
    private RecyclerView playlistsGridView;
    private ListView libraryListView;
    private ListView detailSongsListView;
    
    private View playlistsPageContainer;
    private View bottomPlayerContainer;
    private View fullPlayerScreenContainer;
    private View expandedDetailsContainer;
    private View topBarContainer;
    private View selectionBarContainer;

    private TextView selectionCountTextView;
    private TextView detailTitleTextView;
    private TextView miniTitleTextView;
    private TextView miniArtistTextView;
    private TextView fullTitleTextView;
    private TextView fullArtistTextView;
    private TextView currentTimeTextView;
    private TextView totalTimeTextView;

    private ImageView miniArtImageView;
    private ImageView fullArtImageView;
    private ImageView detailCoverImageView;

    private ImageButton miniPlayButton;
    private ImageButton fullPlayButton;
    private ImageButton deleteSelectionButton;
    private android.widget.Button speedButton;

    private SeekBar seekBarView;
    private EditText searchEditText;
    private java.util.Map<Integer, String> tabSearchStates = new java.util.HashMap<>();
    private int currentTabId = R.id.nav_albums;
    private VisualizerView audioVisualizerView;
    private ParticleView particleView;
    private CircularProgressView circularProgress;
    private java.util.HashSet<String> fireAlbums = new java.util.HashSet<>();

    // View Adapters
    private SongAdapter librarySongAdapter;
    private AlbumAdapter albumListAdapter;
    private PlaylistAdapter playlistListAdapter;
    private DetailSongAdapter detailSongListAdapter;

    // Service Management
    private AudioService audioService;
    private boolean isBound = false;

    // Async & Cache Management
    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private SharedPreferences sharedPreferences;
    private static LruCache<String, Bitmap> artworkCache;
    private Visualizer audioVisualizer;

    // File / Image Launchers
    private ActivityResultLauncher<String[]> imagePickerLauncher;
    private ActivityResultLauncher<String> backupFileLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;

    // Image Quality Modes
    private static final int QUALITY_LOW = 8;
    private static final int QUALITY_MED = 2;
    private static final int QUALITY_HIGH = 1;

    /**
     * Connection handler for the bound AudioService, enabling callback registration and
     * initial state synchronization when connection is established.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        /**
         * Triggered when binding succeeds. Retrieves the service binder, registers callbacks,
         * and initializes playback controls if a track is already active.
         */
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            AudioService.LocalBinder binder = (AudioService.LocalBinder) service;
            audioService = binder.getService();
            audioService.setCallback(MainActivity.this);
            isBound = true;

            if (audioService.getCurrentSong() != null) {
                onTrackChanged(audioService.getCurrentSong(), audioService.getCurrentArt());
                onPlaybackStateChanged(audioService.isPlaying());
            }
            if (speedButton != null) {
                speedButton.setText(String.format(java.util.Locale.getDefault(), "%.2fx", audioService.getPlaybackSpeed()));
            }
        }

        /**
         * Triggered if the service connection is unexpectedly lost.
         */
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    /**
     * Initializes activity layouts, sets up LruCache for artwork, configures views,
     * registers activity launchers, starts/binds AudioService, registers the back-button handler,
     * and triggers permission checks.
     *
     * @param savedInstanceState Saved instance state bundle.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            WindowInsetsControllerCompat windowInsetsController =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Configure dynamic bitmap cache based on runtime hardware memory (allocating 25% of memory)
        if (artworkCache == null) {
            final int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            artworkCache = new LruCache<String, Bitmap>(maxMemoryKb / 4) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };
        }

        sharedPreferences = getSharedPreferences("RetroPrefs", MODE_PRIVATE);
        fireAlbums = new java.util.HashSet<>(sharedPreferences.getStringSet("fireAlbums", new java.util.HashSet<>()));
        applyRefreshRate(sharedPreferences.getBoolean("120hz", true));

        setupViews();
        setupAdapters();
        setupLaunchers();

        // Launch and bind background Audio Service
        Intent serviceIntent = new Intent(this, AudioService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // System back navigation handling: collapse player panels or clear selections first
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSelectionMode) {
                    clearSelection();
                } else if (fullPlayerScreenContainer.getVisibility() == View.VISIBLE) {
                    fullPlayerScreenContainer.setVisibility(View.GONE);
                } else if (expandedDetailsContainer.getVisibility() == View.VISIBLE) {
                    expandedDetailsContainer.setVisibility(View.GONE);
                    currentOpenPlaylist = null;
                } else if (!searchEditText.getText().toString().isEmpty()) {
                    searchEditText.setText("");
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        checkPermissions();
    }

    /**
     * Triggers a subtle tactile haptic vibration keypress event on the targeted view.
     *
     * @param view View triggering the haptic feedback.
     */
    private void triggerHapticFeedback(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /**
     * Configures the display refresh rate. If 120Hz option is enabled, it queries
     * and selects the highest refresh rate mode supported by the hardware display.
     *
     * @param is120Hz Active boolean status flag.
     */
    private void applyRefreshRate(boolean is120Hz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowManager.LayoutParams windowAttributes = getWindow().getAttributes();
            if (is120Hz) {
                android.view.Display.Mode[] supportedModes = getWindowManager().getDefaultDisplay().getSupportedModes();
                android.view.Display.Mode bestDisplayMode = null;
                for (android.view.Display.Mode mode : supportedModes) {
                    if (bestDisplayMode == null || mode.getRefreshRate() > bestDisplayMode.getRefreshRate()) {
                        bestDisplayMode = mode;
                    }
                }
                if (bestDisplayMode != null) {
                    windowAttributes.preferredDisplayModeId = bestDisplayMode.getModeId();
                }
            } else {
                windowAttributes.preferredDisplayModeId = 0;
            }
            getWindow().setAttributes(windowAttributes);
        }
    }

    /**
     * Connects all layout widgets to their XML views, binds click listeners for playback
     * controls, settings panel, playlist generation dialog, navigation menus, search filtering,
     * and seek bar changes.
     */
    private void setupViews() {
        albumsGridView = findViewById(R.id.gridAlbums);
        libraryListView = findViewById(R.id.listLibrary);
        setupAppVersionDisplay();
        setupLibraryHeader();

        playlistsGridView = findViewById(R.id.gridPlaylists);
        playlistsGridView.setLayoutManager(new GridLayoutManager(this, 2));
        playlistsPageContainer = findViewById(R.id.pagePlaylists);
        expandedDetailsContainer = findViewById(R.id.expandedDetailsView);
        detailSongsListView = findViewById(R.id.listDetailSongs);
        detailTitleTextView = findViewById(R.id.txtDetailTitle);
        detailCoverImageView = findViewById(R.id.imgDetailCover);
        detailCoverImageView.setOnLongClickListener(v -> {
            triggerHapticFeedback(v);
            downloadImageFromImageView(detailCoverImageView, detailTitleTextView.getText().toString());
            return true;
        });
        bottomPlayerContainer = findViewById(R.id.bottomPlayer);
        fullPlayerScreenContainer = findViewById(R.id.fullPlayerScreen);
        miniTitleTextView = findViewById(R.id.txtMiniTitle);
        miniArtistTextView = findViewById(R.id.txtMiniArtist);
        miniArtImageView = findViewById(R.id.imgMiniArt);
        miniPlayButton = findViewById(R.id.btnMiniPlay);
        fullTitleTextView = findViewById(R.id.txtFullTitle);
        fullArtistTextView = findViewById(R.id.txtFullArtist);
        fullArtImageView = findViewById(R.id.imgFullArt);
        fullArtImageView.setOnLongClickListener(v -> {
            triggerHapticFeedback(v);
            downloadImageFromImageView(fullArtImageView, fullTitleTextView.getText().toString());
            return true;
        });
        fullPlayButton = findViewById(R.id.btnFullPlay);
        seekBarView = findViewById(R.id.seekBar);
        speedButton = findViewById(R.id.btnSpeed);
        setupSpeedControls();

        currentTimeTextView = findViewById(R.id.txtCurrentTime);
        totalTimeTextView = findViewById(R.id.txtTotalTime);
        searchEditText = findViewById(R.id.editSearch);
        audioVisualizerView = findViewById(R.id.visualizerView);
        particleView = findViewById(R.id.particleView);
        circularProgress = findViewById(R.id.circularProgress);

        topBarContainer = findViewById(R.id.topBar);
        selectionBarContainer = findViewById(R.id.selectionBar);
        selectionCountTextView = findViewById(R.id.txtSelectionCount);
        deleteSelectionButton = findViewById(R.id.btnDeleteSelection);

        setupSelectionControls();
        setupPlaybackControls();
        setupFullPlayerGestures();
        setupNavigationAndSearch();

        findViewById(R.id.btnCreatePlaylist).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            showCreatePlaylistDialog(newPlaylist -> {
                filterData(searchEditText.getText().toString());
                activePlaylistForImage = newPlaylist;
                imagePickerLauncher.launch(new String[]{"image/*"});
            });
        });

        setupSeekBarListener();
    }

    /**
     * Reads package metadata and initializes version text display if the view is present.
     */
    private void setupAppVersionDisplay() {
        TextView txtAppVersion = findViewById(R.id.txtAppVersion);
        if (txtAppVersion != null) {
            try {
                android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                txtAppVersion.setText(pInfo.versionName);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                txtAppVersion.setText("Vibe");
            }
        }
    }

    /**
     * Adds the shuffle-all header control button to the primary library list view.
     */
    private void setupLibraryHeader() {
        android.widget.LinearLayout libraryHeader = new android.widget.LinearLayout(this);
        libraryHeader.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        libraryHeader.setPadding(30, 30, 30, 30);
        com.google.android.material.button.MaterialButton btnLibShuffle = new com.google.android.material.button.MaterialButton(this);
        btnLibShuffle.setText("Shuffle All");
        btnLibShuffle.setTextColor(android.graphics.Color.WHITE);
        btnLibShuffle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333333));
        btnLibShuffle.setCornerRadius(100);
        btnLibShuffle.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        btnLibShuffle.setOnClickListener(v -> {
            triggerHapticFeedback(v);
            if (!displaySongs.isEmpty()) {
                java.util.ArrayList<Song> shuffledQueue = new java.util.ArrayList<>(displaySongs);
                java.util.Collections.shuffle(shuffledQueue);
                playAudio(shuffledQueue, 0);
            }
        });
        libraryHeader.addView(btnLibShuffle);
        libraryListView.addHeaderView(libraryHeader);
    }

    /**
     * Configures quick cycle click listener and long-click fine-tuning dialog for playback speed.
     */
    private void setupSpeedControls() {
        speedButton.setOnClickListener(v -> {
            triggerHapticFeedback(v);
            if (!isBound || audioService == null) return;
            float currentSpeed = audioService.getPlaybackSpeed();
            float nextSpeed = (float) (Math.floor(currentSpeed * 4.0) / 4.0) + 0.25f;
            if (nextSpeed > 2.51f) nextSpeed = 0.25f;
            audioService.setPlaybackSpeed(nextSpeed);
            speedButton.setText(String.format(java.util.Locale.getDefault(), "%.2fx", nextSpeed));
        });
        speedButton.setOnLongClickListener(v -> {
            triggerHapticFeedback(v);
            if (!isBound || audioService == null) return true;
            showFineTuneSpeedDialog();
            return true;
        });
    }

    /**
     * Displays a dialog containing a continuous slider to adjust playback speed precisely.
     */
    private void showFineTuneSpeedDialog() {
        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(this);
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        rootLayout.setPadding(50, 50, 50, 50);

        android.widget.TextView txtSpeedInd = new android.widget.TextView(this);
        txtSpeedInd.setTextSize(18);
        txtSpeedInd.setGravity(android.view.Gravity.CENTER);
        txtSpeedInd.setText(String.format(java.util.Locale.getDefault(), "Speed: %.2fx", audioService.getPlaybackSpeed()));
        rootLayout.addView(txtSpeedInd, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(this);
        rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rowLayout.setPadding(0, 30, 0, 0);

        android.widget.SeekBar speedBar = new android.widget.SeekBar(this);
        speedBar.setMax(225);
        speedBar.setProgress((int) ((audioService.getPlaybackSpeed() - 0.25f) * 100));
        rowLayout.addView(speedBar, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button btnReset = new android.widget.Button(this);
        btnReset.setText("Reset");
        btnReset.setTextColor(android.graphics.Color.WHITE);
        btnReset.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF333333));
        rowLayout.addView(btnReset);

        rootLayout.addView(rowLayout, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        speedBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float newSpeed = 0.25f + (progress / 100f);
                speedButton.setText(String.format(java.util.Locale.getDefault(), "%.2fx", newSpeed));
                txtSpeedInd.setText(String.format(java.util.Locale.getDefault(), "Speed: %.2fx", newSpeed));
                audioService.setPlaybackSpeed(newSpeed);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        btnReset.setOnClickListener(v2 -> speedBar.setProgress(75));

        new android.app.AlertDialog.Builder(this)
                .setTitle("Fine-tune Speed")
                .setView(rootLayout)
                .setPositiveButton("Close", null)
                .show();
    }

    /**
     * Binds action listeners to multi-selection mode toolbar buttons.
     */
    private void setupSelectionControls() {
        findViewById(R.id.btnCancelSelection).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            clearSelection();
        });
        findViewById(R.id.btnAddSelection).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            showBatchAddToPlaylistDialog();
        });
        deleteSelectionButton.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            batchDeleteFromPlaylist();
        });
    }

    /**
     * Binds transport controls and full player sheet expand/collapse listeners.
     */
    private void setupPlaybackControls() {
        findViewById(R.id.btnNext).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) audioService.playNext();
        });
        findViewById(R.id.btnPrev).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) audioService.playPrev();
        });
        miniPlayButton.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) audioService.togglePlayPause();
        });
        fullPlayButton.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) audioService.togglePlayPause();
        });
        bottomPlayerContainer.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            fullPlayerScreenContainer.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnCollapsePlayer).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            fullPlayerScreenContainer.setVisibility(View.GONE);
        });
    }

    /**
     * Registers double-tap and tap gestures on full player container in landscape orientation.
     */
    private void setupFullPlayerGestures() {
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    if (isBound) audioService.togglePlayPause();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    float x = e.getX();
                    int width = fullPlayerScreenContainer.getWidth();
                    if (x < width / 2.0f) {
                        if (isBound) audioService.playPrev();
                    } else {
                        if (isBound) audioService.playNext();
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        fullPlayerScreenContainer.setOnTouchListener((v, event) -> {
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                gestureDetector.onTouchEvent(event);
                return true;
            }
            return false;
        });
    }

    /**
     * Sets up bottom navigation tab switching, search input listeners, and sorting button.
     */
    private void setupNavigationAndSearch() {
        findViewById(R.id.btnSettings).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            showMainSettingsDialog();
        });

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNav);
        bottomNavigationView.setOnItemSelectedListener(menuItem -> {
            triggerHapticFeedback(bottomNavigationView);
            fullPlayerScreenContainer.setVisibility(View.GONE);
            albumsGridView.setVisibility(View.GONE);
            libraryListView.setVisibility(View.GONE);
            playlistsPageContainer.setVisibility(View.GONE);
            expandedDetailsContainer.setVisibility(View.GONE);
            clearSelection();
            currentOpenPlaylist = null;

            tabSearchStates.put(currentTabId, searchEditText.getText().toString());
            int itemId = menuItem.getItemId();
            currentTabId = itemId;

            if (itemId == R.id.nav_albums) {
                albumsGridView.setVisibility(View.VISIBLE);
            } else if (itemId == R.id.nav_library) {
                libraryListView.setVisibility(View.VISIBLE);
            } else if (itemId == R.id.nav_playlists) {
                playlistsPageContainer.setVisibility(View.VISIBLE);
            }

            String savedSearch = tabSearchStates.get(itemId);
            searchEditText.setText(savedSearch != null ? savedSearch : "");
            return true;
        });

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence query, int start, int before, int count) {
                filterData(query.toString());
            }

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btnSort).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            String[] sortOptions = {"A-Z", "Z-A", "Newest"};
            new AlertDialog.Builder(this)
                    .setTitle("Sort")
                    .setItems(sortOptions, (dialog, which) -> {
                        sortData(which);
                        filterData(searchEditText.getText().toString());
                    }).show();
        });
    }

    /**
     * Binds progress changes on the full player seek bar to AudioService seek operations.
     */
    private void setupSeekBarListener() {
        seekBarView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isBound) {
                    audioService.seekTo(progress);
                    currentTimeTextView.setText(formatTime(progress));
                    if (circularProgress != null && audioService.getDuration() > 0) {
                        circularProgress.setProgress((float) progress / audioService.getDuration());
                    }
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /**
     * Initializes the system Visualizer object using the bound AudioService session ID.
     * Binds FFT capture listeners to pipe frequency data to the visualizer wave view.
     */
    private void setupVisualizer() {
        if (!isBound || audioService.getAudioSessionId() == 0) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
 
        if (audioVisualizer != null) {
            try {
                audioVisualizer.setEnabled(false);
            } catch (Exception ignored) {}
            try {
                audioVisualizer.release();
            } catch (Exception ignored) {}
            audioVisualizer = null;
        }
 
        try {
            audioVisualizer = new Visualizer(audioService.getAudioSessionId());
            audioVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
 
            audioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {}
 
                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fftData, int samplingRate) {
                    audioVisualizerView.updateVisualizer(fftData);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
 
            audioVisualizer.setEnabled(true);
        } catch (Exception ignored) {}
    }

    /**
     * Service callback invoked when active track shifts. Updates text views, metadata art,
     * reinitializes visualizer attachments, and generates dynamic background color palettes using Android Palette.
     *
     * @param song     The new Song record metadata.
     * @param albumArt Bitmap art retrieved from metadata.
     */
    @Override
    public void onTrackChanged(Song song, Bitmap albumArt) {
        miniTitleTextView.setText(song.title);
        miniArtistTextView.setText(song.artist);
        fullTitleTextView.setText(song.title);
        fullArtistTextView.setText(song.artist);

        setupVisualizer();

        // Feed low-res image bounds directly to prevent dynamic grey image flickering
        loadArtAsync(fullArtImageView, song.path, false, QUALITY_HIGH, albumArt);

        boolean showVis = sharedPreferences.getBoolean("show_visualizer", true);
        audioVisualizerView.setVisibility(showVis ? View.VISIBLE : View.GONE);

        if (albumArt != null) {
            miniArtImageView.setImageBitmap(albumArt);

            // Generate an adaptive UI theme gradient matching the track artwork colors
            Palette.from(albumArt).generate(palette -> {
                if (palette == null) return;
                int vibrantColor = palette.getVibrantColor(0xFFFFFFFF);
                seekBarView.getThumb().setTint(vibrantColor);
                audioVisualizerView.setColor(vibrantColor);
                if (particleView != null) particleView.setParticleColor(vibrantColor);
                if (circularProgress != null) circularProgress.setColor(vibrantColor);

                if (sharedPreferences.getBoolean("adaptive_bg", true)) {
                    int dominantColor = palette.getDominantColor(0xFF111111);
                    int darkMutedColor = palette.getDarkMutedColor(0xFF000000);
                    android.graphics.drawable.GradientDrawable backgroundGradient = new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{dominantColor, darkMutedColor, 0xFF000000}
                    );
                    fullPlayerScreenContainer.setBackground(backgroundGradient);
                } else {
                    fullPlayerScreenContainer.setBackgroundColor(0xFF000000);
                }
            });
        } else {
            loadArtAsync(miniArtImageView, song.path, false, QUALITY_LOW, null);
            seekBarView.getThumb().setTint(0xFFFFFFFF);
            audioVisualizerView.setColor(0xFFFFFFFF);
            if (particleView != null) particleView.setParticleColor(0xFFFFFFFF);
            if (circularProgress != null) circularProgress.setColor(0xFFFFFFFF);
            fullPlayerScreenContainer.setBackgroundColor(0xFF000000);
        }

        if (isBound) {
            seekBarView.setMax(audioService.getDuration());
            totalTimeTextView.setText(formatTime(audioService.getDuration()));
        }
    }

    /**
     * Service callback invoked when audio changes playback status (e.g. pauses or starts).
     * Automatically coordinates seekBar updating timers.
     *
     * @param isPlaying True if actively playing, false if paused.
     */
    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        miniPlayButton.setImageResource(isPlaying ? R.drawable.ic_pause_bubbly : R.drawable.ic_play_bubbly);
        fullPlayButton.setImageResource(isPlaying ? R.drawable.ic_pause_bubbly : R.drawable.ic_play_bubbly);

        if (isPlaying) {
            seekHandler.removeCallbacks(updateSeekBarTask);
            seekHandler.postDelayed(updateSeekBarTask, 500);
        } else {
            seekHandler.removeCallbacks(updateSeekBarTask);
        }
    }

    /**
     * Recurring Runnable task updating the seek bar progress slider and time label.
     */
    private final Runnable updateSeekBarTask = new Runnable() {
        @Override
        public void run() {
            if (isBound && audioService.isPlaying()) {
                int currentProgressMs = audioService.getCurrentPosition();
                int duration = audioService.getDuration();
                seekBarView.setProgress(currentProgressMs);
                currentTimeTextView.setText(formatTime(currentProgressMs));
                if (circularProgress != null && duration > 0) {
                    circularProgress.setProgress((float) currentProgressMs / duration);
                }
                seekHandler.postDelayed(this, 1000);
            }
        }
    };

    /**
     * Submits a playback queue and starting track offset to the bound AudioService.
     *
     * @param queue    List of Songs representing the queue.
     * @param position Starting index position.
     */
    private void playAudio(ArrayList<Song> queue, int position) {
        if (isBound) {
            audioService.setQueueAndPlay(queue, position);
        } else {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Toggles long-press batch action selection modes for songs. Manages selected
     * track state sets and switches layout action toolbars.
     *
     * @param song Song target toggled.
     */
    private void toggleSelectionMode(Song song) {
        if (!isSelectionMode) {
            isSelectionMode = true;
            topBarContainer.setVisibility(View.GONE);
            selectionBarContainer.setVisibility(View.VISIBLE);
        }

        if (selectedSongs.contains(song)) {
            selectedSongs.remove(song);
        } else {
            selectedSongs.add(song);
        }

        if (selectedSongs.isEmpty()) {
            clearSelection();
        } else {
            selectionCountTextView.setText(String.format(Locale.getDefault(), "%d Selected", selectedSongs.size()));
            deleteSelectionButton.setVisibility(currentOpenPlaylist != null ? View.VISIBLE : View.GONE);
            refreshAllAdapters();
        }
    }

    /**
     * Resets active batch choice selections, clearing selection tracking sets and
     * swapping the action toolbar layout back to normal search mode.
     */
    private void clearSelection() {
        isSelectionMode = false;
        selectedSongs.clear();
        selectionBarContainer.setVisibility(View.GONE);
        topBarContainer.setVisibility(View.VISIBLE);
        refreshAllAdapters();
    }

    /**
     * Refreshes view state adapters for all collection lists on screen.
     */
    private void refreshAllAdapters() {
        if (librarySongAdapter != null) librarySongAdapter.notifyDataSetChanged();
        if (detailSongListAdapter != null) detailSongListAdapter.notifyDataSetChanged();
        if (albumListAdapter != null) albumListAdapter.notifyDataSetChanged();
        if (playlistListAdapter != null) playlistListAdapter.notifyDataSetChanged();
    }

    /**
     * Displays a dialog prompting the user for a new playlist name and optional description.
     *
     * @param onCreated Callback consumer executed after the new playlist is persisted.
     */
    private void showCreatePlaylistDialog(Consumer<Playlist> onCreated) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText nameField = new EditText(this);
        nameField.setHint("Playlist Name");
        EditText descField = new EditText(this);
        descField.setHint("Playlist Description");
        layout.addView(nameField);
        layout.addView(descField);

        new AlertDialog.Builder(this)
                .setTitle("New Playlist")
                .setView(layout)
                .setPositiveButton("Create", (dialog, which) -> {
                    Playlist newPlaylist = new Playlist(nameField.getText().toString(), null);
                    newPlaylist.description = descField.getText().toString();
                    allPlaylists.add(newPlaylist);
                    savePlaylists();
                    if (onCreated != null) {
                        onCreated.accept(newPlaylist);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Displays a dialog selection list prompting users to assign all currently selected
     * tracks to an existing playlist or create a new playlist with them.
     */
    private void showBatchAddToPlaylistDialog() {
        if (selectedSongs.isEmpty()) return;
        String[] options = new String[allPlaylists.size() + 1];
        options[0] = "(Create Playlist...)";
        for (int i = 0; i < allPlaylists.size(); i++) {
            options[i + 1] = allPlaylists.get(i).name;
        }

        new AlertDialog.Builder(this)
                .setTitle("Add " + selectedSongs.size() + " songs to...")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showCreatePlaylistDialog(newPlaylist -> {
                            newPlaylist.songs.addAll(selectedSongs);
                            savePlaylists();
                            filterData("");
                            Toast.makeText(this, "Created & Added " + selectedSongs.size() + " songs!", Toast.LENGTH_SHORT).show();
                            clearSelection();
                        });
                    } else {
                        Playlist targetPlaylist = allPlaylists.get(which - 1);
                        int addedCount = 0;
                        int duplicateCount = 0;
                        for (Song selectedSong : selectedSongs) {
                            boolean songExists = false;
                            for (Song existingSong : targetPlaylist.songs) {
                                if (existingSong.id.equals(selectedSong.id)) {
                                    songExists = true;
                                    break;
                                }
                            }
                            if (!songExists) {
                                targetPlaylist.songs.add(selectedSong);
                                addedCount++;
                            } else {
                                duplicateCount++;
                            }
                        }
                        savePlaylists();
                        filterData("");
                        String message = "Added " + addedCount + " songs to " + targetPlaylist.name;
                        if (duplicateCount > 0) {
                            message += " (" + duplicateCount + " duplicates skipped)";
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        clearSelection();
                    }
                }).show();
    }

    /**
     * Removes selected songs from the active playlist folder, updates persistent data
     * settings representation, and refreshes list layout adapters.
     */
    private void batchDeleteFromPlaylist() {
        if (currentOpenPlaylist == null || selectedSongs.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + selectedSongs.size() + " songs?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    for (Song selected : selectedSongs) {
                        currentOpenPlaylist.songs.removeIf(song -> song.id.equals(selected.id));
                    }
                    savePlaylists();
                    filterData("");
                    clearSelection();
                    openDetailView(currentOpenPlaylist.name, currentOpenPlaylist.songs, true, currentOpenPlaylist, null);
                }).setNegativeButton("No", null).show();
    }

    /**
     * Instantiates view adapters, assigns adapters to lists/grids on screen,
     * and sets click/long-click selectors.
     */
    private void setupAdapters() {
        librarySongAdapter = new SongAdapter(displaySongs);
        libraryListView.setAdapter(librarySongAdapter);

        albumListAdapter = new AlbumAdapter();
        albumsGridView.setAdapter(albumListAdapter);
        albumsGridView.setOnItemClickListener((parent, view, position, id) -> {
            triggerHapticFeedback(view);
            openDetailView(displayAlbums.get(position).name, displayAlbums.get(position).songs, false, null, displayAlbums.get(position));
        });
        albumsGridView.setOnItemLongClickListener((parent, view, position, id) -> {
            triggerHapticFeedback(view);
            Album currentAlbum = displayAlbums.get(position);
            String[] options = {"Convert to Playlist", "Edit Metadata", "Change Cover Art", "Download Image", "Delete Album"};
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(currentAlbum.name)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            Playlist newPlaylist = new Playlist(currentAlbum.name, null);
                            newPlaylist.songs.addAll(currentAlbum.songs);
                            allPlaylists.add(newPlaylist);
                            savePlaylists();
                            filterData(searchEditText.getText().toString());
                            Toast.makeText(MainActivity.this, "Playlist created from Album", Toast.LENGTH_SHORT).show();
                        } else if (which == 1) {
                            showEditAlbumMetadataDialog(currentAlbum);
                        } else if (which == 2) {
                            activeAlbumForImage = currentAlbum;
                            imagePickerLauncher.launch(new String[]{"image/*"});
                        } else if (which == 3) {
                            android.widget.ImageView albumImg = view.findViewById(R.id.imgGridArt);
                            if (albumImg != null) downloadImageFromImageView(albumImg, currentAlbum.name);
                        } else if (which == 4) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Delete Album")
                                    .setMessage("Are you sure you want to physically delete all songs in this album?")
                                    .setPositiveButton("Yes", (dialog2, which2) -> deleteAlbum(currentAlbum))
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    }).show();
            return true;
        });

        playlistListAdapter = new PlaylistAdapter();
        playlistsGridView.setAdapter(playlistListAdapter);

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();
                
                Playlist moved = displayPlaylists.get(from);
                int allFrom = allPlaylists.indexOf(moved);
                int allTo = allPlaylists.indexOf(displayPlaylists.get(to));
                
                if (allFrom != -1 && allTo != -1) {
                    Playlist allMoved = allPlaylists.remove(allFrom);
                    allPlaylists.add(allTo, allMoved);
                }
                Playlist displayMoved = displayPlaylists.remove(from);
                displayPlaylists.add(to, displayMoved);
                playlistListAdapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                savePlaylists();
            }
        });
        itemTouchHelper.attachToRecyclerView(playlistsGridView);

        detailSongListAdapter = new DetailSongAdapter();
        detailSongsListView.setAdapter(detailSongListAdapter);
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
    private void loadArtAsync(ImageView imageView, String artworkPath, boolean isUri, int qualityMode, Bitmap preloadedFallback) {
        if (artworkPath == null || artworkPath.isEmpty()) {
            imageView.setImageResource(R.drawable.ic_albums_bubbly);
            return;
        }
        String cacheKey = artworkPath + "_" + qualityMode;
        Bitmap cachedBitmap = artworkCache.get(cacheKey);
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap);
            return;
        }

        imageView.setTag(artworkPath);

        if (preloadedFallback != null) {
            imageView.setImageBitmap(preloadedFallback);
        } else if (imageView != fullArtImageView && imageView != miniArtImageView) {
            imageView.setImageResource(R.drawable.ic_albums_bubbly);
        }

        imageExecutor.execute(() -> {
            Bitmap decodedBitmap = ArtUtil.decodeArtworkBitmap(getContentResolver(), artworkPath, isUri, qualityMode);

            if (decodedBitmap != null) {
                artworkCache.put(cacheKey, decodedBitmap);
                final Bitmap finalBitmap = decodedBitmap;
                mainHandler.post(() -> {
                    if (artworkPath.equals(imageView.getTag())) {
                        imageView.setImageBitmap(finalBitmap);
                    }
                });
            } else {
                mainHandler.post(() -> {
                    if (artworkPath.equals(imageView.getTag())) {
                        imageView.setImageResource(R.drawable.ic_albums_bubbly);
                    }
                });
            }
        });
    }

    /**
     * ViewHolder caching references to a song item's subviews to reduce findViewById calls.
     */
    static class SongViewHolder {
        CheckBox selectionCheckBox;
        TextView titleTextView;
        TextView artistTextView;
        ImageView artworkImageView;
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
    private View bindSongItemView(int position, View convertView, ViewGroup parent, ArrayList<Song> songs) {
        SongViewHolder viewHolder;
        if (convertView == null) {
            convertView = getLayoutInflater().inflate(R.layout.item_song, parent, false);
            viewHolder = new SongViewHolder();
            viewHolder.selectionCheckBox = convertView.findViewById(R.id.chkSelect);
            viewHolder.titleTextView = convertView.findViewById(R.id.txtTitle);
            viewHolder.artistTextView = convertView.findViewById(R.id.txtArtist);
            viewHolder.artworkImageView = convertView.findViewById(R.id.imgArt);
            convertView.setTag(viewHolder);
        } else {
            viewHolder = (SongViewHolder) convertView.getTag();
        }

        Song currentSong = songs.get(position);
        viewHolder.titleTextView.setText(currentSong.title);
        viewHolder.artistTextView.setText(currentSong.artist);
        loadArtAsync(viewHolder.artworkImageView, currentSong.path, false, QUALITY_LOW, null);

        if (isSelectionMode) {
            viewHolder.selectionCheckBox.setVisibility(View.VISIBLE);
            viewHolder.selectionCheckBox.setChecked(selectedSongs.contains(currentSong));
        } else {
            viewHolder.selectionCheckBox.setVisibility(View.GONE);
        }

        convertView.setOnClickListener(clickedView -> {
            triggerHapticFeedback(clickedView);
            if (isSelectionMode) {
                toggleSelectionMode(currentSong);
            } else {
                playAudio(songs, position);
            }
        });
        convertView.setOnLongClickListener(clickedView -> {
            triggerHapticFeedback(clickedView);
            if (!isSelectionMode) {
                String[] options = {"Select", "Edit Metadata", "Download Image"};
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            toggleSelectionMode(currentSong);
                        } else if (which == 1) {
                            showEditSongMetadataDialog(currentSong);
                        } else if (which == 2) {
                            downloadImageFromImageView(viewHolder.artworkImageView, currentSong.title);
                        }
                    })
                    .show();
            } else {
                toggleSelectionMode(currentSong);
            }
            return true;
        });
        return convertView;
    }

    /**
     * Adapter for displaying songs in the primary library list view.
     */
    private class SongAdapter extends android.widget.ArrayAdapter<Song> {
        final ArrayList<Song> songsList;

        public SongAdapter(ArrayList<Song> list) {
            super(MainActivity.this, R.layout.item_song, list);
            this.songsList = list;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            return bindSongItemView(position, convertView, parent, songsList);
        }
    }

    /**
     * Adapter for displaying songs in the expanded details overlay sheet.
     */
    private class DetailSongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return displayDetailSongs.size();
        }

        @Override
        public Object getItem(int position) {
            return displayDetailSongs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return bindSongItemView(position, convertView, parent, displayDetailSongs);
        }
    }

    /**
     * Adapter for rendering Album grid card layouts.
     */
    private class AlbumAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return displayAlbums.size();
        }

        @Override
        public Object getItem(int position) {
            return displayAlbums.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_grid, parent, false);
            }
            Album currentAlbum = displayAlbums.get(position);
            ((TextView) convertView.findViewById(R.id.txtGridTitle)).setText(currentAlbum.name);
            ((TextView) convertView.findViewById(R.id.txtGridSub)).setText(currentAlbum.artist);
            
            ImageView fireIndicator = convertView.findViewById(R.id.imgFireIndicator);
            if (fireIndicator != null) {
                fireIndicator.setVisibility(currentAlbum.isFire ? View.VISIBLE : View.GONE);
            }

            loadArtAsync(
                    convertView.findViewById(R.id.imgGridArt),
                    currentAlbum.songs.isEmpty() ? null : currentAlbum.songs.get(0).path,
                    false,
                    QUALITY_MED,
                    null
            );
            return convertView;
        }
    }

    /**
     * Adapter for rendering Playlist grid card layouts.
     */
    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView titleText;
            TextView subText;
            ImageView artImageView;
            ImageView fireIndicator;

            ViewHolder(View view) {
                super(view);
                titleText = view.findViewById(R.id.txtGridTitle);
                subText = view.findViewById(R.id.txtGridSub);
                artImageView = view.findViewById(R.id.imgGridArt);
                fireIndicator = view.findViewById(R.id.imgFireIndicator);

                view.setOnClickListener(v -> {
                    int pos = getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        triggerHapticFeedback(view);
                        openDetailView(displayPlaylists.get(pos).name, displayPlaylists.get(pos).songs, true, displayPlaylists.get(pos), null);
                    }
                });
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_grid, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Playlist currentPlaylist = displayPlaylists.get(position);
            holder.titleText.setText(currentPlaylist.name);
            holder.subText.setText(String.format(Locale.getDefault(), "%d songs", currentPlaylist.songs.size()));

            if (holder.fireIndicator != null) {
                holder.fireIndicator.setVisibility(currentPlaylist.isFire ? View.VISIBLE : View.GONE);
            }

            if (currentPlaylist.imageUri != null && !currentPlaylist.imageUri.isEmpty()) {
                loadArtAsync(holder.artImageView, currentPlaylist.imageUri, true, QUALITY_MED, null);
            } else if (!currentPlaylist.songs.isEmpty()) {
                loadArtAsync(holder.artImageView, currentPlaylist.songs.get(0).path, false, QUALITY_MED, null);
            } else {
                holder.artImageView.setBackgroundColor(0xFF333333);
                holder.artImageView.setImageResource(R.drawable.ic_albums_bubbly);
            }
        }

        @Override
        public int getItemCount() {
            return displayPlaylists.size();
        }
    }


    /**
     * Filters lists for songs, albums, and playlists based on text search queries.
     *
     * @param query Search query string input.
     */
    private void filterData(String query) {
        MusicLibraryUtil.filterData(query, allSongs, allAlbums, allPlaylists, displaySongs, displayAlbums, displayPlaylists);
        refreshAllAdapters();
    }

    /**
     * Reads shared system external storage files using a ContentResolver.
     * Constructs the local database maps, scans playlists from SharedPreferences,
     * sorts tracks, and updates lists on the UI thread.
     */
    private void loadMusic() {
        new Thread(() -> {
            HashMap<String, Album> albumMap = new HashMap<>();
            ArrayList<Song> tempSongs = MusicLibraryUtil.queryMediaStoreSongs(getContentResolver(), fireAlbums, albumMap);

            ArrayList<Album> tempAlbums = new ArrayList<>(albumMap.values());
            for (Album album : tempAlbums) {
                Collections.sort(album.songs, Comparator.comparingInt(song -> song.trackNumber));
            }

            HashMap<String, Song> songIdMap = new HashMap<>();
            HashMap<String, Song> songNameMap = new HashMap<>();
            for (Song s : tempSongs) {
                songIdMap.put(s.id, s);
                songNameMap.put((s.title + "_" + s.artist).toLowerCase(Locale.getDefault()), s);
            }

            ArrayList<Playlist> tempPlaylists = PlaylistUtil.parsePlaylists(sharedPreferences, songIdMap, songNameMap);

            Comparator<Song> songComparator = (a, b) -> a.title.compareToIgnoreCase(b.title);
            Comparator<Album> albumComparator = (a, b) -> a.name.compareToIgnoreCase(b.name);
            Collections.sort(tempSongs, songComparator);
            Collections.sort(tempAlbums, albumComparator);

            runOnUiThread(() -> {
                allSongs.clear();
                allSongs.addAll(tempSongs);
                allAlbums.clear();
                allAlbums.addAll(tempAlbums);
                allPlaylists.clear();
                allPlaylists.addAll(tempPlaylists);

                filterData(searchEditText.getText().toString());
            });
        }).start();
    }

    /**
     * Serializes playlist collection structures to persistent SharedPreferences storage.
     */
    private void savePlaylists() {
        PlaylistUtil.savePlaylists(sharedPreferences, allPlaylists);
    }

    /**
     * Sorts song and album datasets alphabetically or by add date.
     *
     * @param sortType Sort selector key index (0: A-Z, 1: Z-A, 2: Newest).
     */
    private void sortData(int sortType) {
        MusicLibraryUtil.sortData(sortType, allSongs, allAlbums);
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
    private void openDetailView(String viewTitle, ArrayList<Song> songsList, boolean isPlaylist, Playlist playlistObject, Album albumObject) {
        expandedDetailsContainer.setVisibility(View.VISIBLE);
        detailTitleTextView.setText(viewTitle);
        currentOpenPlaylist = isPlaylist ? playlistObject : null;

        ImageView btnDetailOptions = findViewById(R.id.btnDetailOptions);
        TextView txtDetailDescription = findViewById(R.id.txtDetailDescription);
        if (isPlaylist && playlistObject != null) {
            btnDetailOptions.setVisibility(View.VISIBLE);
            if (playlistObject.description != null && !playlistObject.description.isEmpty()) {
                txtDetailDescription.setVisibility(View.VISIBLE);
                txtDetailDescription.setText(playlistObject.description);
            } else {
                txtDetailDescription.setVisibility(View.GONE);
            }
            btnDetailOptions.setOnClickListener(v -> {
                triggerHapticFeedback(v);
                showPlaylistDetailOptionsDialog(playlistObject, txtDetailDescription);
            });
        } else {
            btnDetailOptions.setVisibility(View.GONE);
            txtDetailDescription.setVisibility(View.GONE);
        }

        if (isPlaylist && playlistObject != null && playlistObject.imageUri != null) {
            loadArtAsync(detailCoverImageView, playlistObject.imageUri, true, QUALITY_HIGH, null);
        } else if (!songsList.isEmpty()) {
            loadArtAsync(detailCoverImageView, songsList.get(0).path, false, QUALITY_HIGH, null);
        } else {
            detailCoverImageView.setImageResource(R.drawable.ic_albums_bubbly);
        }

        displayDetailSongs.clear();
        displayDetailSongs.addAll(songsList);
        if (detailSongListAdapter != null) {
            detailSongListAdapter.notifyDataSetChanged();
        }

        findViewById(R.id.btnDetailPlayAll).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (!songsList.isEmpty()) {
                playAudio(new ArrayList<>(songsList), 0);
            }
        });
        findViewById(R.id.btnDetailShuffle).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (!songsList.isEmpty()) {
                ArrayList<Song> shuffledQueue = new ArrayList<>(songsList);
                Collections.shuffle(shuffledQueue);
                playAudio(shuffledQueue, 0);
            }
        });

        ImageButton fireToggle = findViewById(R.id.btnDetailFireToggle);
        setupDetailFireToggle(fireToggle, isPlaylist, playlistObject, albumObject);

        if (isSelectionMode) {
            deleteSelectionButton.setVisibility(currentOpenPlaylist != null ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Displays a dialog containing management options for an active playlist in detail view.
     *
     * @param playlist Target playlist to manage.
     * @param txtDetailDescription View displaying playlist description text.
     */
    private void showPlaylistDetailOptionsDialog(Playlist playlist, TextView txtDetailDescription) {
        String[] options = {"Edit Details", "Change Cover", "Download Image", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(playlist.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEditPlaylistDialog(playlist, txtDetailDescription);
                    } else if (which == 1) {
                        activePlaylistForImage = playlist;
                        imagePickerLauncher.launch(new String[]{"image/*"});
                    } else if (which == 2) {
                        downloadImageFromImageView(detailCoverImageView, playlist.name);
                    } else if (which == 3) {
                        allPlaylists.remove(playlist);
                        savePlaylists();
                        filterData(searchEditText.getText().toString());
                        expandedDetailsContainer.setVisibility(View.GONE);
                    }
                }).show();
    }

    /**
     * Displays input dialog to update playlist title and description metadata.
     *
     * @param playlist Target playlist to edit.
     * @param txtDetailDescription View displaying playlist description text.
     */
    private void showEditPlaylistDialog(Playlist playlist, TextView txtDetailDescription) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText nameField = new EditText(this);
        nameField.setHint("Playlist Name");
        nameField.setText(playlist.name);
        EditText descField = new EditText(this);
        descField.setHint("Playlist Description");
        descField.setText(playlist.description);
        layout.addView(nameField);
        layout.addView(descField);

        new AlertDialog.Builder(this)
                .setTitle("Edit Details")
                .setView(layout)
                .setPositiveButton("Save", (dialogInner, whichInner) -> {
                    playlist.name = nameField.getText().toString();
                    playlist.description = descField.getText().toString();
                    detailTitleTextView.setText(playlist.name);

                    if (playlist.description != null && !playlist.description.isEmpty()) {
                        txtDetailDescription.setVisibility(View.VISIBLE);
                        txtDetailDescription.setText(playlist.description);
                    } else {
                        txtDetailDescription.setVisibility(View.GONE);
                    }

                    savePlaylists();
                    filterData(searchEditText.getText().toString());
                }).show();
    }

    /**
     * Binds click handling and visual feedback animation to the detail view favorite/fire toggle.
     *
     * @param fireToggle Target fire toggle button.
     * @param isPlaylist True if the details sheet represents a playlist.
     * @param playlist Associated playlist if applicable.
     * @param album Associated album if applicable.
     */
    private void setupDetailFireToggle(ImageButton fireToggle, boolean isPlaylist, Playlist playlist, Album album) {
        boolean isFire = (isPlaylist && playlist != null) ? playlist.isFire : (album != null && album.isFire);
        fireToggle.setColorFilter(isFire ? android.graphics.Color.parseColor("#FF9800") : android.graphics.Color.WHITE);

        fireToggle.setOnClickListener(v -> {
            triggerHapticFeedback(v);
            boolean newFire = false;
            if (isPlaylist && playlist != null) {
                playlist.isFire = !playlist.isFire;
                newFire = playlist.isFire;
            } else if (!isPlaylist && album != null) {
                album.isFire = !album.isFire;
                newFire = album.isFire;
                if (newFire) fireAlbums.add(album.albumId);
                else fireAlbums.remove(album.albumId);
                sharedPreferences.edit().putStringSet("fireAlbums", fireAlbums).apply();
            }
            savePlaylists();
            fireToggle.setColorFilter(newFire ? android.graphics.Color.parseColor("#FF9800") : android.graphics.Color.WHITE);
            v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction(() -> {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                sortData(sharedPreferences.getInt("sort_type", 0));
                filterData(searchEditText.getText().toString());
            }).start();
        });
    }

    /**
     * Queries and requests OS access permissions needed for media retrieval,
     * notification dispatches, and audio recording (required for the visualizer FFT API).
     */
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_MEDIA_AUDIO,
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.POST_NOTIFICATIONS,
                            Manifest.permission.RECORD_AUDIO
                    },
                    1
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.RECORD_AUDIO
                    },
                    1
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        loadMusic();
    }

    /**
     * Converts raw millisecond values to standard MM:SS time string formats.
     *
     * @param positionMs Position value in milliseconds.
     * @return           Formatted time string.
     */
    private String formatTime(int positionMs) {
        int hours = positionMs / (1000 * 60 * 60);
        int minutes = (positionMs / (1000 * 60)) % 60;
        int seconds = (positionMs / 1000) % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        }
    }

    /**
     * Downloads and exports the artwork bitmap displayed in an ImageView to user gallery.
     *
     * @param view ImageView holding the artwork tag.
     * @param title Title used for naming the image file.
     */
    private void downloadImageFromImageView(ImageView view, String title) {
        String artworkPath = (String) view.getTag();
        if (artworkPath == null || artworkPath.isEmpty()) {
            Toast.makeText(this, "No image available to download", Toast.LENGTH_SHORT).show();
            return;
        }

        imageExecutor.execute(() -> {
            boolean isUri = artworkPath.startsWith("content://") || artworkPath.startsWith("data:image/");
            Bitmap decodedBitmap = ArtUtil.decodeArtworkBitmap(getContentResolver(), artworkPath, isUri, 1);
            if (decodedBitmap == null) {
                mainHandler.post(() -> Toast.makeText(this, "No valid high-res image found", Toast.LENGTH_SHORT).show());
                return;
            }

            boolean saved = ArtUtil.saveBitmapToGallery(getContentResolver(), decodedBitmap, title);
            mainHandler.post(() -> Toast.makeText(this, saved ? "Image saved to Pictures!" : "Failed to save image", Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Configures document picker contracts and activity launchers for choosing custom playlist cover images,
     * exporting playlist backup backups, and restoring backups.
     */
    private void setupLaunchers() {
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::handleImagePickerResult);
        backupFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), documentUri -> PlaylistUtil.exportBackup(this, sharedPreferences, documentUri));
        restoreFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), documentUri -> PlaylistUtil.restoreBackup(this, sharedPreferences, documentUri, this::loadMusic));
    }

    /**
     * Updates active playlist or album cover image reference with the selected document URI.
     *
     * @param documentUri Selected image document URI.
     */
    private void handleImagePickerResult(Uri documentUri) {
        if (documentUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        if (activePlaylistForImage != null) {
            activePlaylistForImage.imageUri = documentUri.toString();
            savePlaylists();
            filterData("");
        } else if (activeAlbumForImage != null) {
            updateAlbumArt(activeAlbumForImage, documentUri);
        }
    }

    /**
     * Displays a text dialog input window for configuring the Raspberry Pi sync server IP destination.
     */
    private void showSetIpDialog() {
        EditText inputField = new EditText(this);
        inputField.setText(sharedPreferences.getString("sync_server_url", ""));
        new AlertDialog.Builder(this)
                .setTitle("Sync Server URL")
                .setView(inputField)
                .setPositiveButton("Save", (dialog, which) -> {
                    String url = inputField.getText().toString().trim();
                    sharedPreferences.edit().putString("sync_server_url", url).apply();
                    Toast.makeText(this, "Server URL Saved!", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private AlertDialog syncProgressDialog;
    private TextView syncStatusTextView;
    private ProgressBar syncProgressBar;

    /**
     * Coordinates the background SyncManager execution process, revealing progress updates
     * and handling server network connection results inside dialog elements.
     */
    private void runSync() {
        String serverUrl = sharedPreferences.getString("sync_server_url", "");
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please set server IP first!", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        syncStatusTextView = new TextView(this);
        syncStatusTextView.setText("Preparing synchronization...");
        syncStatusTextView.setTextColor(0xFFFFFFFF);
        syncStatusTextView.setTextSize(16);
        layout.addView(syncStatusTextView);

        syncProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        syncProgressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressParams.topMargin = (int) (15 * getResources().getDisplayMetrics().density);
        syncProgressBar.setLayoutParams(progressParams);

        if (syncProgressBar.getIndeterminateDrawable() != null) {
            syncProgressBar.getIndeterminateDrawable().setColorFilter(0xFFa084dc, PorterDuff.Mode.SRC_IN);
        }
        if (syncProgressBar.getProgressDrawable() != null) {
            syncProgressBar.getProgressDrawable().setColorFilter(0xFFa084dc, PorterDuff.Mode.SRC_IN);
        }
        layout.addView(syncProgressBar);

        syncProgressDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Library Sync")
                .setView(layout)
                .setCancelable(false)
                .create();

        syncProgressDialog.show();

        SyncManager.startSync(this, serverUrl, allSongs, new SyncManager.SyncCallback() {
            @Override
            public void onProgress(int progress, int max, String message) {
                runOnUiThread(() -> {
                    if (syncProgressDialog != null && syncProgressDialog.isShowing()) {
                        syncStatusTextView.setText(message);
                        if (max > 0) {
                            syncProgressBar.setIndeterminate(false);
                            syncProgressBar.setMax(max);
                            syncProgressBar.setProgress(progress);
                        } else {
                            syncProgressBar.setIndeterminate(true);
                        }
                    }
                });
            }

            @Override
            public void onComplete(String result) {
                runOnUiThread(() -> {
                    if (syncProgressDialog != null && syncProgressDialog.isShowing()) {
                        syncProgressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, "Sync Complete: " + result, Toast.LENGTH_LONG).show();
                    loadMusic();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (syncProgressDialog != null && syncProgressDialog.isShowing()) {
                        syncProgressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, "Sync Error: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Releases active visualizers, terminates bound service connections,
     * and shuts down image loading thread executors.
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isPlayerExpanded", fullPlayerScreenContainer.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState.getBoolean("isPlayerExpanded", false)) {
            fullPlayerScreenContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        if (audioVisualizer != null) {
            try {
                audioVisualizer.setEnabled(false);
            } catch (Exception ignored) {}
            audioVisualizer.release();
            audioVisualizer = null;
        }
        imageExecutor.shutdown();
        seekHandler.removeCallbacks(updateSeekBarTask);
    }

    /**
     * Displays the parent settings list dialog window.
     */
    private void showMainSettingsDialog() {
        String[] mainOptions = {
                "Sync & Server Settings",
                "Visualizer & Display Options",
                "Backup & Portability Profiles",
                "Audio & Equalizer Options"
        };

        new AlertDialog.Builder(this)
                .setTitle("VibeStation Settings")
                .setItems(mainOptions, (dialog, which) -> {
                    if (which == 0) {
                        showSyncSettingsDialog();
                    } else if (which == 1) {
                        showVisualSettingsDialog();
                    } else if (which == 2) {
                        showBackupSettingsDialog();
                    } else if (which == 3) {
                        showEqualizerSettingsDialog();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Displays the sync and server settings dialog options.
     */
    private void showSyncSettingsDialog() {
        String[] syncOptions = {
                "Sync Now (Raspberry Pi)",
                "Set Sync Server IP Address",
                "Copy Web URL"
        };

        new AlertDialog.Builder(this)
                .setTitle("Sync & Server")
                .setItems(syncOptions, (dialog, which) -> {
                    if (which == 0) {
                        runSync();
                    } else if (which == 1) {
                        showSetIpDialog();
                    } else if (which == 2) {
                        String serverUrl = sharedPreferences.getString("sync_server_url", "");
                        if (!serverUrl.isEmpty()) {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            android.content.ClipData clip = android.content.ClipData.newPlainText("Web URL", serverUrl);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(this, "Copied URL: " + serverUrl, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(this, "Please set server IP first!", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Back", (dialog, which) -> showMainSettingsDialog())
                .show();
    }

    /**
     * Displays the visualizer display and hardware refresh rate toggle configurations.
     */
    private void showVisualSettingsDialog() {
        boolean is120 = sharedPreferences.getBoolean("120hz", true);
        boolean adaptiveBg = sharedPreferences.getBoolean("adaptive_bg", true);
        boolean showVis = sharedPreferences.getBoolean("show_visualizer", true);

        String[] visualOptions = {
                showVis ? "Disable Visualizer Wave" : "Enable Visualizer Wave",
                adaptiveBg ? "Disable Adaptive Background" : "Enable Adaptive Background",
                is120 ? "Disable 120Hz Refresh Rate" : "Enable 120Hz Refresh Rate"
        };

        new AlertDialog.Builder(this)
                .setTitle("Visualizer & Display")
                .setItems(visualOptions, (dialog, which) -> {
                    if (which == 0) {
                        boolean newValue = !showVis;
                        sharedPreferences.edit().putBoolean("show_visualizer", newValue).apply();
                        audioVisualizerView.setVisibility(newValue ? View.VISIBLE : View.GONE);
                        Toast.makeText(this, "Visualizer " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        boolean newValue = !adaptiveBg;
                        sharedPreferences.edit().putBoolean("adaptive_bg", newValue).apply();
                        Toast.makeText(this, "Adaptive Background " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                        if (audioService != null && audioService.getCurrentArt() != null) {
                            onTrackChanged(audioService.getCurrentSong(), audioService.getCurrentArt());
                        }
                    } else if (which == 2) {
                        boolean newValue = !is120;
                        sharedPreferences.edit().putBoolean("120hz", newValue).apply();
                        applyRefreshRate(newValue);
                        Toast.makeText(this, "120Hz " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Back", (dialog, which) -> showMainSettingsDialog())
                .show();
    }

    /**
     * Displays import/export profile backup action dialog selections.
     */
    private void showBackupSettingsDialog() {
        String[] backupOptions = {
                "Export Playlists Backup",
                "Import Playlists Backup"
        };

        new AlertDialog.Builder(this)
                .setTitle("Backup Profiles")
                .setItems(backupOptions, (dialog, which) -> {
                    if (which == 0) {
                        backupFileLauncher.launch("VibeStation_Backup.txt");
                    } else if (which == 1) {
                        restoreFileLauncher.launch(new String[]{"text/plain"});
                    }
                })
                .setNegativeButton("Back", (dialog, which) -> showMainSettingsDialog())
                .show();
    }

    /**
     * Displays the equalizer settings dialog, allowing users to adjust frequency bands.
     */
    private void showEqualizerSettingsDialog() {
        if (audioService == null || audioService.getEqualizer() == null) {
            Toast.makeText(this, "Equalizer not supported or audio not ready.", Toast.LENGTH_SHORT).show();
            return;
        }

        Equalizer equalizer = audioService.getEqualizer();
        View view = getLayoutInflater().inflate(R.layout.dialog_equalizer, null);
        
        SwitchCompat switchEqualizer = view.findViewById(R.id.switchEqualizer);
        LinearLayout bandsContainer = view.findViewById(R.id.equalizerBandsContainer);
        Button btnReset = view.findViewById(R.id.btnResetEqualizer);
        Button btnClose = view.findViewById(R.id.btnCloseEqualizer);
        
        boolean isEnabled = sharedPreferences.getBoolean("eq_enabled", false);
        try {
            equalizer.setEnabled(isEnabled);
        } catch (Exception e) {
            // Ignore if setting enable fails
        }
        switchEqualizer.setChecked(isEnabled);
        
        switchEqualizer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try {
                equalizer.setEnabled(isChecked);
                sharedPreferences.edit().putBoolean("eq_enabled", isChecked).apply();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to toggle equalizer.", Toast.LENGTH_SHORT).show();
            }
        });
        
        short bands = equalizer.getNumberOfBands();
        final short minEQLevel = equalizer.getBandLevelRange()[0];
        final short maxEQLevel = equalizer.getBandLevelRange()[1];
        
        for (short i = 0; i < bands; i++) {
            final short band = i;
            
            TextView freqTextView = new TextView(this);
            freqTextView.setText((equalizer.getCenterFreq(band) / 1000) + " Hz");
            freqTextView.setTextColor(android.graphics.Color.WHITE);
            freqTextView.setPadding(0, 16, 0, 0);
            
            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(maxEQLevel - minEQLevel);
            
            int savedLevel = sharedPreferences.getInt("eq_band_" + band, equalizer.getBandLevel(band));
            try {
                equalizer.setBandLevel(band, (short) savedLevel);
            } catch (Exception e) {
                savedLevel = equalizer.getBandLevel(band);
            }
            seekBar.setProgress(savedLevel - minEQLevel);
            
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        short newLevel = (short) (progress + minEQLevel);
                        try {
                            equalizer.setBandLevel(band, newLevel);
                            sharedPreferences.edit().putInt("eq_band_" + band, newLevel).apply();
                        } catch (Exception e) {
                            // Ignored
                        }
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            
            bandsContainer.addView(freqTextView);
            bandsContainer.addView(seekBar);
        }
        
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();
                
        btnReset.setOnClickListener(v -> {
            for (short i = 0; i < bands; i++) {
                try {
                    equalizer.setBandLevel(i, (short) 0);
                    sharedPreferences.edit().putInt("eq_band_" + i, 0).apply();
                } catch (Exception e) {
                    // Ignored
                }
            }
            dialog.dismiss();
            showEqualizerSettingsDialog();
        });
        
        btnClose.setOnClickListener(v -> {
            dialog.dismiss();
            showMainSettingsDialog();
        });
        
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    /**
     * Checks if the app has MANAGE_EXTERNAL_STORAGE permission on Android 11+.
     * If not, prompts the user to grant it.
     * @return True if granted or SDK < R, false otherwise.
     */
    private boolean checkManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                android.content.Intent intent = new android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return false;
            }
        }
        return true;
    }

    /**
     * Shows a dialog to edit a song's metadata.
     *
     * @param song The song to edit.
     */
    private void showEditSongMetadataDialog(Song song) {
        if (!checkManageStoragePermission()) return;
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Edit Song Metadata");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        
        final EditText titleInput = new EditText(this);
        titleInput.setHint("Song Title");
        titleInput.setText(song.title);
        layout.addView(titleInput);
        
        final EditText artistInput = new EditText(this);
        artistInput.setHint("Artist");
        artistInput.setText(song.artist);
        layout.addView(artistInput);
        
        final EditText albumInput = new EditText(this);
        albumInput.setHint("Album Name");
        albumInput.setText(song.album);
        layout.addView(albumInput);
        
        builder.setView(layout);
        
        builder.setPositiveButton("Save", (dialog, which) -> {
            String newTitle = titleInput.getText().toString();
            String newArtist = artistInput.getText().toString();
            String newAlbum = albumInput.getText().toString();
            
            new android.app.AlertDialog.Builder(this)
                .setTitle("Warning")
                .setMessage("This will permanently overwrite the metadata on the physical file. Are you sure?")
                .setPositiveButton("Yes", (dialog2, which2) -> updateSongMetadata(song, newTitle, newArtist, newAlbum))
                .setNegativeButton("Cancel", null)
                .show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Shows a dialog to edit an album's metadata.
     *
     * @param album The album to edit.
     */
    private void showEditAlbumMetadataDialog(Album album) {
        if (!checkManageStoragePermission()) return;
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Edit Album Metadata");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);
        
        final EditText albumInput = new EditText(this);
        albumInput.setHint("Album Name");
        albumInput.setText(album.name);
        layout.addView(albumInput);
        
        final EditText artistInput = new EditText(this);
        artistInput.setHint("Album Artist");
        artistInput.setText(album.artist);
        layout.addView(artistInput);
        
        builder.setView(layout);
        
        builder.setPositiveButton("Save", (dialog, which) -> {
            String newAlbum = albumInput.getText().toString();
            String newArtist = artistInput.getText().toString();
            
            new android.app.AlertDialog.Builder(this)
                .setTitle("Warning")
                .setMessage("This will permanently overwrite the metadata on all physical files in this album. Are you sure?")
                .setPositiveButton("Yes", (dialog2, which2) -> updateAlbumMetadata(album, newAlbum, newArtist))
                .setNegativeButton("Cancel", null)
                .show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Updates physical file ID3 tags for a song using jaudiotagger and refreshes MediaStore.
     *
     * @param song The song model to update.
     * @param newTitle The new song title.
     * @param newArtist The new artist name.
     * @param newAlbum The new album name.
     */
    private void updateSongMetadata(Song song, String newTitle, String newArtist, String newAlbum) {
        MediaMetadataUtil.updateSongMetadata(this, song, newTitle, newArtist, newAlbum, this::loadMusic);
    }

    /**
     * Updates physical file ID3 tags for all songs in an album using jaudiotagger.
     *
     * @param album The album model to update.
     * @param newAlbum The new album name.
     * @param newArtist The new artist name.
     */
    private void updateAlbumMetadata(Album album, String newAlbum, String newArtist) {
        MediaMetadataUtil.updateAlbumMetadata(this, album, newAlbum, newArtist, this::loadMusic);
    }

    /**
     * Updates physical file ID3 artwork tags for all songs in an album.
     *
     * @param album The album model to update.
     * @param imageUri The new image URI.
     */
    private void updateAlbumArt(Album album, Uri imageUri) {
        activeAlbumForImage = null;
        MediaMetadataUtil.updateAlbumArt(this, album, imageUri, this::loadMusic);
    }

    /**
     * Deletes all physical files associated with an album and triggers a MediaStore scan.
     *
     * @param album The album model to delete.
     */
    private void deleteAlbum(Album album) {
        MediaMetadataUtil.deleteAlbum(this, album, this::loadMusic);
    }
}