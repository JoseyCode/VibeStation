# Restore Playlist Cover Art During Backup Import Across Devices and Reinstalls

## Synopsis
- **Overview**: Exporting and subsequently restoring a playlist backup across devices or after an application reinstallation results in all playlist cover art reverting to placeholder defaults.
- **Root Cause & Impact**: Playlists store their visual artwork as Android content URIs (e.g. `content://com.android.providers.media.documents/document/image%3A...`) obtained from `ActivityResultContracts.OpenDocument` or `imagePickerLauncher`. While `takePersistableUriPermission` grants persistent read access to the active application install, uninstalling the application or moving the backup file to a new device automatically invalidates or revokes the provider grant. When `ArtUtil.decodeArtworkBitmap()` attempts to open an input stream on the restored URI string, Android raises a `SecurityException` or returns null. Because the backup file does not embed self-contained, portable image binaries, user custom artwork is permanently unrecoverable after a clean install.
- **Scope & Architectural Considerations**: The backup file format is shared across export and import operations in `PlaylistUtil`. Storing full uncompressed Base64 bitmaps in `SharedPreferences` was previously removed to prevent memory bloat during application startup. Consequently, the portable solution must separate the backup exchange format from the runtime persistence model: embed compressed Base64 artwork blobs inside the backup JSON file (`VibeStation_Backup.txt`), but during restoration, decode the blobs to disk in internal storage (`context.getFilesDir() / "playlist_covers"`), point `playlist.imageUri` to the local file URI, and strip the Base64 property before writing to `SharedPreferences`.
- **Context & Trigger Conditions**: Occurs whenever a user exports a backup via Settings > Backup & Portability Profiles > Export Playlists Backup, performs an app reinstallation or switches devices, and runs Import Playlists Backup.

## Suggested Fix
- **Proposed Approach**: Implement portable artwork serialization during export and file-backed extraction during restoration.
- **Implementation Guidance**:
  1. In `PlaylistUtil.exportBackup()`: For each playlist containing a valid `imageUri`, retrieve the artwork bitmap via `ArtUtil`, downsample to standard cover dimensions (e.g., 512x512 JPEG at 75% quality), encode to Base64, and store in a dedicated JSON key `cover_b64`.
  2. In `PlaylistUtil.restoreBackup()`: Parse the JSON array. For each playlist with `cover_b64`, decode the binary data, write the JPEG to `new File(context.getFilesDir(), "playlist_covers/playlist_" + System.currentTimeMillis() + "_" + i + ".jpg")`, and set `playlist.imageUri = Uri.fromFile(localFile).toString()`.
  3. Strip `cover_b64` from the restored JSON before writing to `SharedPreferences` to ensure `RetroPrefs` remains compact and memory-efficient.
  4. Ensure `playlist_covers/` directory is created if missing and old orphaned files can be garbage collected when playlists are deleted.

- **Code Snippets**:

Problematic Code (`PlaylistUtil.java`):
```java
// exportBackup: only exports raw document URI strings without portable image data
for (Playlist playlist : playlists) {
    JSONObject obj = new JSONObject();
    obj.put("name", playlist.name);
    obj.put("imageUri", playlist.imageUri != null ? playlist.imageUri : "");
    // No portable artwork payload
}
```

Suggested Fix Pattern (`PlaylistUtil.java`):
```java
// Export: Embed downsampled thumbnail into backup JSON
if (playlist.imageUri != null && !playlist.imageUri.isEmpty()) {
    String base64Art = ArtUtil.getBase64Image(context.getContentResolver(), Uri.parse(playlist.imageUri));
    if (!base64Art.isEmpty()) {
        playlistJsonObject.put("cover_b64", base64Art);
    }
}

// Restore: Extract to internal storage and re-point imageUri
File coversDir = new File(context.getFilesDir(), "playlist_covers");
if (!coversDir.exists()) coversDir.mkdirs();

for (int i = 0; i < validatedArray.length(); i++) {
    JSONObject playlistObj = validatedArray.getJSONObject(i);
    String coverB64 = playlistObj.optString("cover_b64", null);
    if (coverB64 != null && !coverB64.isEmpty()) {
        byte[] imageBytes = Base64.decode(coverB64, Base64.DEFAULT);
        File coverFile = new File(coversDir, "cover_" + System.currentTimeMillis() + "_" + i + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(coverFile)) {
            fos.write(imageBytes);
            playlistObj.put("imageUri", Uri.fromFile(coverFile).toString());
        }
        playlistObj.remove("cover_b64");
    }
}
```

- **Verification & Testing**:
  - [ ] Passed Self-Review Sweep
  - [ ] Verify playlist backup export generates valid JSON with embedded `cover_b64` payloads
  - [ ] Verify playlist restore decodes image bytes to internal `playlist_covers` directory
  - [ ] Verify restored playlists display cover art properly after fresh app reinstallation
  - [ ] Verify `SharedPreferences` ("RetroPrefs") does not contain raw Base64 data after restore
