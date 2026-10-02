package com.boogie.vibestation.share

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Why a receiver turned an offer down. */
internal enum class DeclineReason {
    /** The person chose no. */
    USER,

    /** The receiver was already busy with another offer or transfer. */
    BUSY,

    /** The receiver does not have enough free storage. */
    NO_SPACE
}

/**
 * Control messages the two phones exchange over the connection. Audio itself travels as files, tagged with
 * the track's index in the offered manifest; these messages only negotiate. Messages come from an
 * untrusted peer, so [parse] validates everything.
 */
internal sealed interface ShareMessage {

    /** The sender offers [manifest]. */
    data class Offer(val manifest: ShareManifest) : ShareMessage

    /** The receiver accepts and asks for the tracks at [indices] (empty when it already has everything). */
    data class Accept(val indices: List<Int>) : ShareMessage

    /** The receiver refuses the offer. */
    data class Decline(val reason: DeclineReason) : ShareMessage

    /** The receiver finished importing: [stored] tracks were written, [failed] could not be. */
    data class Done(val stored: Int, val failed: Int) : ShareMessage

    /**
     * Serializes the message for sending.
     *
     * @return JSON text that [parse] reads back.
     */
    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is Offer -> json.put(TYPE, "offer").put("manifest", manifest.toJson())
            is Accept -> json.put(TYPE, "accept").put("indices", JSONArray(indices))
            is Decline -> json.put(TYPE, "decline").put("reason", reason.name)
            is Done -> json.put(TYPE, "done").put("stored", stored).put("failed", failed)
        }
        return json.toString()
    }

    /** Parsing. */
    companion object {
        private const val TYPE = "type"

        /**
         * Parses a message from the peer.
         *
         * @param text JSON text received.
         * @return The message.
         * @throws IllegalArgumentException If the text is malformed, of an unknown type, or has invalid values.
         */
        fun parse(text: String): ShareMessage {
            try {
                val json = JSONObject(text)
                return when (val type = json.getString(TYPE)) {
                    "offer" -> Offer(ShareManifest.fromJson(json.getString("manifest")))
                    "accept" -> Accept(parseIndices(json.getJSONArray("indices")))
                    "decline" -> Decline(DeclineReason.valueOf(json.getString("reason")))
                    "done" -> Done(nonNegative(json.getInt("stored")), nonNegative(json.getInt("failed")))
                    else -> throw IllegalArgumentException("Unknown message type: ${type.take(ShareManifest.MAX_TEXT)}")
                }
            } catch (e: JSONException) {
                throw IllegalArgumentException("Malformed message", e)
            }
        }

        private fun parseIndices(array: JSONArray): List<Int> {
            require(array.length() <= ShareManifest.MAX_TRACKS) { "Too many indices" }
            return (0 until array.length()).map { nonNegative(array.getInt(it)) }
        }

        private fun nonNegative(value: Int): Int {
            require(value >= 0) { "Negative value" }
            return value
        }
    }
}
