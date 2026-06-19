package com.example.retroclone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// AI NOTE: This service is the single source of truth for audio playback.
// It uses a LocalBinder to communicate directly with MainActivity in the same process.
public class AudioService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.example.retroclone.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.retroclone.ACTION_NEXT";
    public static final String ACTION_PREV = "com.example.retroclone.ACTION_PREV";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vibe_channel";

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Playback State
    private ArrayList<Models.Song> currentQueue = new ArrayList<>();
    private int currentIndex = -1;
    private Models.Song currentSong;
    private Bitmap currentArt;

    // Callback interface to update MainActivity's UI
    public interface ServiceCallback {
        void onTrackChanged(Models.Song song, Bitmap art);
        void onPlaybackStateChanged(boolean isPlaying);
    }

    public int getAudioSessionId() {
        return mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
    }
    private ServiceCallback serviceCallback;

    public class LocalBinder extends Binder {
        AudioService getService() { return AudioService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setCallback(ServiceCallback callback) { this.serviceCallback = callback; }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        createNotificationChannel();
        setupMediaSession();
        setupAudioFocus();

        // Auto-play next song when current finishes
        mediaPlayer.setOnCompletionListener(mp -> playNext());
    }

    // --- CORE ENGINE ACTIONS ---

    public void setQueueAndPlay(ArrayList<Models.Song> queue, int position) {
        this.currentQueue = queue;
        this.currentIndex = position;
        playTrack();
    }

    private void playTrack() {
        if (currentQueue.isEmpty() || currentIndex < 0 || currentIndex >= currentQueue.size()) return;
        if (!requestFocus()) return;

        currentSong = currentQueue.get(currentIndex);

        try {
            mediaPlayer.reset();
            Uri trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Long.parseLong(currentSong.id));
            mediaPlayer.setDataSource(this, trackUri);
            mediaPlayer.prepare();
            mediaPlayer.start();

            loadAlbumArtAndNotify(trackUri);

        } catch (Exception e) {
            Toast.makeText(this, "Error playing track", Toast.LENGTH_SHORT).show();
        }
    }

    public void togglePlayPause() {
        if (currentSong == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else if (requestFocus()) {
            mediaPlayer.start();
        }
        updateSystemPlayerAndUI();
    }

    public void playNext() {
        if (currentQueue.isEmpty()) return;
        currentIndex = (currentIndex + 1) % currentQueue.size();
        playTrack();
    }

    public void playPrev() {
        if (currentQueue.isEmpty()) return;
        currentIndex = (currentIndex - 1 + currentQueue.size()) % currentQueue.size();
        playTrack();
    }

    public void seekTo(int position) {
        if (mediaPlayer != null) mediaPlayer.seekTo(position);
    }

    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public Models.Song getCurrentSong() { return currentSong; }
    public Bitmap getCurrentArt() { return currentArt; }

    // --- SYSTEM INTEGRATION (Notifications, Focus, Session) ---

    // Handles actions coming from the notification buttons
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE: togglePlayPause(); break;
                case ACTION_NEXT: playNext(); break;
                case ACTION_PREV: playPrev(); break;
            }
        }
        return START_NOT_STICKY;
    }

    private void loadAlbumArtAndNotify(Uri trackUri) {
        executorService.execute(() -> {
            currentArt = null;
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(this, trackUri);
                byte[] data = mmr.getEmbeddedPicture();
                if (data != null) {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inSampleSize = 2; // Medium quality for notification
                    currentArt = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
                }
                mmr.release();
            } catch (Exception e) {}

            new Handler(Looper.getMainLooper()).post(this::updateSystemPlayerAndUI);
        });
    }

    private void updateSystemPlayerAndUI() {
        if (currentSong == null) return;
        boolean playing = mediaPlayer.isPlaying();

        // 1. Update Media Session (Lock screen)
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, mediaPlayer.getCurrentPosition(), 1.0f).build());

        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.getDuration());
        if (currentArt != null) meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArt);
        mediaSession.setMetadata(meta.build());

        // 2. Update Foreground Notification
        Notification notification = buildNotification(playing);
        startForeground(NOTIFICATION_ID, notification);

        // 3. Update Activity UI
        if (serviceCallback != null) {
            serviceCallback.onTrackChanged(currentSong, currentArt);
            serviceCallback.onPlaybackStateChanged(playing);
        }
    }

    private Notification buildNotification(boolean isPlaying) {
        Intent intentApp = new Intent(this, MainActivity.class);
        PendingIntent pendingApp = PendingIntent.getActivity(this, 0, intentApp, PendingIntent.FLAG_IMMUTABLE);

        PendingIntent playPauseIntent = PendingIntent.getService(this, 0, new Intent(this, AudioService.class).setAction(ACTION_PLAY_PAUSE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextIntent = PendingIntent.getService(this, 0, new Intent(this, AudioService.class).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent prevIntent = PendingIntent.getService(this, 0, new Intent(this, AudioService.class).setAction(ACTION_PREV), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentSong.title)
                .setContentText(currentSong.artist)
                .setLargeIcon(currentArt)
                .setContentIntent(pendingApp)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "Play/Pause", playPauseIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void setupAudioFocus() {
        audioFocusChangeListener = focusChange -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) togglePlayPause();
            }
        };
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(audioFocusChangeListener).build();
            return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "VibeStation");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { togglePlayPause(); }
            @Override public void onPause() { togglePlayPause(); }
            @Override public void onSkipToNext() { playNext(); }
            @Override public void onSkipToPrevious() { playPrev(); }
            @Override public void onSeekTo(long pos) { seekTo((int)pos); }
        });
        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "VibeStation Playback", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaSession.release();
        executorService.shutdown();
    }
}