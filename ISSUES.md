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
