package com.boogie.vibestation.share

import java.security.SecureRandom

/**
 * The identity a phone advertises while Share Mode is open, packed into the discovery name so the other
 * phone can decide whether to connect before any connection exists.
 *
 * @property versionCode Android versionCode of the running app; both phones must match exactly.
 * @property nonce       Random per-session id, 8 lowercase hex digits. Nearby endpoint ids differ per side,
 *                       so this is the shared value both phones compare to decide who requests the connection.
 * @property deviceName  Friendly name shown to the user, length-limited.
 */
internal data class ShareHello(val versionCode: Int, val nonce: String, val deviceName: String) {

    /**
     * Packs this hello into the string advertised to nearby phones.
     *
     * @return Text that [decode] reads back.
     */
    fun encode(): String = listOf(TAG, versionCode, nonce, cleanName(deviceName)).joinToString(SEPARATOR)

    /**
     * Decides which of two phones sends the connection request, so exactly one does. The phone with the
     * smaller nonce requests; the other waits. Equal nonces (astronomically unlikely) make both request.
     *
     * @param other The hello of the phone that was found.
     * @return True if this phone should call requestConnection.
     */
    fun shouldRequestTo(other: ShareHello): Boolean = nonce <= other.nonce

    /** Parsing, creation, and the wire constants. */
    companion object {
        private const val TAG = "VS1"
        private const val SEPARATOR = "|"
        private const val PART_COUNT = 4
        private const val VERSION_PART = 1
        private const val NONCE_PART = 2
        private const val NAME_PART = 3
        private const val NONCE_BYTES = 4
        private const val MAX_NAME = 30
        private val NONCE_FORMAT = Regex("[0-9a-f]{8}")

        /**
         * Reads an advertised name.
         *
         * @param raw Name as received from a nearby endpoint.
         * @return The hello, or null when [raw] is not a VibeStation share advertisement.
         */
        fun decode(raw: String): ShareHello? {
            val parts = raw.split(SEPARATOR, limit = PART_COUNT)
            if (parts.size != PART_COUNT || parts[0] != TAG || !NONCE_FORMAT.matches(parts[NONCE_PART])) return null
            return parts[VERSION_PART].toIntOrNull()?.let {
                ShareHello(it, parts[NONCE_PART], cleanName(parts[NAME_PART]))
            }
        }

        /**
         * Creates a hello with a fresh random nonce.
         *
         * @param versionCode Version code of the running app.
         * @param deviceName  Friendly device name.
         * @param random      Source of the nonce; injectable for tests.
         * @return A new hello.
         */
        fun create(versionCode: Int, deviceName: String, random: SecureRandom = SecureRandom()): ShareHello {
            val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
            return ShareHello(versionCode, bytes.joinToString("") { "%02x".format(it) }, cleanName(deviceName))
        }

        /** Removes control characters and the separator, trims, and caps the length. */
        private fun cleanName(name: String): String =
            name.filter { !it.isISOControl() && it != SEPARATOR[0] }.trim().take(MAX_NAME)
    }
}
