package com.boogie.vibestation.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.boogie.vibestation.models.Playlist;
import com.boogie.vibestation.models.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles serialization, persistence, backup export, and restoration for playlists.
 */
public final class PlaylistUtil {

    private PlaylistUtil() {
        // Prevent instantiation
    }

    /**
     * Serializes playlist collection structures to JSON array and saves to SharedPreferences.
     *
     * @param prefs Target SharedPreferences instance.
     * @param playlists Playlists to persist.
     */
    public static void savePlaylists(SharedPreferences prefs, List<Playlist> playlists) {
        try {
            JSONArray playlistsJsonArray = new JSONArray();
            for (Playlist playlist : playlists) {
                JSONObject playlistJsonObject = new JSONObject();
                playlistJsonObject.put("name", playlist.name);
                playlistJsonObject.put("imageUri", playlist.imageUri != null ? playlist.imageUri : "");
                playlistJsonObject.put("description", playlist.description != null ? playlist.description : "");
                playlistJsonObject.put("isFire", playlist.isFire);

                JSONArray songsJsonArray = new JSONArray();
                for (Song song : playlist.songs) {
                    JSONObject songJsonObject = new JSONObject();
                    songJsonObject.put("id", song.id);
                    songJsonObject.put("t", song.title);
                    songJsonObject.put("a", song.artist);
                    songsJsonArray.put(songJsonObject);
                }
                playlistJsonObject.put("songData", songsJsonArray);
                playlistsJsonArray.put(playlistJsonObject);
            }
            prefs.edit().putString("playlists", playlistsJsonArray.toString()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Parses stored playlist JSON configuration from preferences and associates tracks using lookup maps.
     *
     * @param prefs Source SharedPreferences instance.
     * @param songIdMap Lookup map keyed by track ID.
     * @param songNameMap Lookup map keyed by normalized title and artist.
     * @return List of reconstructed Playlist instances.
     */
    public static ArrayList<Playlist> parsePlaylists(SharedPreferences prefs, Map<String, Song> songIdMap, Map<String, Song> songNameMap) {
        ArrayList<Playlist> tempPlaylists = new ArrayList<>();
        try {
            JSONArray playlistsJsonArray = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < playlistsJsonArray.length(); i++) {
                JSONObject playlistJsonObject = playlistsJsonArray.getJSONObject(i);
                Playlist playlist = new Playlist(
                        playlistJsonObject.getString("name"),
                        playlistJsonObject.optString("imageUri", null)
                );
                playlist.description = playlistJsonObject.optString("description", "");
                playlist.isFire = playlistJsonObject.optBoolean("isFire", false);
                JSONArray songsJsonArray = playlistJsonObject.getJSONArray("songData");
                for (int j = 0; j < songsJsonArray.length(); j++) {
                    JSONObject songJsonObject = songsJsonArray.getJSONObject(j);
                    String songId = songJsonObject.getString("id");
                    String songTitle = songJsonObject.getString("t");
                    String songArtist = songJsonObject.getString("a");

                    Song matchedSong = songIdMap.get(songId);
                    if (matchedSong == null) {
                        matchedSong = songNameMap.get((songTitle + "_" + songArtist).toLowerCase(Locale.getDefault()));
                    }
                    if (matchedSong != null) {
                        playlist.songs.add(matchedSong);
                    }
                }
                tempPlaylists.add(playlist);
            }
        } catch (Exception ignored) {}
        return tempPlaylists;
    }

    /**
     * Serializes playlist collection and embedded base64 artwork into a backup file on a background thread.
     *
     * @param context Application context for Toast and content resolution.
     * @param prefs Source SharedPreferences instance.
     * @param documentUri Target file URI for writing backup data.
     */
    public static void exportBackup(Context context, SharedPreferences prefs, Uri documentUri) {
        if (documentUri == null) return;
        new Thread(() -> {
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(documentUri)) {
                JSONArray playlistsJsonArray = new JSONArray(prefs.getString("playlists", "[]"));
                for (int i = 0; i < playlistsJsonArray.length(); i++) {
                    JSONObject playlistJsonObject = playlistsJsonArray.getJSONObject(i);
                    String imageUri = playlistJsonObject.optString("imageUri", "");
                    if (!imageUri.isEmpty()) {
                        playlistJsonObject.put("b64", ArtUtil.getBase64Image(context.getContentResolver(), Uri.parse(imageUri)));
                    }
                }
                if (outputStream != null) {
                    outputStream.write(playlistsJsonArray.toString().getBytes());
                }
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(() -> Toast.makeText(context, "Export Ready!", Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {}
        }).start();
    }

    /**
     * Parses and restores playlists JSON backup data from a user-selected document.
     *
     * @param context Application context for content resolution.
     * @param prefs Target SharedPreferences instance.
     * @param documentUri Selected backup document URI.
     * @param onRestoreComplete Callback executed on UI thread upon successful restoration.
     */
    public static void restoreBackup(Context context, SharedPreferences prefs, Uri documentUri, Runnable onRestoreComplete) {
        if (documentUri == null) return;
        new Thread(() -> {
            try (InputStream inputStream = context.getContentResolver().openInputStream(documentUri);
                 BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
                StringBuilder stringBuilder = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    stringBuilder.append(line);
                }
                prefs.edit().putString("playlists", stringBuilder.toString()).apply();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(() -> {
                    if (onRestoreComplete != null) {
                        onRestoreComplete.run();
                    }
                    Toast.makeText(context, "Restore Successful!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception ignored) {}
        }).start();
    }
}
