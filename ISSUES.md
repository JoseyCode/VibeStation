# VibeStation Issues Log

## Issue 1: Application Not Responding (ANR) hangs on startup and sync completion
*   **Description**: App becomes unresponsive and triggers ANR dialogs when loading or refreshing the music library.
*   **Root Cause**: Synchronous MediaStore database queries and O(P * S) nested loops for matching playlists are executed directly on the main UI thread.
*   **Resolution**: 
    1. Offloaded MediaStore queries and playlist loading logic to a background thread.
    2. Optimized the playlist track matching loop from O(P * S) to O(N) using HashMap lookups.
    3. Posted updates back to the UI thread safely.
*   **Status**: Resolved

## Issue 2: Partial/corrupt files left behind on failed or interrupted sync transfers
*   **Description**: If the connection drops during sync, partially transferred files are saved. This leaves corrupted files in the library and prevents the sync process from redownloading them next time.
*   **Root Cause**: Incomplete streams were not cleaned up from Android's MediaStore or the Express server directory, causing the keys to match as already existing.
*   **Resolution**:
    1. Client-side: Wrapped download stream writing in a try-catch and deleted the partial MediaStore database record on exception.
    2. Server-side: Modified the library scanner on the server to detect 0-byte files, delete them on the fly, and skip listing them.
*   **Status**: Resolved

## Issue 3: Web player plays incorrect song when choosing from filtered search results
*   **Description**: Searching inside the search bar filters the UI, but selecting any item plays the song at that index from the original unfiltered library list instead of the search results. In addition, previous/next buttons iterate through the unsorted library.
*   **Root Cause**: The client-side click handler and navigation controls query the static global `songs` array by index instead of using the currently active search-filtered queue.
*   **Resolution**:
    1. Introduced a `currentQueue` array representing the active list of playable tracks (which updates as searches are typed).
    2. Updated `playTrack()`, navigation buttons, and play/pause controls to reference `currentQueue` instead of `songs` for bounds and track retrieval.
*   **Status**: Resolved

## Issue 4: App freezes (ANRs) during playlist backup export and restore operations
*   **Description**: When triggering playlist backups, the app can freeze or trigger ANR warnings if there are custom playlist images.
*   **Root Cause**: Base64 encoding, scaling, and compression of images, along with backup reading, were executed synchronously inside the activity result callbacks on the main UI thread.
*   **Resolution**: Offloaded the backup writing and restore parsing blocks to background threads, updating the UI safely via `runOnUiThread`.
*   **Status**: Resolved

## Issue 5: Sync crashes on track names containing Unicode (e.g., Japanese, Cyrillic) or slashes
*   **Description**: Syncing consistently crashed or misidentified tracks with weird characters, slashes, or non-ASCII characters (e.g. SoundCloud tracks).
*   **Root Cause**:
    1. Keys for international character tracks collided because ASCII-only regex stripped them completely.
    2. Slashes in track titles caused filesystem path insertion crashes.
    3. Missing metadata fields in server JSON threw JSONExceptions.
*   **Resolution**:
    1. Used Unicode regex groups `\\p{L}` (letters) and `\\p{N}` (digits) to preserve international characters.
    2. Stripped illegal path characters from filenames during download.
    3. Switched to JSON `optString` to tolerate missing metadata fields.
*   **Status**: Resolved

## Issue 6: App visualizer is extremely laggy and slow during playback
*   **Labels**: `bug`, `performance`, `app`
*   **Description**: The real-time FFT bezier visualizer causes noticeable UI lag, stuttering, and drops in frame rate during audio playback.
*   **Root Cause**: 
    1. The visualizer processes and renders all 341 bins (`fftBytes.length / 3`) directly on the main UI thread.
    2. Generating and rasterizing a complex `Path` with 341 `cubicTo` bezier curves on every frame is highly CPU-intensive.
    3. Math calculations (like `Math.sqrt`) are performed on the main thread for all bins.
    4. Choppy rendering (10–20fps) occurs because invalidation only occurs when new data is captured, rather than animating smoothly at the device's display refresh rate (60Hz/120Hz).
*   **Proposed Resolution**:
    1. **Logarithmic Downsampling**: Group the FFT spectrum into 40 visually distinct bands using a logarithmic scale to match human hearing and drastically reduce rendering complexity.
    2. **Background Math Offloading**: Offload all heavy FFT calculations, magnitudes, and logarithmic binning to the background `Visualizer` thread inside `updateVisualizer()`.
    3. **Interpolated Reactive Rendering**: Perform smooth linear interpolation (LERP) on the UI thread and use `postInvalidateOnAnimation()` to render the animation smoothly at the screen's native refresh rate (up to 120Hz), pausing when settled to conserve battery.
*   **Status**: Open (Drafting Fix)


