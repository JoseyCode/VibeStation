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
import android.widget.*;

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

    private ArrayList<Models.Song> allSongs = new ArrayList<>();
    private ArrayList<Models.Album> allAlbums = new ArrayList<>();
    private ArrayList<Models.Playlist> allPlaylists = new ArrayList<>();
    private ArrayList<Models.Song> displaySongs = new ArrayList<>();
    private ArrayList<Models.Album> displayAlbums = new ArrayList<>();
    private ArrayList<Models.Playlist> displayPlaylists = new ArrayList<>();
    private ArrayList<Models.Song> displayDetailSongs = new ArrayList<>();

    private Models.Playlist activePlaylistForImage;
    private Models.Playlist currentOpenPlaylist;

    private boolean isSelectionMode = false;
    private HashSet<Models.Song> selectedSongs = new HashSet<>();

    private GridView gridAlbums, gridPlaylists;
    private ListView listLibrary, listDetailSongs;
    private View pagePlaylists, bottomPlayer, fullPlayerScreen, expandedDetailsView, rootLayout;
    private View topBar, selectionBar;
    private TextView txtSelectionCount, txtDetailTitle, txtMiniTitle, txtMiniArtist, txtFullTitle, txtFullArtist, txtCurrentTime, txtTotalTime;
    private ImageView imgMiniArt, imgFullArt, imgDetailCover;
    private ImageButton btnMiniPlay, btnFullPlay, btnDeleteSelection;
    private SeekBar seekBar;
    private EditText editSearch;
    private VisualizerView visualizerView;

    private SongAdapter libraryAdapter;
    private AlbumAdapter albumAdapter;
    private PlaylistAdapter playlistAdapter;
    private DetailSongAdapter detailSongAdapter;

    private AudioService audioService;
    private boolean isBound = false;
    private Handler seekHandler = new Handler(Looper.getMainLooper());
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;
    private LruCache<String, Bitmap> artCache;
    private Visualizer audioVisualizer;

    private ActivityResultLauncher<String[]> imagePicker;
    private ActivityResultLauncher<String> backupFileLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;

    private static final int QUALITY_LOW = 8;
    private static final int QUALITY_MED = 2;
    private static final int QUALITY_HIGH = 1;

    private ServiceConnection serviceConnection = new ServiceConnection() {
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
        public void onServiceDisconnected(ComponentName arg0) { isBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        artCache = new LruCache<String, Bitmap>(maxMemory / 4) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount() / 1024; }
        };

        prefs = getSharedPreferences("RetroPrefs", MODE_PRIVATE);
        applyRefreshRate(prefs.getBoolean("120hz", true));

        setupViews();
        setupAdapters();
        setupLaunchers();

        Intent intent = new Intent(this, AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (isSelectionMode) { clearSelection(); }
                else if (fullPlayerScreen.getVisibility() == View.VISIBLE) fullPlayerScreen.setVisibility(View.GONE);
                else if (expandedDetailsView.getVisibility() == View.VISIBLE) {
                    expandedDetailsView.setVisibility(View.GONE);
                    currentOpenPlaylist = null;
                }
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        checkPermissions();
    }

    private void vibrate(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }

    private void applyRefreshRate(boolean is120Hz) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            if (is120Hz) {
                android.view.Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
                android.view.Display.Mode bestMode = null;
                for (android.view.Display.Mode mode : modes) {
                    if (bestMode == null || mode.getRefreshRate() > bestMode.getRefreshRate()) bestMode = mode;
                }
                if (bestMode != null) params.preferredDisplayModeId = bestMode.getModeId();
            } else params.preferredDisplayModeId = 0;
            getWindow().setAttributes(params);
        }
    }

    private void setupViews() {
        rootLayout = findViewById(R.id.rootLayout);
        gridAlbums = findViewById(R.id.gridAlbums); listLibrary = findViewById(R.id.listLibrary);
        gridPlaylists = findViewById(R.id.gridPlaylists); pagePlaylists = findViewById(R.id.pagePlaylists);
        expandedDetailsView = findViewById(R.id.expandedDetailsView); listDetailSongs = findViewById(R.id.listDetailSongs);
        txtDetailTitle = findViewById(R.id.txtDetailTitle); imgDetailCover = findViewById(R.id.imgDetailCover);
        bottomPlayer = findViewById(R.id.bottomPlayer); fullPlayerScreen = findViewById(R.id.fullPlayerScreen);
        txtMiniTitle = findViewById(R.id.txtMiniTitle); txtMiniArtist = findViewById(R.id.txtMiniArtist);
        imgMiniArt = findViewById(R.id.imgMiniArt); btnMiniPlay = findViewById(R.id.btnMiniPlay);
        txtFullTitle = findViewById(R.id.txtFullTitle); txtFullArtist = findViewById(R.id.txtFullArtist);
        imgFullArt = findViewById(R.id.imgFullArt); btnFullPlay = findViewById(R.id.btnFullPlay);
        seekBar = findViewById(R.id.seekBar); txtCurrentTime = findViewById(R.id.txtCurrentTime);
        txtTotalTime = findViewById(R.id.txtTotalTime); editSearch = findViewById(R.id.editSearch);
        visualizerView = findViewById(R.id.visualizerView);

        topBar = findViewById(R.id.topBar); selectionBar = findViewById(R.id.selectionBar);
        txtSelectionCount = findViewById(R.id.txtSelectionCount); btnDeleteSelection = findViewById(R.id.btnDeleteSelection);

        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> { vibrate(v); clearSelection(); });
        findViewById(R.id.btnAddSelection).setOnClickListener(v -> { vibrate(v); showBatchAddToPlaylistDialog(); });
        btnDeleteSelection.setOnClickListener(v -> { vibrate(v); batchDeleteFromPlaylist(); });

        findViewById(R.id.btnNext).setOnClickListener(v -> { vibrate(v); if(isBound) audioService.playNext(); });
        findViewById(R.id.btnPrev).setOnClickListener(v -> { vibrate(v); if(isBound) audioService.playPrev(); });
        btnMiniPlay.setOnClickListener(v -> { vibrate(v); if(isBound) audioService.togglePlayPause(); });
        btnFullPlay.setOnClickListener(v -> { vibrate(v); if(isBound) audioService.togglePlayPause(); });

        bottomPlayer.setOnClickListener(v -> { vibrate(v); fullPlayerScreen.setVisibility(View.VISIBLE); });
        findViewById(R.id.btnCollapsePlayer).setOnClickListener(v -> { vibrate(v); fullPlayerScreen.setVisibility(View.GONE); });

        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            vibrate(v);
            boolean is120 = prefs.getBoolean("120hz", true);
            boolean adaptiveBg = prefs.getBoolean("adaptive_bg", true);
            boolean showVis = prefs.getBoolean("show_visualizer", true);

            String[] options = {
                    "Backup Playlists (Export)",
                    "Restore Playlists (Import)",
                    is120 ? "Disable 120Hz Mode" : "Enable 120Hz Mode",
                    adaptiveBg ? "Disable Adaptive Background" : "Enable Adaptive Background",
                    showVis ? "Disable Visualizer Wave" : "Enable Visualizer Wave"
            };

            new AlertDialog.Builder(this).setTitle("VibeStation Settings").setItems(options, (d, w) -> {
                if (w == 0) backupFileLauncher.launch("VibeStation_Backup.txt");
                else if (w == 1) restoreFileLauncher.launch(new String[]{"text/plain"});
                else if (w == 2) {
                    boolean newVal = !is120;
                    prefs.edit().putBoolean("120hz", newVal).apply();
                    applyRefreshRate(newVal);
                    Toast.makeText(this, "120Hz " + (newVal ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                } else if (w == 3) {
                    boolean newVal = !adaptiveBg;
                    prefs.edit().putBoolean("adaptive_bg", newVal).apply();
                    Toast.makeText(this, "Adaptive Background " + (newVal ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                    if (audioService != null && audioService.getCurrentArt() != null) {
                        onTrackChanged(audioService.getCurrentSong(), audioService.getCurrentArt());
                    }
                } else if (w == 4) {
                    boolean newVal = !showVis;
                    prefs.edit().putBoolean("show_visualizer", newVal).apply();
                    visualizerView.setVisibility(newVal ? View.VISIBLE : View.GONE);
                    Toast.makeText(this, "Visualizer " + (newVal ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
                }
            }).show();
        });

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(item -> {
            vibrate(nav);
            fullPlayerScreen.setVisibility(View.GONE);
            gridAlbums.setVisibility(View.GONE); listLibrary.setVisibility(View.GONE);
            pagePlaylists.setVisibility(View.GONE); expandedDetailsView.setVisibility(View.GONE);
            clearSelection();
            currentOpenPlaylist = null;

            if (item.getItemId() == R.id.nav_albums) gridAlbums.setVisibility(View.VISIBLE);
            else if (item.getItemId() == R.id.nav_library) listLibrary.setVisibility(View.VISIBLE);
            else if (item.getItemId() == R.id.nav_playlists) pagePlaylists.setVisibility(View.VISIBLE);
            return true;
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterData(s.toString()); }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.btnSort).setOnClickListener(v -> {
            vibrate(v);
            new AlertDialog.Builder(this).setTitle("Sort").setItems(new String[]{"A-Z", "Z-A", "Newest"}, (d, w) -> { sortData(w); filterData(editSearch.getText().toString()); }).show();
        });

        findViewById(R.id.btnCreatePlaylist).setOnClickListener(v -> {
            vibrate(v);
            EditText in = new EditText(this);
            new AlertDialog.Builder(this).setTitle("New Playlist").setView(in).setPositiveButton("Create", (d, w) -> {
                Models.Playlist newPl = new Models.Playlist(in.getText().toString(), null);
                allPlaylists.add(newPl); savePlaylists(); filterData(editSearch.getText().toString());
                activePlaylistForImage = newPl;
                imagePicker.launch(new String[]{"image/*"});
            }).show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if(fromUser && isBound) { audioService.seekTo(progress); txtCurrentTime.setText(formatTime(progress)); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupVisualizer() {
        if (!isBound || audioService.getAudioSessionId() == 0) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        try {
            if (audioVisualizer != null) audioVisualizer.release();

            audioVisualizer = new Visualizer(audioService.getAudioSessionId());
            audioVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);

            audioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] bytes, int samplingRate) {}

                @Override
                public void onFftDataCapture(Visualizer v, byte[] bytes, int samplingRate) {
                    visualizerView.updateVisualizer(bytes);
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true);

            audioVisualizer.setEnabled(true);
        } catch (Exception e) {}
    }

    @Override
    public void onTrackChanged(Models.Song song, Bitmap art) {
        txtMiniTitle.setText(song.title); txtMiniArtist.setText(song.artist);
        txtFullTitle.setText(song.title); txtFullArtist.setText(song.artist);

        setupVisualizer();

        // Pass the low-res 'art' as the fallback to eliminate the grey flash
        loadArtAsync(imgFullArt, song.path, false, QUALITY_HIGH, art);

        boolean showVis = prefs.getBoolean("show_visualizer", true);
        visualizerView.setVisibility(showVis ? View.VISIBLE : View.GONE);

        if (art != null) {
            imgMiniArt.setImageBitmap(art);

            Palette.from(art).generate(p -> {
                int vibrant = p.getVibrantColor(0xFFFFFFFF);
                seekBar.getThumb().setTint(vibrant);
                visualizerView.setColor(vibrant);

                if (prefs.getBoolean("adaptive_bg", true)) {
                    int dominant = p.getDominantColor(0xFF111111);
                    int muted = p.getDarkMutedColor(0xFF000000);
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{dominant, muted, 0xFF000000}
                    );
                    fullPlayerScreen.setBackground(gd);
                } else {
                    fullPlayerScreen.setBackgroundColor(0xFF000000);
                }
            });
        } else {
            imgMiniArt.setImageResource(android.R.drawable.ic_menu_gallery);
            imgFullArt.setImageResource(android.R.drawable.ic_menu_gallery);
            seekBar.getThumb().setTint(0xFFFFFFFF);
            visualizerView.setColor(0xFFFFFFFF);
            fullPlayerScreen.setBackgroundColor(0xFF000000);
        }

        if (isBound) {
            seekBar.setMax(audioService.getDuration());
            txtTotalTime.setText(formatTime(audioService.getDuration()));
        }
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        btnMiniPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        btnFullPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        if (isPlaying) {
            seekHandler.removeCallbacks(updateSeekBarTask);
            seekHandler.postDelayed(updateSeekBarTask, 500);
        } else {
            seekHandler.removeCallbacks(updateSeekBarTask);
        }
    }

    private Runnable updateSeekBarTask = new Runnable() {
        @Override public void run() {
            if (isBound && audioService.isPlaying()) {
                int pos = audioService.getCurrentPosition();
                seekBar.setProgress(pos);
                txtCurrentTime.setText(formatTime(pos));
                seekHandler.postDelayed(this, 1000);
            }
        }
    };

    private void playAudio(ArrayList<Models.Song> queue, int pos) {
        if (isBound) audioService.setQueueAndPlay(queue, pos);
        else Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
    }

    private void toggleSelectionMode(Models.Song song) {
        if (!isSelectionMode) {
            isSelectionMode = true;
            topBar.setVisibility(View.GONE);
            selectionBar.setVisibility(View.VISIBLE);
        }

        if (selectedSongs.contains(song)) selectedSongs.remove(song);
        else selectedSongs.add(song);

        if (selectedSongs.isEmpty()) clearSelection();
        else {
            txtSelectionCount.setText(selectedSongs.size() + " Selected");
            btnDeleteSelection.setVisibility(currentOpenPlaylist != null ? View.VISIBLE : View.GONE);
            refreshAllAdapters();
        }
    }

    private void clearSelection() {
        isSelectionMode = false;
        selectedSongs.clear();
        selectionBar.setVisibility(View.GONE);
        topBar.setVisibility(View.VISIBLE);
        refreshAllAdapters();
    }

    private void refreshAllAdapters() {
        if(libraryAdapter != null) libraryAdapter.notifyDataSetChanged();
        if(detailSongAdapter != null) detailSongAdapter.notifyDataSetChanged();
        if(albumAdapter != null) albumAdapter.notifyDataSetChanged();
        if(playlistAdapter != null) playlistAdapter.notifyDataSetChanged();
    }

    private void showBatchAddToPlaylistDialog() {
        if (selectedSongs.isEmpty()) return;
        String[] options = new String[allPlaylists.size() + 1];
        options[0] = "(Create Playlist...)";
        for (int i = 0; i < allPlaylists.size(); i++) options[i + 1] = allPlaylists.get(i).name;

        new AlertDialog.Builder(this).setTitle("Add " + selectedSongs.size() + " songs to...").setItems(options, (d, w) -> {
            if (w == 0) {
                EditText in = new EditText(this);
                new AlertDialog.Builder(this).setTitle("New Playlist Name").setView(in).setPositiveButton("Create", (d2, w2) -> {
                    Models.Playlist p = new Models.Playlist(in.getText().toString(), null);
                    p.songs.addAll(selectedSongs);
                    allPlaylists.add(p); savePlaylists(); filterData("");
                    Toast.makeText(this, "Created & Added " + selectedSongs.size() + " songs!", Toast.LENGTH_SHORT).show();
                    clearSelection();
                }).show();
            } else {
                Models.Playlist target = allPlaylists.get(w - 1);
                int added = 0; int dupes = 0;
                for (Models.Song s : selectedSongs) {
                    boolean exists = false;
                    for (Models.Song existing : target.songs) { if (existing.id.equals(s.id)) { exists = true; break; } }
                    if (!exists) { target.songs.add(s); added++; } else { dupes++; }
                }
                savePlaylists(); filterData("");
                String msg = "Added " + added + " songs to " + target.name;
                if (dupes > 0) msg += " (" + dupes + " duplicates skipped)";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                clearSelection();
            }
        }).show();
    }

    private void batchDeleteFromPlaylist() {
        if (currentOpenPlaylist == null || selectedSongs.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Remove " + selectedSongs.size() + " songs?")
                .setPositiveButton("Yes", (d, w) -> {
                    for (Models.Song selected : selectedSongs) {
                        currentOpenPlaylist.songs.removeIf(s -> s.id.equals(selected.id));
                    }
                    savePlaylists(); filterData(""); clearSelection();
                    openDetailView(currentOpenPlaylist.name, currentOpenPlaylist.songs, true, currentOpenPlaylist);
                }).setNegativeButton("No", null).show();
    }

    private void setupAdapters() {
        libraryAdapter = new SongAdapter(displaySongs);
        listLibrary.setAdapter(libraryAdapter);

        albumAdapter = new AlbumAdapter();
        gridAlbums.setAdapter(albumAdapter);
        gridAlbums.setOnItemClickListener((p, v, pos, id) -> { vibrate(v); openDetailView(displayAlbums.get(pos).name, displayAlbums.get(pos).songs, false, null); });

        playlistAdapter = new PlaylistAdapter();
        gridPlaylists.setAdapter(playlistAdapter);
        gridPlaylists.setOnItemClickListener((p, v, pos, id) -> { vibrate(v); openDetailView(displayPlaylists.get(pos).name, displayPlaylists.get(pos).songs, true, displayPlaylists.get(pos)); });

        gridPlaylists.setOnItemLongClickListener((p, v, pos, id) -> {
            vibrate(v);
            Models.Playlist pl = displayPlaylists.get(pos);
            new AlertDialog.Builder(this).setTitle(pl.name).setItems(new String[]{"Rename", "Change Cover", "Delete"}, (d, w) -> {
                if (w == 0) {
                    EditText in = new EditText(this); in.setText(pl.name);
                    new AlertDialog.Builder(this).setTitle("Rename").setView(in).setPositiveButton("Save", (dx, wx) -> { pl.name = in.getText().toString(); savePlaylists(); filterData(editSearch.getText().toString()); }).show();
                }
                else if (w == 1) { activePlaylistForImage = pl; imagePicker.launch(new String[]{"image/*"}); }
                else if (w == 2) { allPlaylists.remove(pl); savePlaylists(); filterData(editSearch.getText().toString()); }
            }).show();
            return true;
        });

        detailSongAdapter = new DetailSongAdapter();
        listDetailSongs.setAdapter(detailSongAdapter);
    }

    // UPDATED: Now accepts a "preloadedFallback" so it doesn't flash the grey gallery icon
    private void loadArtAsync(ImageView imgView, String path, boolean isUri, int qualityMode, Bitmap preloadedFallback) {
        if (path == null || path.isEmpty()) { imgView.setImageResource(android.R.drawable.ic_menu_gallery); return; }
        String cacheKey = path + "_" + qualityMode;
        Bitmap cached = artCache.get(cacheKey);
        if (cached != null) { imgView.setImageBitmap(cached); return; }

        imgView.setTag(path);

        // Show the preloaded low-res image instead of the grey placeholder if it's available
        if (preloadedFallback != null) {
            imgView.setImageBitmap(preloadedFallback);
        } else {
            imgView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        imageExecutor.execute(() -> {
            Bitmap bm = null;
            try {
                if (isUri) {
                    InputStream is = getContentResolver().openInputStream(Uri.parse(path));
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = qualityMode;
                    bm = BitmapFactory.decodeStream(is, null, o);
                    if(is!=null) is.close();
                } else {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(path);
                    byte[] data = mmr.getEmbeddedPicture();
                    if (data != null) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = qualityMode;
                        bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    }
                    mmr.release();
                }
            } catch (Exception e) {}

            if (bm != null) {
                artCache.put(cacheKey, bm); final Bitmap fbm = bm;
                mainHandler.post(() -> { if (path.equals(imgView.getTag())) imgView.setImageBitmap(fbm); });
            }
        });
    }

    class SongViewHolder { CheckBox chkSelect; TextView txtTitle, txtArtist; ImageView imgArt; }

    private class SongAdapter extends ArrayAdapter<Models.Song> {
        ArrayList<Models.Song> list;
        public SongAdapter(ArrayList<Models.Song> list) { super(MainActivity.this, R.layout.item_song, list); this.list = list; }

        @NonNull @Override public View getView(int pos, View v, @NonNull ViewGroup p) {
            SongViewHolder h;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_song, p, false);
                h = new SongViewHolder();
                h.chkSelect = v.findViewById(R.id.chkSelect);
                h.txtTitle = v.findViewById(R.id.txtTitle); h.txtArtist = v.findViewById(R.id.txtArtist);
                h.imgArt = v.findViewById(R.id.imgArt);
                v.setTag(h);
            } else h = (SongViewHolder) v.getTag();

            Models.Song s = list.get(pos);
            h.txtTitle.setText(s.title); h.txtArtist.setText(s.artist);
            loadArtAsync(h.imgArt, s.path, false, QUALITY_LOW, null);

            if (isSelectionMode) {
                h.chkSelect.setVisibility(View.VISIBLE);
                h.chkSelect.setChecked(selectedSongs.contains(s));
            } else {
                h.chkSelect.setVisibility(View.GONE);
            }

            v.setOnClickListener(view -> { vibrate(view); if (isSelectionMode) toggleSelectionMode(s); else playAudio(list, pos); });
            v.setOnLongClickListener(view -> { vibrate(view); if (!isSelectionMode) toggleSelectionMode(s); return true; });
            return v;
        }
    }

    private class DetailSongAdapter extends BaseAdapter {
        @Override public int getCount() { return displayDetailSongs.size(); }
        @Override public Object getItem(int i) { return displayDetailSongs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View v, ViewGroup p) {
            SongViewHolder h;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_song, p, false);
                h = new SongViewHolder();
                h.chkSelect = v.findViewById(R.id.chkSelect);
                h.txtTitle = v.findViewById(R.id.txtTitle); h.txtArtist = v.findViewById(R.id.txtArtist);
                h.imgArt = v.findViewById(R.id.imgArt);
                v.setTag(h);
            } else h = (SongViewHolder) v.getTag();

            Models.Song s = displayDetailSongs.get(pos);
            h.txtTitle.setText(s.title); h.txtArtist.setText(s.artist);
            loadArtAsync(h.imgArt, s.path, false, QUALITY_LOW, null);

            if (isSelectionMode) {
                h.chkSelect.setVisibility(View.VISIBLE);
                h.chkSelect.setChecked(selectedSongs.contains(s));
            } else {
                h.chkSelect.setVisibility(View.GONE);
            }

            v.setOnClickListener(view -> { vibrate(view); if (isSelectionMode) toggleSelectionMode(s); else playAudio(displayDetailSongs, pos); });
            v.setOnLongClickListener(view -> { vibrate(view); if (!isSelectionMode) toggleSelectionMode(s); return true; });
            return v;
        }
    }

    private class AlbumAdapter extends BaseAdapter {
        @Override public int getCount() { return displayAlbums.size(); }
        @Override public Object getItem(int i) { return displayAlbums.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_grid, p, false);
            Models.Album a = displayAlbums.get(i);
            ((TextView) v.findViewById(R.id.txtGridTitle)).setText(a.name);
            ((TextView) v.findViewById(R.id.txtGridSub)).setText(a.artist);
            loadArtAsync(v.findViewById(R.id.imgGridArt), a.songs.isEmpty() ? null : a.songs.get(0).path, false, QUALITY_MED, null);
            return v;
        }
    }

    private class PlaylistAdapter extends BaseAdapter {
        @Override public int getCount() { return displayPlaylists.size(); }
        @Override public Object getItem(int i) { return displayPlaylists.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_grid, p, false);
            Models.Playlist pl = displayPlaylists.get(i);
            ((TextView) v.findViewById(R.id.txtGridTitle)).setText(pl.name);
            ((TextView) v.findViewById(R.id.txtGridSub)).setText(pl.songs.size() + " songs");

            if (pl.imageUri != null && !pl.imageUri.isEmpty()) {
                loadArtAsync(v.findViewById(R.id.imgGridArt), pl.imageUri, true, QUALITY_MED, null);
            } else if (!pl.songs.isEmpty()) {
                loadArtAsync(v.findViewById(R.id.imgGridArt), pl.songs.get(0).path, false, QUALITY_MED, null);
            } else {
                v.findViewById(R.id.imgGridArt).setBackgroundColor(0xFF333333);
                ((ImageView) v.findViewById(R.id.imgGridArt)).setImageResource(android.R.drawable.ic_menu_gallery);
            }
            return v;
        }
    }

    private void filterData(String query) {
        String q = query.toLowerCase().trim();
        displaySongs.clear(); displayAlbums.clear(); displayPlaylists.clear();
        if (q.isEmpty()) { displaySongs.addAll(allSongs); displayAlbums.addAll(allAlbums); displayPlaylists.addAll(allPlaylists); }
        else {
            for (Models.Song s : allSongs) if (s.title.toLowerCase().contains(q) || s.artist.toLowerCase().contains(q)) displaySongs.add(s);
            for (Models.Album a : allAlbums) if (a.name.toLowerCase().contains(q) || a.artist.toLowerCase().contains(q)) displayAlbums.add(a);
            for (Models.Playlist p : allPlaylists) if (p.name.toLowerCase().contains(q)) displayPlaylists.add(p);
        }
        refreshAllAdapters();
    }

    private void loadMusic() {
        allSongs.clear(); HashMap<String, Models.Album> map = new HashMap<>();
        Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?", new String[]{"%Music/%"}, null);
        if (c != null && c.moveToFirst()) {
            do {
                String id = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                String title = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                String artist = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                String path = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                String albId = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                String albName = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                long date = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED));
                int track = c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK));

                if (artist.toLowerCase().contains("unknown")) { try { String[] pts = path.split("/"); albName = pts[pts.length-2]; artist = pts[pts.length-3]; } catch(Exception e){} }
                Models.Song s = new Models.Song(id, title, artist, path, albId, albName, track, date);
                allSongs.add(s);
                if (!map.containsKey(albId)) map.put(albId, new Models.Album(albId, albName, artist, date));
                map.get(albId).songs.add(s);
            } while (c.moveToNext()); c.close();
        }
        allAlbums = new ArrayList<>(map.values());
        for (Models.Album a : allAlbums) Collections.sort(a.songs, Comparator.comparingInt(s -> s.trackNumber));
        loadPlaylists(); sortData(0); filterData("");
    }

    private void savePlaylists() {
        try {
            JSONArray arr = new JSONArray();
            for (Models.Playlist p : allPlaylists) {
                JSONObject o = new JSONObject(); o.put("name", p.name); o.put("imageUri", p.imageUri != null ? p.imageUri : "");
                JSONArray songData = new JSONArray();
                for (Models.Song s : p.songs) { JSONObject sObj = new JSONObject(); sObj.put("id", s.id); sObj.put("t", s.title); sObj.put("a", s.artist); songData.put(sObj); }
                o.put("songData", songData); arr.put(o);
            }
            prefs.edit().putString("playlists", arr.toString()).apply();
        } catch (Exception e) {}
    }

    private void loadPlaylists() {
        allPlaylists.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Models.Playlist p = new Models.Playlist(o.getString("name"), o.optString("imageUri", null));
                JSONArray sData = o.getJSONArray("songData");
                for (int j = 0; j < sData.length(); j++) {
                    JSONObject sObj = sData.getJSONObject(j);
                    String sId = sObj.getString("id"), sTitle = sObj.getString("t"), sArtist = sObj.getString("a");
                    for (Models.Song s : allSongs) {
                        if (s.id.equals(sId) || (s.title.equalsIgnoreCase(sTitle) && s.artist.equalsIgnoreCase(sArtist))) { p.songs.add(s); break; }
                    }
                }
                allPlaylists.add(p);
            }
        } catch (Exception e) {}
    }

    private void sortData(int type) {
        Comparator<Models.Song> sComp = type == 0 ? (a, b) -> a.title.compareToIgnoreCase(b.title) : type == 1 ? (a, b) -> b.title.compareToIgnoreCase(a.title) : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
        Comparator<Models.Album> aComp = type == 0 ? (a, b) -> a.name.compareToIgnoreCase(b.name) : type == 1 ? (a, b) -> b.name.compareToIgnoreCase(a.name) : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
        Collections.sort(allSongs, sComp); Collections.sort(allAlbums, aComp);
    }

    private void openDetailView(String title, ArrayList<Models.Song> list, boolean isP, Models.Playlist pObj) {
        expandedDetailsView.setVisibility(View.VISIBLE); txtDetailTitle.setText(title);
        currentOpenPlaylist = isP ? pObj : null;

        if (isP && pObj.imageUri != null) loadArtAsync(imgDetailCover, pObj.imageUri, true, QUALITY_HIGH, null);
        else if (isP && !list.isEmpty()) loadArtAsync(imgDetailCover, list.get(0).path, false, QUALITY_HIGH, null);
        else if (!list.isEmpty()) loadArtAsync(imgDetailCover, list.get(0).path, false, QUALITY_HIGH, null);
        else imgDetailCover.setImageResource(android.R.drawable.ic_menu_gallery);

        displayDetailSongs.clear(); displayDetailSongs.addAll(list);
        if(detailSongAdapter!=null) detailSongAdapter.notifyDataSetChanged();

        findViewById(R.id.btnDetailPlayAll).setOnClickListener(v -> { vibrate(v); if(!list.isEmpty()) playAudio(new ArrayList<>(list), 0); });
        findViewById(R.id.btnDetailShuffle).setOnClickListener(v -> { vibrate(v); if(!list.isEmpty()){ ArrayList<Models.Song> s = new ArrayList<>(list); Collections.shuffle(s); playAudio(s, 0); }});
        if (isSelectionMode) btnDeleteSelection.setVisibility(currentOpenPlaylist != null ? View.VISIBLE : View.GONE);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO}, 1);
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] res) { super.onRequestPermissionsResult(r, p, res); loadMusic(); }
    private String formatTime(int ms) { int sec = (ms / 1000) % 60; int min = (ms / (1000 * 60)) % 60; return String.format(Locale.getDefault(), "%d:%02d", min, sec); }

    private void setupLaunchers() {
        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null && activePlaylistForImage != null) { try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) {} activePlaylistForImage.imageUri = uri.toString(); savePlaylists(); filterData(""); } });
        backupFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> { if (uri != null) { try (OutputStream os = getContentResolver().openOutputStream(uri)) { JSONArray arr = new JSONArray(prefs.getString("playlists", "[]")); for (int i=0; i<arr.length(); i++) { JSONObject o = arr.getJSONObject(i); String imgUri = o.optString("imageUri", ""); if (!imgUri.isEmpty()) o.put("b64", getBase64Image(Uri.parse(imgUri))); } os.write(arr.toString().getBytes()); Toast.makeText(this, "Export Ready!", Toast.LENGTH_SHORT).show(); } catch (Exception e) {} } });
        restoreFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) { try (InputStream is = getContentResolver().openInputStream(uri); BufferedReader reader = new BufferedReader(new InputStreamReader(is))) { StringBuilder sb = new StringBuilder(); String line; while ((line = reader.readLine()) != null) sb.append(line); prefs.edit().putString("playlists", sb.toString()).apply(); loadPlaylists(); filterData(""); Toast.makeText(this, "Restore Successful!", Toast.LENGTH_SHORT).show(); } catch (Exception e) {} } });
    }

    private String getBase64Image(Uri uri) {
        try { InputStream is = getContentResolver().openInputStream(uri); Bitmap bm = BitmapFactory.decodeStream(is); if (bm == null) return ""; Bitmap scaled = Bitmap.createScaledBitmap(bm, 400, 400, true); ByteArrayOutputStream baos = new ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos); return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT); } catch (Exception e) { return ""; }
    }

    @Override protected void onDestroy() {
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