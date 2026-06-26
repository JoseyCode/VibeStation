package com.boogie.vibestation;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the background synchronization process between the Android app's local MediaStore
 * audio database and a remote VibeStation server. Handles song uploads, downloads,
 * and JSON-based playlist syncing.
 */
public class SyncManager {

    // Executor that serializes sync execution runs to prevent race conditions on write operations
    private static final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    /**
     * Callback interface providing progress, completion, and failure feedback
     * notifications during a synchronization run.
     */
    public interface SyncCallback {
        /** Called when a synchronization step completes. */
        void onProgress(int progress, int max, String message);
        /** Called when the synchronization run completes successfully. */
        void onComplete(String result);
        /** Called when a critical exception terminates the synchronization run. */
        void onError(String error);
    }

    /**
     * Starts the synchronization operation on a background executor. Querying remote songs,
     * comparing match keys to compute uploads and downloads, and merging playlists.
     *
     * @param context    Application context for content resolvers and preferences.
     * @param serverUrl  Base URL of the remote synchronization server.
     * @param localSongs List of all local songs found in the MediaStore database.
     * @param callback   Callback for reporting sync status updates to the UI.
     */
    public static void startSync(Context context, String serverUrl, ArrayList<Models.Song> localSongs, SyncCallback callback) {
        syncExecutor.execute(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();

            try {
                callback.onProgress(0, 0, "Querying server library...");
                Log.d("VibeSync", "Connecting to server: " + serverUrl);

                // Fetch remote song library JSON array representation
                Request listRequest = new Request.Builder().url(serverUrl + "/api/songs").build();

                String remoteSongsJson;
                try (Response response = client.newCall(listRequest).execute()) {
                    if (!response.isSuccessful()) throw new IOException("Server error: " + response.code());
                    if (response.body() == null) throw new IOException("Empty server response");
                    remoteSongsJson = response.body().string();
                }

                JSONArray remoteSongsArray = new JSONArray(remoteSongsJson);
                HashSet<String> remoteKeys = new HashSet<>();
                ArrayList<RemoteSong> remoteSongsList = new ArrayList<>();

                // Parse and map remote tracks to lookup hash keys
                for (int i = 0; i < remoteSongsArray.length(); i++) {
                    JSONObject sObj = remoteSongsArray.getJSONObject(i);
                    String id = sObj.optString("id", "");
                    String title = sObj.optString("title", "Unknown Title");
                    String artist = sObj.optString("artist", "Unknown Artist");
                    String album = sObj.optString("album", "Unknown Album");
                    String key = makeMatchKey(title, artist);
                    remoteKeys.add(key);
                    remoteSongsList.add(new RemoteSong(id, title, artist, album));
                }

                // Compute upload list: Local songs that do not exist on the remote server
                ArrayList<Models.Song> uploadList = new ArrayList<>();
                for (Models.Song localSong : localSongs) {
                    String localKey = makeMatchKey(localSong.title, localSong.artist);
                    if (!remoteKeys.contains(localKey)) {
                        uploadList.add(localSong);
                    }
                }

                HashSet<String> localKeys = new HashSet<>();
                for (Models.Song localSong : localSongs) {
                    localKeys.add(makeMatchKey(localSong.title, localSong.artist));
                }

                // Compute download list: Remote songs that do not exist in local MediaStore
                ArrayList<RemoteSong> downloadList = new ArrayList<>();
                for (RemoteSong remoteSong : remoteSongsList) {
                    String remoteKey = makeMatchKey(remoteSong.title, remoteSong.artist);
                    if (!localKeys.contains(remoteKey)) {
                        downloadList.add(remoteSong);
                    }
                }

                int totalSongsToSync = uploadList.size() + downloadList.size();
                int currentProgress = 0;

                // Process uploads sequentially
                int uploadedCount = 0;
                for (Models.Song localSong : uploadList) {
                    callback.onProgress(currentProgress, totalSongsToSync, "Uploading (" + (currentProgress + 1) + "/" + totalSongsToSync + "):\n" + localSong.title);
                    try {
                        uploadSong(client, serverUrl, localSong);
                        uploadedCount++;
                    } catch (Exception e) {
                        Log.e("VibeSync", "Failed to upload: " + localSong.title, e);
                    }
                    currentProgress++;
                }

                // Process downloads sequentially
                int downloadedCount = 0;
                for (RemoteSong remoteSong : downloadList) {
                    callback.onProgress(currentProgress, totalSongsToSync, "Downloading (" + (currentProgress + 1) + "/" + totalSongsToSync + "):\n" + remoteSong.title);
                    try {
                        downloadSong(context, client, serverUrl, remoteSong);
                        downloadedCount++;
                    } catch (Exception e) {
                        Log.e("VibeSync", "Failed to download: " + remoteSong.title, e);
                    }
                    currentProgress++;
                }

                callback.onProgress(totalSongsToSync, totalSongsToSync, "Syncing playlists database...");
                syncPlaylists(context, client, serverUrl);

                callback.onComplete("Uploaded " + uploadedCount + " tracks, Downloaded " + downloadedCount + " tracks.");

            } catch (Exception e) {
                Log.e("VibeSync", "Sync process crashed with exception", e);
                callback.onError(e.toString());
            }
        });
    }

    /**
     * Generates a normalized match key based on alphanumeric characters in song metadata.
     * Prevents metadata differences (whitespace, casing, punctuation) from causing duplicates.
     *
     * @param title  The song title.
     * @param artist The song artist.
     * @return       A clean lowercase identification key.
     */
    private static String makeMatchKey(String title, String artist) {
        String safeTitle = title != null ? title.trim() : "";
        String safeArtist = artist != null ? artist.trim() : "";
        if (safeTitle.isEmpty() && safeArtist.isEmpty()) {
            return "unknown_track";
        }
        return (safeTitle + "_" + safeArtist).toLowerCase(Locale.getDefault()).replaceAll("[^\\p{L}\\p{N}_]", "");
    }

    /**
     * Sanitizes file system input strings to prevent invalid characters from causing crash loops during downloads.
     *
     * @param name Name string to sanitize.
     * @return     A filesystem-safe name string.
     */
    private static String safeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "track_" + System.currentTimeMillis();
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
    }

    /**
     * Performs a multipart POST request containing the raw audio file to upload it to the server.
     *
     * @param client    Initialized OkHttpClient.
     * @param serverUrl Server destination base URL.
     * @param song      The local Song object to upload.
     */
    private static void uploadSong(OkHttpClient client, String serverUrl, Models.Song song) throws IOException {
        File file = new File(song.path);
        if (!file.exists()) return;

        RequestBody fileBody = RequestBody.create(file, MediaType.parse("audio/mpeg"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(serverUrl + "/api/upload")
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Upload failed for " + song.title);
        }
    }

    /**
     * Streams the audio file content from the server and inserts it into the Android MediaStore content provider.
     * Compatible with Android 10+ scoped storage policies using MediaStore IS_PENDING flags.
     *
     * @param context    Application context for content resolution.
     * @param client     OkHttpClient.
     * @param serverUrl  Server source base URL.
     * @param remoteSong The RemoteSong description of the track to download.
     */
    private static void downloadSong(Context context, OkHttpClient client, String serverUrl, RemoteSong remoteSong) throws IOException {
        Request request = new Request.Builder()
                .url(serverUrl + "/api/stream/" + Uri.encode(remoteSong.id))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Failed to download stream");
            if (response.body() == null) throw new IOException("Empty response body from stream");

            // Build metadata records for insertion into MediaStore content provider
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName(remoteSong.title) + ".mp3");
            values.put(MediaStore.Audio.Media.TITLE, remoteSong.title);
            values.put(MediaStore.Audio.Media.ARTIST, remoteSong.artist);
            values.put(MediaStore.Audio.Media.ALBUM, remoteSong.album);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");

            // Scoped storage requirements for Android 10 (Q) and higher
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            }

            Uri uri = null;
            try {
                uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Failed to insert MediaStore record");

                // Read download streams and write bytes into the shared system output storage path
                try (InputStream is = response.body().byteStream();
                     OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                // Turn off pending status flag once writing successfully completes on Q+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                }
            } catch (Exception e) {
                // Delete orphaned provider entries in the event of an IO failure during writing
                if (uri != null) {
                    try {
                        context.getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {}
                }
                throw e;
            }
        }
    }

    /**
     * Posts local JSON-formatted playlists to the server, fetches the merged response database,
     * and saves it locally inside the app's Shared Preferences database.
     *
     * @param context   Application context.
     * @param client    OkHttpClient.
     * @param serverUrl Destination server base URL.
     */
    private static void syncPlaylists(Context context, OkHttpClient client, String serverUrl) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("RetroPrefs", Context.MODE_PRIVATE);
            String localPlaylistsJson = prefs.getString("playlists", "[]");

            RequestBody body = RequestBody.create(localPlaylistsJson, MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(serverUrl + "/api/playlists")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String mergedPlaylistsJson = response.body().string();
                    prefs.edit().putString("playlists", mergedPlaylistsJson).apply();
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Local model representing a remote server track definition metadata block.
     */
    private static class RemoteSong {
        final String id, title, artist, album;
        RemoteSong(String id, String title, String artist, String album) {
            this.id = id; this.title = title; this.artist = artist; this.album = album;
        }
    }
}
