package com.kerberos.radiorocksstreamingapp.data

/**
 * Shared parsing helpers for track titles.
 *
 * Used by both PlaybackService (ICY stream titles + site fallback) and
 * RadioViewModel (site polling) so the two never disagree about what counts
 * as a "song" vs. a station/DJ announcement.
 */
object TrackTitles {

    /** Placeholder shown while no song title is known (DJ talk, station ID, etc.). */
    const val LIVE_PLACEHOLDER = "Live Stream"

    /**
     * Split "Artist - Title" into (artist, title).
     * Falls back to the station name as artist when there is no separator.
     * Returns null when the input is unusable (blank).
     *
     * @param decodeUrl URL-decode the input first (ICY StreamTitle values are
     *   percent-encoded). Decoding failures fall back to the raw string.
     */
    fun parse(rawTitle: String, decodeUrl: Boolean = false): Pair<String, String>? {
        val trimmed = if (decodeUrl) urlDecode(rawTitle.trim()) else rawTitle.trim()
        if (trimmed.isBlank()) return null
        val parts = trimmed.split(" - ", limit = 2)
        val (artist, title) = if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            StreamConstants.STATION_NAME to trimmed
        }
        if (title.isBlank()) return null
        return artist to title
    }

    /** Percent-decoding that never throws: malformed input is returned as-is. */
    private fun urlDecode(raw: String): String =
        try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }

    /**
     * Returns true when the raw title indicates that no song is currently
     * playing (DJ talk, station ID, promo, ...).
     *
     * Station names are matched on word boundaries so that real songs are not
     * swallowed (e.g. "Live Here Now" must stay a real title).
     */
    fun isNonSong(rawTitle: String): Boolean {
        val lower = rawTitle.trim().lowercase()
        if (lower.isBlank()) return true
        val station = StreamConstants.STATION_NAME.lowercase()   // "radio roks"
        return containsWord(lower, station) ||        // "Radio ROKS - Рок. Тільки рок!"
            containsWord(lower, "radioroks") ||       // radioroks.ua
            containsWord(lower, "radio_roks") ||
            containsWord(lower, "on air") ||
            containsWord(lower, "direct") ||
            containsWord(lower, "efir") ||            // "Прямий ефір"
            containsWord(lower, "прямий") ||
            containsWord(lower, "диктор") ||
            containsWord(lower, "dj") ||
            isLiveAnnouncement(lower)
    }

    /**
     * "Live" alone is not enough to call a title an announcement ("Live Here Now"
     * is a real song), so it only counts when the title is a generic live
     * broadcast label such as "Live", "Live Stream" or "Live Radio".
     */
    private fun isLiveAnnouncement(lower: String): Boolean {
        val generic = setOf(
            "live", "live stream", "live radio", "live broadcast",
            "live now", "live from", "прямой эфир", "прямий ефір"
        )
        return generic.any { it == lower }
    }

    /**
     * Returns true when the raw title is a PROMO/announcement break.
     */
    fun isAdvertisement(rawTitle: String): Boolean {
        val lower = rawTitle.trim().lowercase()
        if (lower.isBlank()) return false
        val station = StreamConstants.STATION_NAME.lowercase()
        if (containsWord(lower, station) && !isLiveAnnouncement(lower)) return true
        return isNonSong(rawTitle)
    }

    /**
     * True when [needle] occurs in [haystack] as a whole word.
     * Word boundaries are non-alphanumeric characters.
     */
    private fun containsWord(haystack: String, needle: String): Boolean {
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val before = index - 1
            val after = index + needle.length
            val startOk = before < 0 || !haystack[before].isLetterOrDigit()
            val endOk = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (startOk && endOk) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}
