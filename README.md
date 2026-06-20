# VibeStation

> Who said offline music couldn't be fun?

VibeStation is a vibe-coded, premium, offline MP3 player for Android. Built with a focus on immersive visuals, smooth performance, and simple usability, VibeStation turns your offline music collection into a responsive, color-dynamic experience.

---

## Features

*   **Adaptive UI Styling**: Uses the Android Palette API to extract color profiles (dominant and muted) from your album art, dynamically styling the media player's background and controls to match the track's vibe.
*   **FFT Bezier Visualizer**: An integrated, high-refresh graphic equalizer that maps real-time frequencies onto a smooth, Bezier-curved waveform.
*   **120Hz Smooth Scrolling**: Window manager optimization to automatically unlock maximum display refresh rates for buttery-smooth navigation.
*   **Smart Playlist Management**: Create, rename, delete, and customize playlists with custom covers.
*   **Export & Import Backups**: Back up your custom playlists (including base64-encoded custom covers) and restore them easily across devices.
*   **Complete Offline Privacy**: No accounts, no ads, no trackers, and no internet connection required.

---

## Architecture and Core Components

VibeStation follows a standard Android framework structure. Below is a directory tree of the key project files and layouts:

```text
VibeStation/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/retroclone/
│   │       │   ├── Models.java         (Song, Album, and Playlist data structures)
│   │       │   ├── SplashActivity.java (Intro screen with randomized slogan)
│   │       │   ├── MainActivity.java   (Core UI controller, adapters, & content queries)
│   │       │   ├── AudioService.java   (Playback service, MediaSession, & lockscreen controls)
│   │       │   └── VisualizerView.java (Custom view rendering dynamic FFT curves)
│   │       ├── res/
│   │       │   ├── layout/             (XML UI layout files)
│   │       │   │   ├── activity_splash.xml
│   │       │   │   ├── activity_main.xml
│   │       │   │   ├── item_song.xml
│   │       │   │   └── item_grid.xml
│   │       │   └── values/             (Theme styles and color definitions)
│   │       │       ├── colors.xml
│   │       │       └── themes.xml
│   │       └── AndroidManifest.xml     (System declarations, permissions, foreground setup)
│   └── build.gradle.kts                (Module configurations and dependencies)
├── web/                                (Web Player & Pi Sync Server)
│   ├── public/                         (Static UI assets)
│   │   ├── app.js                      (Web player client-side controller and visualizer)
│   │   ├── index.html                  (HTML structure)
│   │   └── style.css                   (Glassmorphic, responsive stylesheets)
│   └── server.js                       (Node.js Express stream & metadata API server)
└── settings.gradle.kts                 (Project-wide builds configuration)
```

*   **[`SplashActivity`](app/src/main/java/com/example/retroclone/SplashActivity.java)**: Greets you with a random vibe-coded slogan on start.
*   **[`MainActivity`](app/src/main/java/com/example/retroclone/MainActivity.java)**: Coordinates the interface, handles standard storage query routines, and manages user interaction.
*   **[`AudioService`](app/src/main/java/com/example/retroclone/AudioService.java)**: The single source of truth for audio playback, integrating background foreground service lifecycles, media notifications, lockscreen playback state (`MediaSessionCompat`), and audio focus handling.
*   **[`VisualizerView`](app/src/main/java/com/example/retroclone/VisualizerView.java)**: Custom canvas view that plots and paints real-time frequency-domain data (FFT) as a smooth Bezier wave.
*   **[`Models`](app/src/main/java/com/example/retroclone/Models.java)**: Lightweight, clean data models for `Song`, `Album`, and `Playlist`.
*   **`web/`**: Node.js Express server (`server.js`) that hosts the streaming hub, handles media uploads via `multer`, extracts metadata via `music-metadata`, and serves a fully responsive, visualizer-equipped player client (`app.js`, `style.css`, `index.html`) optimized for both mobile and desktop views.
*   **Library Sync Protocol**: An asynchronous, multi-threaded sync connector (`SyncManager` in the Android app) that synchronizes local tracks directly to the Node.js Express server backend.

---

## Web Stream & Sync Hub API Reference

The Node.js Express server (`web/server.js`) coordinates local database syncs, stream routing, external metadata fetching, and local data overrides.

### 🌐 Endpoints

#### Playback & Streaming APIs
*   **`GET /api/songs`**: Retrieves a JSON array of all songs scanned and indexed in the library.
*   **`GET /api/stream/:id`**: Streams the binary audio content for the track specified by `:id`.
*   **`GET /api/artwork/:id`**: Serves the embedded album artwork parsed from the track metadata. Falls back to `placeholder.webp` if unavailable.

#### Device Synchronization APIs
*   **`POST /api/upload`**: Receives multi-file binary uploads (MP3 files synced from the Android app) and saves them in the `music/` directory.
*   **`GET /api/playlists`**: Returns the list of synced custom playlists.
*   **`POST /api/playlists`**: Synchronizes playlist layouts and custom cover images from the mobile client.
*   **`POST /api/deduplicate`**: Scans and removes duplicate entries from the library sync database.

#### Metadata & Integration APIs
*   **`GET /api/metadata/artist?name={artist}`**: Fetches biographical records, logos, and artist images from TheAudioDB.
*   **`GET /api/metadata/album?artist={artist}&album={album}`**: Fetches release year, genre, artwork, and descriptions from TheAudioDB. Merges results with local JSON overrides when description fields are empty or null.
*   **`GET /api/metadata/lyrics?artist={artist}&title={title}`**: Fetches matching song lyrics from the Lyrics.ovh API.

---

## 🛠 Local Metadata Overrides

For albums or artists that return `null` descriptions in public databases (for instance, NF's *HOPE*), VibeStation supports manual offline database overrides. 

*   **File Path**: [`web/metadata_overrides.json`](web/metadata_overrides.json)
*   **Key Mapping**: Target matches are normalized automatically by removing non-alphanumeric characters and converting to lowercase (e.g., `nf_hope` for artist `NF` and album `HOPE`).

**Example Override Entry**:
```json
{
  "artists": {},
  "albums": {
    "nf_hope": {
      "description": "HOPE is the fifth studio album by American rapper NF, released on April 7, 2023. Marked by a shift in tone towards healing, peace, and self-acceptance, the album represents a powerful transition from NF's previous darker themes of trauma and depression. It features notable collaborations with Julia Michaels and Cordae."
    }
  }
}
```

---

## Getting Started

### Prerequisites
*   Android Device running Android 7.0 (API level 24) or higher.
*   Android Studio Ladybug (or newer).
*   Target SDK: 36 (Java 11 compatible).

### Building and Running
1. Clone the repository:
   ```bash
   git clone https://github.com/JoseyCode/VibeStation.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Put some MP3 files into your device's `/Music/` directory.
5. Run the app on your physical device or emulator.
