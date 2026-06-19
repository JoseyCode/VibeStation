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

## Architecture & Core Components

VibeStation follows a standard Android framework structure:

*   **[`SplashActivity`](app/src/main/java/com/example/retroclone/SplashActivity.java)**: Greets you with a random vibe-coded slogan on start.
*   **[`MainActivity`](app/src/main/java/com/example/retroclone/MainActivity.java)**: Coordinates the interface, handles standard storage query routines, and manages user interaction.
*   **[`AudioService`](app/src/main/java/com/example/retroclone/AudioService.java)**: The single source of truth for audio playback, integrating background foreground service lifecycles, media notifications, lockscreen playback state (`MediaSessionCompat`), and audio focus handling.
*   **[`VisualizerView`](app/src/main/java/com/example/retroclone/VisualizerView.java)**: Custom canvas view that plots and paints real-time frequency-domain data (FFT) as a smooth Bezier wave.
*   **[`Models`](app/src/main/java/com/example/retroclone/Models.java)**: Lightweight, clean data models for `Song`, `Album`, and `Playlist`.

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

---

## Roadmap & Planned Upgrades

We are planning to modernize the entire codebase in stages:
*   [ ] **Stage 1**: Package renaming from `com.example.retroclone` to `com.vibestation.app`.
*   [ ] **Stage 2**: Database upgrade from SharedPreferences JSON storage to **Room Database**.
*   [ ] **Stage 3**: Code modernization migrating the legacy Java codebase to **Kotlin** with Coroutines and Flows.
*   [ ] **Stage 4**: UI/UX overhaul replacing XML/Imperative layouts with **Jetpack Compose**.
*   [ ] **Stage 5**: Launching the home screen play widget (`vibe_widget.xml`).
