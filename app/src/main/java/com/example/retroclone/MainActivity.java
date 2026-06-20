package com.example.retroclone;

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
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.collection.LruCache;
import androidx.palette.graphics.Palette;

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

public class MainActivity extends AppCompatActivity implements AudioService.ServiceCallback {

    // Audio Playback Content Lists
    private final ArrayList<Models.Song> allSongs = new ArrayList<>();
    private final ArrayList<Models.Album> allAlbums = new ArrayList<>();
    private final ArrayList<Models.Playlist> allPlaylists = new ArrayList<>();
    private final ArrayList<Models.Song> displaySongs = new ArrayList<>();
    private final ArrayList<Models.Album> displayAlbums = new ArrayList<>();
    private final ArrayList<Models.Playlist> displayPlaylists = new ArrayList<>();
    private final ArrayList<Models.Song> displayDetailSongs = new ArrayList<>();

    // Playlist Target References
    private Models.Playlist activePlaylistForImage;
    private Models.Playlist currentOpenPlaylist;

    // Selection Queue
    private boolean isSelectionMode = false;
    private final HashSet<Models.Song> selectedSongs = new HashSet<>();

    // UI Widgets
    private GridView albumsGridView;
    private GridView playlistsGridView;
    private ListView libraryListView;
    private ListView detailSongsListView;
    
    private View playlistsPageContainer;
    private View bottomPlayerContainer;
    private View fullPlayerScreenContainer;
    private View expandedDetailsContainer;
    private View rootRelativeLayout;
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

    private SeekBar seekBarView;
    private EditText searchEditText;
    private VisualizerView audioVisualizerView;

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
    private LruCache<String, Bitmap> artworkCache;
    private Visualizer audioVisualizer;

    // File / Image Launchers
    private ActivityResultLauncher<String[]> imagePickerLauncher;
    private ActivityResultLauncher<String> backupFileLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;

    // Image Quality Modes
    private static final int QUALITY_LOW = 8;
    private static final int QUALITY_MED = 2;
    private static final int QUALITY_HIGH = 1;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
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
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Configure dynamic bitmap cache based on runtime hardware memory
        final int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        artworkCache = new LruCache<String, Bitmap>(maxMemoryKb / 4) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        sharedPreferences = getSharedPreferences("RetroPrefs", MODE_PRIVATE);
        applyRefreshRate(sharedPreferences.getBoolean("120hz", true));

        setupViews();
        setupAdapters();
        setupLaunchers();

        // Launch and bind background Audio Service
        Intent serviceIntent = new Intent(this, AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // System back navigation handling
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
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        checkPermissions();
    }

