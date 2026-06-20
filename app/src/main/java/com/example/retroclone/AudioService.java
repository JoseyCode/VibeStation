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

public class AudioService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.example.retroclone.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.retroclone.ACTION_NEXT";
    public static final String ACTION_PREV = "com.example.retroclone.ACTION_PREV";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "vibe_channel";
    private static final String CHANNEL_NAME = "VibeStation Playback";

    private final IBinder serviceBinder = new LocalBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();

    // Playback Queue State
    private ArrayList<Models.Song> currentQueue = new ArrayList<>();
    private int currentIndex = -1;
    private Models.Song currentSong;
    private Bitmap currentAlbumArt;

    public interface ServiceCallback {
        void onTrackChanged(Models.Song song, Bitmap albumArt);
        void onPlaybackStateChanged(boolean isPlaying);
    }

    private ServiceCallback serviceCallback;

    public int getAudioSessionId() {
        return mediaPlayer != null ? mediaPlayer.getAudioSessionId() : 0;
    }

    public class LocalBinder extends Binder {
        AudioService getService() {
            return AudioService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }

    public void setCallback(ServiceCallback callback) {
        this.serviceCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        createNotificationChannel();
        setupMediaSession();
        setupAudioFocus();

        // Automatically load and play the next song when current track ends
        mediaPlayer.setOnCompletionListener(player -> playNext());
    }

    public void setQueueAndPlay(ArrayList<Models.Song> newQueue, int initialPosition) {
        this.currentQueue = newQueue;
        this.currentIndex = initialPosition;
        playTrack();
    }

    private void playTrack() {
        if (currentQueue.isEmpty() || currentIndex < 0 || currentIndex >= currentQueue.size()) {
            return;
        }
        if (!requestFocus()) {
            return;
        }

        currentSong = currentQueue.get(currentIndex);

        try {
            mediaPlayer.reset();
            Uri trackUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    Long.parseLong(currentSong.id)
            );
            mediaPlayer.setDataSource(this, trackUri);
            mediaPlayer.prepare();
            mediaPlayer.start();

            loadAlbumArtAndNotify(trackUri);

        } catch (Exception exception) {
            Toast.makeText(this, "Error playing track", Toast.LENGTH_SHORT).show();
        }
    }

    public void togglePlayPause() {
        if (currentSong == null) {
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else if (requestFocus()) {
            mediaPlayer.start();
        }
        updateSystemPlayerAndUI();
    }

    public void playNext() {
        if (currentQueue.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + 1) % currentQueue.size();
        playTrack();
    }

    public void playPrev() {
        if (currentQueue.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex - 1 + currentQueue.size()) % currentQueue.size();
        playTrack();
    }

    public void seekTo(int positionMs) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(positionMs);
        }
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

    public Models.Song getCurrentSong() {
        return currentSong;
    }

    public Bitmap getCurrentArt() {
        return currentAlbumArt;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    togglePlayPause();
                    break;
                case ACTION_NEXT:
                    playNext();
                    break;
                case ACTION_PREV:
                    playPrev();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    private void loadAlbumArtAndNotify(Uri trackUri) {
        artworkExecutor.execute(() -> {
            currentAlbumArt = null;
            MediaMetadataRetriever metadataRetriever = null;
            try {
                metadataRetriever = new MediaMetadataRetriever();
                metadataRetriever.setDataSource(this, trackUri);
                byte[] embeddedPictureData = metadataRetriever.getEmbeddedPicture();
                if (embeddedPictureData != null) {
                    BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                    decodeOptions.inSampleSize = 2; // Decimate resolution for notification speed
                    currentAlbumArt = BitmapFactory.decodeByteArray(
                            embeddedPictureData,
                            0,
                            embeddedPictureData.length,
                            decodeOptions
                    );
                }
            } catch (Exception ignored) {
            } finally {
                if (metadataRetriever != null) {
                    try { metadataRetriever.release(); } catch (Exception ignored) {}
                }
            }

            new Handler(Looper.getMainLooper()).post(this::updateSystemPlayerAndUI);
        });
    }

    private void updateSystemPlayerAndUI() {
        if (currentSong == null) {
            return;
        }
        boolean isPlaying = mediaPlayer.isPlaying();

        // 1. Send update to Media Session (Lock screen controls)
        int playbackState = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(playbackState, mediaPlayer.getCurrentPosition(), 1.0f)
                .build());

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.getDuration());

        if (currentAlbumArt != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentAlbumArt);
        }
        mediaSession.setMetadata(metadataBuilder.build());

        // 2. Refresh Foreground Notification
        Notification playbackNotification = buildNotification(isPlaying);
        startForeground(NOTIFICATION_ID, playbackNotification);

        // 3. Notify Activity UI
        if (serviceCallback != null) {
            serviceCallback.onTrackChanged(currentSong, currentAlbumArt);
            serviceCallback.onPlaybackStateChanged(isPlaying);
        }
    }

    private Notification buildNotification(boolean isPlaying) {
        Intent appLaunchIntent = new Intent(this, MainActivity.class);
        PendingIntent appPendingIntent = PendingIntent.getActivity(
                this,
                0,
                appLaunchIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent playPausePendingIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, AudioService.class).setAction(ACTION_PLAY_PAUSE),
                PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent nextPendingIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, AudioService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent prevPendingIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, AudioService.class).setAction(ACTION_PREV),
                PendingIntent.FLAG_IMMUTABLE
        );

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentSong.title)
                .setContentText(currentSong.artist)
                .setLargeIcon(currentAlbumArt)
                .setContentIntent(appPendingIntent)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPendingIntent)
                .addAction(playPauseIcon, "Play/Pause", playPausePendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void setupAudioFocus() {
        audioFocusChangeListener = focusChangeState -> {
            if (focusChangeState == AudioManager.AUDIOFOCUS_LOSS || focusChangeState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    togglePlayPause();
                }
            }
        };
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build();
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "VibeStation");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                togglePlayPause();
            }

            @Override
            public void onPause() {
                togglePlayPause();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrev();
            }

            @Override
            public void onSeekTo(long positionMs) {
                seekTo((int) positionMs);
            }
        });
        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationChannel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(notificationChannel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        artworkExecutor.shutdown();
    }
}