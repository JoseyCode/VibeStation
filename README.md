# VibeStation

> Who said offline music couldn't be fun?

VibeStation is a vibe-coded, premium, offline MP3 player for Android meant to rival popular apps like Retro Music Player. In fact, before I created and was using VibeStation, Retro Music Player has been my go to app for offline music with a modern feel. Retro Music Player was great. I'd say overall it's 99% of what I wanted... but that last percent made all the difference. The final straw was Retro Music Player lacking the ability to let you add custom images to your playlists and instead opting to use auto-generated collages of artists within the playlists. It's a nice feature, but why not add the ability to put custom images on your playlists? That's a standard feature across all major music software and not that hard to implement. At the same time, I was feeling particularly proud of how far I'd come as a Software Developer still in College and wanted to know what I could accomplish if I took my hard earned skills and paired it with an agentic workflow. One week later and boom, we had VibeStation. I compiled nearly 30 different apks across the early testing phases and was copy pasting code from a Gemini browser into my IDE... safe to say I've come a LONG way. VibeStation is my agentic passion project to test both my skills as an agentic programmer and test current models and their capabilities. It has been an incredible project to develop and I'm very pleased with how the app and web player look, feel, and work as a whole... and I hope those who choose to clone and try VibeStation will agree!

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

VibeStation is a single-module Kotlin Android app. Below is a directory tree of the key project files and layouts:

```text
VibeStation/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/boogie/vibestation/
│   │   │   │   ├── SplashActivity.kt    (Intro screen with randomized slogan)
│   │   │   │   ├── MainActivity.kt      (Core UI controller, adapters, & content queries)
│   │   │   │   ├── AudioService.kt      (Playback service, MediaSession, & lockscreen controls)
│   │   │   │   ├── SyncManager.kt       (Library and playlist sync with VibeStation-Web)
│   │   │   │   ├── AppConfig.kt         (Application version constant)
│   │   │   │   ├── models/              (Song, Album, and Playlist data structures)
│   │   │   │   ├── util/                (Playlist persistence, artwork, MediaStore, and tag helpers)
│   │   │   │   └── views/               (Custom views: FFT visualizer, particles, circular progress)
│   │   │   ├── res/                     (Layouts, drawables, and theme/color values)
│   │   │   └── AndroidManifest.xml      (System declarations, permissions, foreground setup)
│   │   ├── test/kotlin/                 (JVM unit tests and ArchUnit architecture rules)
│   │   └── androidTest/kotlin/          (Instrumented tests)
│   └── build.gradle.kts                 (Module configurations and dependencies)
├── config/detekt/                       (detekt/ktlint rules and baseline)
└── settings.gradle.kts                  (Project-wide builds configuration)
```

> **Note**: The web streaming server and player client have been extracted to their own dedicated repository: [**JoseyCode/VibeStation-Web**](https://github.com/JoseyCode/VibeStation-Web).

*   **[`SplashActivity`](app/src/main/kotlin/com/boogie/vibestation/SplashActivity.kt)**: Greets you with a random vibe-coded slogan on start.
*   **[`MainActivity`](app/src/main/kotlin/com/boogie/vibestation/MainActivity.kt)**: Coordinates the interface, handles standard storage query routines, and manages user interaction.
*   **[`AudioService`](app/src/main/kotlin/com/boogie/vibestation/AudioService.kt)**: The single source of truth for audio playback, integrating background foreground service lifecycles, media notifications, lockscreen playback state (`MediaSessionCompat`), and audio focus handling.
*   **[`VisualizerView`](app/src/main/kotlin/com/boogie/vibestation/views/VisualizerView.kt)**: Custom canvas view that plots and paints real-time frequency-domain data (FFT) as a smooth Bezier wave.
*   **[`models`](app/src/main/kotlin/com/boogie/vibestation/models)**: Lightweight, clean data models for `Song`, `Album`, and `Playlist`.
*   **Library Sync Protocol**: An asynchronous, multi-threaded sync connector ([`SyncManager`](app/src/main/kotlin/com/boogie/vibestation/SyncManager.kt) in the Android app) that synchronizes local tracks directly to the [**VibeStation-Web**](https://github.com/JoseyCode/VibeStation-Web) sync server backend.

---

## Web Stream & Sync Hub

The streaming hub, browser player client, and synchronization server are maintained in a dedicated standalone repository:
👉 [**JoseyCode/VibeStation-Web**](https://github.com/JoseyCode/VibeStation-Web)

For instructions on deploying the server on a Raspberry Pi or local server, configuring endpoints, and setting up metadata overrides, please visit the [VibeStation-Web documentation](https://github.com/JoseyCode/VibeStation-Web).

---

## Getting Started

### Prerequisites
*   Android Device running Android 7.0 (API level 24) or higher.
*   Android Studio Ladybug (or newer).
*   Target SDK: 36 (Kotlin, JVM 11 target).

### Building and Running
1. Clone the repository:
   ```bash
   git clone https://github.com/JoseyCode/VibeStation.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Put some MP3 files into your device's `/Music/` directory.
5. Run the app on your physical device or emulator.

### Quality Checks
Run the full verification suite before committing:
```bash
./gradlew checkQuality
```
It runs JVM unit tests (including ArchUnit layer rules), Android Lint, detekt with ktlint (`config/detekt/`), a Kover line-coverage floor, and CPD copy-paste detection. Existing detekt and lint findings are captured in baseline files, so new findings fail the build.