    private void triggerHapticFeedback(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

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

    private void setupViews() {
        rootRelativeLayout = findViewById(R.id.rootLayout);
        albumsGridView = findViewById(R.id.gridAlbums);
        libraryListView = findViewById(R.id.listLibrary);
        playlistsGridView = findViewById(R.id.gridPlaylists);
        playlistsPageContainer = findViewById(R.id.pagePlaylists);
        expandedDetailsContainer = findViewById(R.id.expandedDetailsView);
        detailSongsListView = findViewById(R.id.listDetailSongs);
        detailTitleTextView = findViewById(R.id.txtDetailTitle);
        detailCoverImageView = findViewById(R.id.imgDetailCover);
        bottomPlayerContainer = findViewById(R.id.bottomPlayer);
        fullPlayerScreenContainer = findViewById(R.id.fullPlayerScreen);
        miniTitleTextView = findViewById(R.id.txtMiniTitle);
        miniArtistTextView = findViewById(R.id.txtMiniArtist);
        miniArtImageView = findViewById(R.id.imgMiniArt);
        miniPlayButton = findViewById(R.id.btnMiniPlay);
        fullTitleTextView = findViewById(R.id.txtFullTitle);
        fullArtistTextView = findViewById(R.id.txtFullArtist);
        fullArtImageView = findViewById(R.id.imgFullArt);
        fullPlayButton = findViewById(R.id.btnFullPlay);
        seekBarView = findViewById(R.id.seekBar);
        currentTimeTextView = findViewById(R.id.txtCurrentTime);
        totalTimeTextView = findViewById(R.id.txtTotalTime);
        searchEditText = findViewById(R.id.editSearch);
        audioVisualizerView = findViewById(R.id.visualizerView);

        topBarContainer = findViewById(R.id.topBar);
        selectionBarContainer = findViewById(R.id.selectionBar);
        selectionCountTextView = findViewById(R.id.txtSelectionCount);
        deleteSelectionButton = findViewById(R.id.btnDeleteSelection);

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

        findViewById(R.id.btnNext).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) {
                audioService.playNext();
            }
        });
        findViewById(R.id.btnPrev).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) {
                audioService.playPrev();
            }
        });
        miniPlayButton.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) {
                audioService.togglePlayPause();
            }
        });
        fullPlayButton.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            if (isBound) {
                audioService.togglePlayPause();
            }
        });

        bottomPlayerContainer.setOnClickListener(view -> {
            triggerHapticFeedback(view);
            fullPlayerScreenContainer.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.btnCollapsePlayer).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            fullPlayerScreenContainer.setVisibility(View.GONE);
        });

        findViewById(R.id.btnSettings).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            boolean is120 = sharedPreferences.getBoolean("120hz", true);
            boolean adaptiveBg = sharedPreferences.getBoolean("adaptive_bg", true);
            boolean showVis = sharedPreferences.getBoolean("show_visualizer", true);

            String[] options = {
                    "Sync Now (Raspberry Pi)",
                    "Set Sync Server IP",
                    "Backup Playlists (Export)",
                    "Restore Playlists (Import)",
                    is120 ? "Disable 120Hz Mode" : "Enable 120Hz Mode",
                    adaptiveBg ? "Disable Adaptive Background" : "Enable Adaptive Background",
                    showVis ? "Disable Visualizer Wave" : "Enable Visualizer Wave"
            };

            new AlertDialog.Builder(this)
                    .setTitle("VibeStation Settings")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            runSync();
                        } else if (which == 1) {
                            showSetIpDialog();
                        } else if (which == 2) {
                            backupFileLauncher.launch("VibeStation_Backup.txt");
                        } else if (which == 3) {
                            restoreFileLauncher.launch(new String[]{"text/plain"});
                        } else if (which == 4) {
                            boolean newValue = !is120;
                            sharedPreferences.edit().putBoolean("120hz", newValue).apply();
                            applyRefreshRate(newValue);
                            Toast.makeText(this, "120Hz " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                        } else if (which == 5) {
                            boolean newValue = !adaptiveBg;
                            sharedPreferences.edit().putBoolean("adaptive_bg", newValue).apply();
                            Toast.makeText(this, "Adaptive Background " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                            if (audioService != null && audioService.getCurrentArt() != null) {
                                onTrackChanged(audioService.getCurrentSong(), audioService.getCurrentArt());
                            }
                        } else if (which == 6) {
                            boolean newValue = !showVis;
                            sharedPreferences.edit().putBoolean("show_visualizer", newValue).apply();
                            audioVisualizerView.setVisibility(newValue ? View.VISIBLE : View.GONE);
                            Toast.makeText(this, "Visualizer " + (newValue ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                        }
                    }).show();
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

            int itemId = menuItem.getItemId();
            if (itemId == R.id.nav_albums) {
                albumsGridView.setVisibility(View.VISIBLE);
            } else if (itemId == R.id.nav_library) {
                libraryListView.setVisibility(View.VISIBLE);
            } else if (itemId == R.id.nav_playlists) {
                playlistsPageContainer.setVisibility(View.VISIBLE);
            }
            return true;
        });

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence query, int start, int before, int count) {
                filterData(query.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void afterTextChanged(Editable s) {}
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

        findViewById(R.id.btnCreatePlaylist).setOnClickListener(view -> {
            triggerHapticFeedback(view);
            EditText inputField = new EditText(this);
            new AlertDialog.Builder(this)
                    .setTitle("New Playlist")
                    .setView(inputField)
                    .setPositiveButton("Create", (dialog, which) -> {
                        Models.Playlist newPlaylist = new Models.Playlist(inputField.getText().toString(), null);
                        allPlaylists.add(newPlaylist);
                        savePlaylists();
                        filterData(searchEditText.getText().toString());
                        activePlaylistForImage = newPlaylist;
                        imagePickerLauncher.launch(new String[]{"image/*"});
                    }).show();
        });

        seekBarView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isBound) {
                    audioService.seekTo(progress);
                    currentTimeTextView.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupVisualizer() {
        if (!isBound || audioService.getAudioSessionId() == 0) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        try {
            if (audioVisualizer != null) {
                audioVisualizer.release();
            }

            audioVisualizer = new Visualizer(audioService.getAudioSessionId());
            audioVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);

            audioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {}

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fftData, int samplingRate) {
                    audioVisualizerView.updateVisualizer(fftData);
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true);

            audioVisualizer.setEnabled(true);
        } catch (Exception ignored) {}
    }

    @Override
    public void onTrackChanged(Models.Song song, Bitmap albumArt) {
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

            Palette.from(albumArt).generate(palette -> {
                if (palette == null) return;
                int vibrantColor = palette.getVibrantColor(0xFFFFFFFF);
                seekBarView.getThumb().setTint(vibrantColor);
                audioVisualizerView.setColor(vibrantColor);

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
            miniArtImageView.setImageResource(android.R.drawable.ic_menu_gallery);
            fullArtImageView.setImageResource(android.R.drawable.ic_menu_gallery);
            seekBarView.getThumb().setTint(0xFFFFFFFF);
            audioVisualizerView.setColor(0xFFFFFFFF);
            fullPlayerScreenContainer.setBackgroundColor(0xFF000000);
        }

        if (isBound) {
            seekBarView.setMax(audioService.getDuration());
            totalTimeTextView.setText(formatTime(audioService.getDuration()));
        }
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        miniPlayButton.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        fullPlayButton.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        if (isPlaying) {
            seekHandler.removeCallbacks(updateSeekBarTask);
            seekHandler.postDelayed(updateSeekBarTask, 500);
        } else {
            seekHandler.removeCallbacks(updateSeekBarTask);
        }
    }

    private final Runnable updateSeekBarTask = new Runnable() {
        @Override
        public void run() {
            if (isBound && audioService.isPlaying()) {
                int currentProgressMs = audioService.getCurrentPosition();
                seekBarView.setProgress(currentProgressMs);
                currentTimeTextView.setText(formatTime(currentProgressMs));
                seekHandler.postDelayed(this, 1000);
            }
        }
    };

    private void playAudio(ArrayList<Models.Song> queue, int position) {
        if (isBound) {
            audioService.setQueueAndPlay(queue, position);
        } else {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSelectionMode(Models.Song song) {
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

    private void clearSelection() {
        isSelectionMode = false;
        selectedSongs.clear();
        selectionBarContainer.setVisibility(View.GONE);
        topBarContainer.setVisibility(View.VISIBLE);
        refreshAllAdapters();
    }

    private void refreshAllAdapters() {
        if (librarySongAdapter != null) librarySongAdapter.notifyDataSetChanged();
        if (detailSongListAdapter != null) detailSongListAdapter.notifyDataSetChanged();
        if (albumListAdapter != null) albumListAdapter.notifyDataSetChanged();
        if (playlistListAdapter != null) playlistListAdapter.notifyDataSetChanged();
    }

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
                        EditText inputField = new EditText(this);
                        new AlertDialog.Builder(this)
                                .setTitle("New Playlist Name")
                                .setView(inputField)
                                .setPositiveButton("Create", (dialog2, which2) -> {
                                    Models.Playlist newPlaylist = new Models.Playlist(inputField.getText().toString(), null);
                                    newPlaylist.songs.addAll(selectedSongs);
                                    allPlaylists.add(newPlaylist);
                                    savePlaylists();
                                    filterData("");
                                    Toast.makeText(this, "Created & Added " + selectedSongs.size() + " songs!", Toast.LENGTH_SHORT).show();
                                    clearSelection();
                                }).show();
                    } else {
                        Models.Playlist targetPlaylist = allPlaylists.get(which - 1);
                        int addedCount = 0;
                        int duplicateCount = 0;
                        for (Models.Song selectedSong : selectedSongs) {
                            boolean songExists = false;
                            for (Models.Song existingSong : targetPlaylist.songs) {
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

    private void batchDeleteFromPlaylist() {
        if (currentOpenPlaylist == null || selectedSongs.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + selectedSongs.size() + " songs?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    for (Models.Song selected : selectedSongs) {
                        currentOpenPlaylist.songs.removeIf(song -> song.id.equals(selected.id));
                    }
                    savePlaylists();
                    filterData("");
                    clearSelection();
                    openDetailView(currentOpenPlaylist.name, currentOpenPlaylist.songs, true, currentOpenPlaylist);
                }).setNegativeButton("No", null).show();
    }

    private void setupAdapters() {
        librarySongAdapter = new SongAdapter(displaySongs);
        libraryListView.setAdapter(librarySongAdapter);

        albumListAdapter = new AlbumAdapter();
        albumsGridView.setAdapter(albumListAdapter);
        albumsGridView.setOnItemClickListener((parent, view, position, id) -> {
            triggerHapticFeedback(view);
            openDetailView(displayAlbums.get(position).name, displayAlbums.get(position).songs, false, null);
        });

        playlistListAdapter = new PlaylistAdapter();
        playlistsGridView.setAdapter(playlistListAdapter);
        playlistsGridView.setOnItemClickListener((parent, view, position, id) -> {
            triggerHapticFeedback(view);
            openDetailView(displayPlaylists.get(position).name, displayPlaylists.get(position).songs, true, displayPlaylists.get(position));
        });

        playlistsGridView.setOnItemLongClickListener((parent, view, position, id) -> {
            triggerHapticFeedback(view);
            Models.Playlist playlist = displayPlaylists.get(position);
            String[] options = {"Rename", "Change Cover", "Delete"};
            new AlertDialog.Builder(this)
                    .setTitle(playlist.name)
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            EditText inputField = new EditText(this);
                            inputField.setText(playlist.name);
                            new AlertDialog.Builder(this)
                                    .setTitle("Rename")
                                    .setView(inputField)
                                    .setPositiveButton("Save", (dialogInner, whichInner) -> {
                                        playlist.name = inputField.getText().toString();
                                        savePlaylists();
                                        filterData(searchEditText.getText().toString());
                                    }).show();
                        } else if (which == 1) {
                            activePlaylistForImage = playlist;
                            imagePickerLauncher.launch(new String[]{"image/*"});
                        } else if (which == 2) {
                            allPlaylists.remove(playlist);
                            savePlaylists();
                            filterData(searchEditText.getText().toString());
                        }
                    }).show();
            return true;
        });

        detailSongListAdapter = new DetailSongAdapter();
        detailSongsListView.setAdapter(detailSongListAdapter);
    }

    private void loadArtAsync(ImageView imageView, String artworkPath, boolean isUri, int qualityMode, Bitmap preloadedFallback) {
        if (artworkPath == null || artworkPath.isEmpty()) {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery);
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
        } else {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        imageExecutor.execute(() -> {
            Bitmap decodedBitmap = null;
            try {
                if (isUri) {
                    InputStream inputStream = getContentResolver().openInputStream(Uri.parse(artworkPath));
                    BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                    decodeOptions.inSampleSize = qualityMode;
                    decodedBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions);
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } else {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(artworkPath);
                    byte[] rawPictureData = retriever.getEmbeddedPicture();
                    if (rawPictureData != null) {
                        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                        decodeOptions.inSampleSize = qualityMode;
                        decodedBitmap = BitmapFactory.decodeByteArray(rawPictureData, 0, rawPictureData.length, decodeOptions);
                    }
                    retriever.release();
                }
            } catch (Exception ignored) {}

            if (decodedBitmap != null) {
                artworkCache.put(cacheKey, decodedBitmap);
                final Bitmap finalBitmap = decodedBitmap;
                mainHandler.post(() -> {
                    if (artworkPath.equals(imageView.getTag())) {
                        imageView.setImageBitmap(finalBitmap);
                    }
                });
            }
        });
    }

    static class SongViewHolder {
        CheckBox selectionCheckBox;
        TextView titleTextView;
        TextView artistTextView;
        ImageView artworkImageView;
    }

    private class SongAdapter extends android.widget.ArrayAdapter<Models.Song> {
        final ArrayList<Models.Song> songsList;

        public SongAdapter(ArrayList<Models.Song> list) {
            super(MainActivity.this, R.layout.item_song, list);
            this.songsList = list;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
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

            Models.Song currentSong = songsList.get(position);
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
                    playAudio(songsList, position);
                }
            });
            convertView.setOnLongClickListener(clickedView -> {
                triggerHapticFeedback(clickedView);
                if (!isSelectionMode) {
                    toggleSelectionMode(currentSong);
                }
                return true;
            });
            return convertView;
        }
    }

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

            Models.Song currentSong = displayDetailSongs.get(position);
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
                    playAudio(displayDetailSongs, position);
                }
            });
            convertView.setOnLongClickListener(clickedView -> {
                triggerHapticFeedback(clickedView);
                if (!isSelectionMode) {
                    toggleSelectionMode(currentSong);
                }
                return true;
            });
            return convertView;
        }
    }

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
            Models.Album currentAlbum = displayAlbums.get(position);
            ((TextView) convertView.findViewById(R.id.txtGridTitle)).setText(currentAlbum.name);
            ((TextView) convertView.findViewById(R.id.txtGridSub)).setText(currentAlbum.artist);
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

    private class PlaylistAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return displayPlaylists.size();
        }

        @Override
        public Object getItem(int position) {
            return displayPlaylists.get(position);
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
            Models.Playlist currentPlaylist = displayPlaylists.get(position);
            ((TextView) convertView.findViewById(R.id.txtGridTitle)).setText(currentPlaylist.name);
            ((TextView) convertView.findViewById(R.id.txtGridSub)).setText(
                    String.format(Locale.getDefault(), "%d songs", currentPlaylist.songs.size())
            );

            ImageView gridArtImageView = convertView.findViewById(R.id.imgGridArt);
            if (currentPlaylist.imageUri != null && !currentPlaylist.imageUri.isEmpty()) {
                loadArtAsync(gridArtImageView, currentPlaylist.imageUri, true, QUALITY_MED, null);
            } else if (!currentPlaylist.songs.isEmpty()) {
                loadArtAsync(gridArtImageView, currentPlaylist.songs.get(0).path, false, QUALITY_MED, null);
            } else {
                gridArtImageView.setBackgroundColor(0xFF333333);
                gridArtImageView.setImageResource(android.R.drawable.ic_menu_gallery);
            }
            return convertView;
        }
    }

    private void filterData(String query) {
        String trimmedQuery = query.toLowerCase().trim();
        displaySongs.clear();
        displayAlbums.clear();
        displayPlaylists.clear();

        if (trimmedQuery.isEmpty()) {
            displaySongs.addAll(allSongs);
            displayAlbums.addAll(allAlbums);
            displayPlaylists.addAll(allPlaylists);
        } else {
            for (Models.Song song : allSongs) {
                if (song.title.toLowerCase().contains(trimmedQuery) || song.artist.toLowerCase().contains(trimmedQuery)) {
                    displaySongs.add(song);
                }
            }
            for (Models.Album album : allAlbums) {
                if (album.name.toLowerCase().contains(trimmedQuery) || album.artist.toLowerCase().contains(trimmedQuery)) {
                    displayAlbums.add(album);
                }
            }
            for (Models.Playlist playlist : allPlaylists) {
                if (playlist.name.toLowerCase().contains(trimmedQuery)) {
                    displayPlaylists.add(playlist);
                }
            }
        }
        refreshAllAdapters();
    }

    private void loadMusic() {
        allSongs.clear();
        HashMap<String, Models.Album> albumMap = new HashMap<>();
        Cursor musicCursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{"%Music/%"},
                null
        );

        if (musicCursor != null && musicCursor.moveToFirst()) {
            do {
                String id = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                String title = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                String artist = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                String path = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                String albumId = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                String albumName = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                long dateAdded = musicCursor.getLong(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED));
                int trackNumber = musicCursor.getInt(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK));

                if (artist.toLowerCase().contains("unknown")) {
                    try {
                        String[] pathSegments = path.split("/");
                        if (pathSegments.length >= 3) {
                            albumName = pathSegments[pathSegments.length - 2];
                            artist = pathSegments[pathSegments.length - 3];
                        }
                    } catch (Exception ignored) {}
                }

                Models.Song song = new Models.Song(id, title, artist, path, albumId, albumName, trackNumber, dateAdded);
                allSongs.add(song);

                if (!albumMap.containsKey(albumId)) {
                    albumMap.put(albumId, new Models.Album(albumId, albumName, artist, dateAdded));
                }
                albumMap.get(albumId).songs.add(song);

            } while (musicCursor.moveToNext());
            musicCursor.close();
        }

        allAlbums.clear();
        allAlbums.addAll(albumMap.values());

        for (Models.Album album : allAlbums) {
            Collections.sort(album.songs, Comparator.comparingInt(song -> song.trackNumber));
        }

        loadPlaylists();
        sortData(0);
        filterData("");
    }

    private void savePlaylists() {
        try {
            JSONArray playlistsJsonArray = new JSONArray();
            for (Models.Playlist playlist : allPlaylists) {
                JSONObject playlistJsonObject = new JSONObject();
                playlistJsonObject.put("name", playlist.name);
                playlistJsonObject.put("imageUri", playlist.imageUri != null ? playlist.imageUri : "");

                JSONArray songsJsonArray = new JSONArray();
                for (Models.Song song : playlist.songs) {
                    JSONObject songJsonObject = new JSONObject();
                    songJsonObject.put("id", song.id);
                    songJsonObject.put("t", song.title);
                    songJsonObject.put("a", song.artist);
                    songsJsonArray.put(songJsonObject);
                }
                playlistJsonObject.put("songData", songsJsonArray);
                playlistsJsonArray.put(playlistJsonObject);
            }
            sharedPreferences.edit().putString("playlists", playlistsJsonArray.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadPlaylists() {
        allPlaylists.clear();
        try {
            JSONArray playlistsJsonArray = new JSONArray(sharedPreferences.getString("playlists", "[]"));
            for (int i = 0; i < playlistsJsonArray.length(); i++) {
                JSONObject playlistJsonObject = playlistsJsonArray.getJSONObject(i);
                Models.Playlist playlist = new Models.Playlist(
                        playlistJsonObject.getString("name"),
                        playlistJsonObject.optString("imageUri", null)
                );
                JSONArray songsJsonArray = playlistJsonObject.getJSONArray("songData");
                for (int j = 0; j < songsJsonArray.length(); j++) {
                    JSONObject songJsonObject = songsJsonArray.getJSONObject(j);
                    String songId = songJsonObject.getString("id");
                    String songTitle = songJsonObject.getString("t");
                    String songArtist = songJsonObject.getString("a");

                    for (Models.Song song : allSongs) {
                        if (song.id.equals(songId) || (song.title.equalsIgnoreCase(songTitle) && song.artist.equalsIgnoreCase(songArtist))) {
                            playlist.songs.add(song);
                            break;
                        }
                    }
                }
                allPlaylists.add(playlist);
            }
        } catch (Exception ignored) {}
    }

    private void sortData(int sortType) {
        Comparator<Models.Song> songComparator = sortType == 0 
                ? (a, b) -> a.title.compareToIgnoreCase(b.title) 
                : sortType == 1 ? (a, b) -> b.title.compareToIgnoreCase(a.title) 
                : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);

        Comparator<Models.Album> albumComparator = sortType == 0 
                ? (a, b) -> a.name.compareToIgnoreCase(b.name) 
                : sortType == 1 ? (a, b) -> b.name.compareToIgnoreCase(a.name) 
                : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);

        Collections.sort(allSongs, songComparator);
        Collections.sort(allAlbums, albumComparator);
    }

    private void openDetailView(String viewTitle, ArrayList<Models.Song> songsList, boolean isPlaylist, Models.Playlist playlistObject) {
        expandedDetailsContainer.setVisibility(View.VISIBLE);
        detailTitleTextView.setText(viewTitle);
        currentOpenPlaylist = isPlaylist ? playlistObject : null;

        if (isPlaylist && playlistObject.imageUri != null) {
            loadArtAsync(detailCoverImageView, playlistObject.imageUri, true, QUALITY_HIGH, null);
        } else if (!songsList.isEmpty()) {
            loadArtAsync(detailCoverImageView, songsList.get(0).path, false, QUALITY_HIGH, null);
        } else {
            detailCoverImageView.setImageResource(android.R.drawable.ic_menu_gallery);
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
                ArrayList<Models.Song> shuffledQueue = new ArrayList<>(songsList);
                Collections.shuffle(shuffledQueue);
                playAudio(shuffledQueue, 0);
            }
        });
        if (isSelectionMode) {
            deleteSelectionButton.setVisibility(currentOpenPlaylist != null ? View.VISIBLE : View.GONE);
        }
    }

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

    private String formatTime(int positionMs) {
        int seconds = (positionMs / 1000) % 60;
        int minutes = (positionMs / (1000 * 60)) % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private void setupLaunchers() {
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), documentUri -> {
            if (documentUri != null && activePlaylistForImage != null) {
                try {
                    getContentResolver().takePersistableUriPermission(documentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                activePlaylistForImage.imageUri = documentUri.toString();
                savePlaylists();
                filterData("");
            }
        });

        backupFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), documentUri -> {
            if (documentUri != null) {
                try (OutputStream outputStream = getContentResolver().openOutputStream(documentUri)) {
                    JSONArray playlistsJsonArray = new JSONArray(sharedPreferences.getString("playlists", "[]"));
                    for (int i = 0; i < playlistsJsonArray.length(); i++) {
                        JSONObject playlistJsonObject = playlistsJsonArray.getJSONObject(i);
                        String imageUri = playlistJsonObject.optString("imageUri", "");
                        if (!imageUri.isEmpty()) {
                            playlistJsonObject.put("b64", getBase64Image(Uri.parse(imageUri)));
                        }
                    }
                    if (outputStream != null) {
                        outputStream.write(playlistsJsonArray.toString().getBytes());
                    }
                    Toast.makeText(this, "Export Ready!", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });

        restoreFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), documentUri -> {
            if (documentUri != null) {
                try (InputStream inputStream = getContentResolver().openInputStream(documentUri);
                     BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
                    StringBuilder stringBuilder = new StringBuilder();
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line);
                    }
                    sharedPreferences.edit().putString("playlists", stringBuilder.toString()).apply();
                    loadPlaylists();
                    filterData("");
                    Toast.makeText(this, "Restore Successful!", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    private String getBase64Image(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) return "";
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, 400, 400, true);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT);
        } catch (Exception e) {
            return "";
        }
    }

    private void showSetIpDialog() {
        EditText inputField = new EditText(this);
        inputField.setText(sharedPreferences.getString("sync_server_url", "http://192.168.50.199:1337"));
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

    private void runSync() {
        String serverUrl = sharedPreferences.getString("sync_server_url", "http://192.168.50.199:1337");
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        if (audioVisualizer != null) {
            audioVisualizer.release();
        }
        imageExecutor.shutdown();
        seekHandler.removeCallbacks(updateSeekBarTask);
    }
}