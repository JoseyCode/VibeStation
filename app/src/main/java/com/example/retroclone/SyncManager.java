package com.example.retroclone;

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

public class SyncManager {

    private static final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    public interface SyncCallback {
        void onProgress(int progress, int max, String message);
        void onComplete(String result);
        void onError(String error);
    }

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

                ArrayList<RemoteSong> downloadList = new ArrayList<>();
                for (RemoteSong remoteSong : remoteSongsList) {
                    String remoteKey = makeMatchKey(remoteSong.title, remoteSong.artist);
                    if (!localKeys.contains(remoteKey)) {
                        downloadList.add(remoteSong);
                    }
                }

                int totalSongsToSync = uploadList.size() + downloadList.size();
                int currentProgress = 0;

                int uploadedCount = 0;
                for (Models.Song localSong : uploadList) {
                    callback.onProgress(currentProgress, totalSongsToSync, "Uploading (" + (currentProgress + 1) + "/" + totalSongsToSync + "):\n" + localSong.title);
                    uploadSong(client, serverUrl, localSong);
                    uploadedCount++;
                    currentProgress++;
                }

                int downloadedCount = 0;
                for (RemoteSong remoteSong : downloadList) {
                    callback.onProgress(currentProgress, totalSongsToSync, "Downloading (" + (currentProgress + 1) + "/" + totalSongsToSync + "):\n" + remoteSong.title);
                    downloadSong(context, client, serverUrl, remoteSong);
                    downloadedCount++;
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

    private static String makeMatchKey(String title, String artist) {
        String safeTitle = title != null ? title.trim() : "";
        String safeArtist = artist != null ? artist.trim() : "";
        if (safeTitle.isEmpty() && safeArtist.isEmpty()) {
            return "unknown_track";
        }
        return (safeTitle + "_" + safeArtist).toLowerCase(Locale.getDefault()).replaceAll("[^\\p{L}\\p{N}_]", "");
    }

    private static String safeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "track_" + System.currentTimeMillis();
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

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

    private static void downloadSong(Context context, OkHttpClient client, String serverUrl, RemoteSong remoteSong) throws IOException {
        Request request = new Request.Builder()
                .url(serverUrl + "/api/stream/" + remoteSong.id)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Failed to download stream");
            if (response.body() == null) throw new IOException("Empty response body from stream");

            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName(remoteSong.title) + ".mp3");
            values.put(MediaStore.Audio.Media.TITLE, remoteSong.title);
            values.put(MediaStore.Audio.Media.ARTIST, remoteSong.artist);
            values.put(MediaStore.Audio.Media.ALBUM, remoteSong.album);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            }

            Uri uri = null;
            try {
                uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Failed to insert MediaStore record");

                try (InputStream is = response.body().byteStream();
                     OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                }
            } catch (Exception e) {
                if (uri != null) {
                    try {
                        context.getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {}
                }
                throw e;
            }
        }
    }

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

    private static class RemoteSong {
        final String id, title, artist, album;
        RemoteSong(String id, String title, String artist, String album) {
            this.id = id; this.title = title; this.artist = artist; this.album = album;
        }
    }
}
