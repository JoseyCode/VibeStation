package com.boogie.vibestation.util

/** The audio formats the app stores and the sync server accepts, keyed by lowercase file extension. */
object AudioFormats {

    private const val DEFAULT_MIME = "audio/mpeg"

    private val MIME_BY_EXTENSION = linkedMapOf(
        "mp3" to DEFAULT_MIME,
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/x-wav"
    )

    /**
     * Extracts the lowercase extension of a file name or path.
     *
     * @param name File name or path.
     * @return Extension without the dot, or an empty string when there is none.
     */
    fun extensionOf(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /**
     * Looks up the MIME type for an extension.
     *
     * @param extension Extension without the dot, any case.
     * @return The MIME type, or null when the format is not supported.
     */
    fun mimeForExtension(extension: String): String? = MIME_BY_EXTENSION[extension.lowercase()]

    /**
     * Finds the extension that belongs to a MIME type.
     *
     * @param mimeType MIME type such as `audio/flac`.
     * @return The matching extension, or `mp3` when the type is unknown.
     */
    fun extensionForMime(mimeType: String): String =
        MIME_BY_EXTENSION.entries
            .firstOrNull { it.value == mimeType }
            ?.key
            ?: "mp3"

    /**
     * Resolves a supported extension from a server-reported format, falling back to MP3.
     *
     * @param format Format string such as `flac`; may be blank for servers that do not report one.
     * @return A supported extension.
     */
    fun extensionForFormat(format: String): String = format.lowercase().takeIf { it in MIME_BY_EXTENSION } ?: "mp3"

    /**
     * Resolves the MIME type to upload or store a file under.
     *
     * @param name File name or path.
     * @return The MIME type for its extension, or `audio/mpeg` when the extension is unknown.
     */
    fun mimeOf(name: String): String = mimeForExtension(extensionOf(name)) ?: DEFAULT_MIME

    /**
     * Whether the sync server can store a file of this name.
     *
     * @param name File name or path.
     * @return True for supported audio formats.
     */
    fun isSupported(name: String): Boolean = extensionOf(name) in MIME_BY_EXTENSION
}
