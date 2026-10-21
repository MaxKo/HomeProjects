package com.kerberos.radiorocksstreamingapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * Dual-source track metadata:
 *  1. PRIMARY — ICY StreamTitle read directly from the audio stream
 *     (icy-metaint header → skip audio bytes → metadata block).
 *  2. FALLBACK — current song parsed from https://www.radioroks.ua/playlist/
 *     (<span class="song"><b>Artist</b> — Title</span>).
 *
 * Emits (track, fromSite) pairs so UI can badge LIVE vs SITE source.
 */
object RadioMetadataFetcher {

    private const val PLAYLIST_URL = "https://www.radioroks.ua/playlist/"

    data class TrackResult(val track: String, val fromSite: Boolean)

    fun pollTrackMetadata(
        streamUrl: () -> String,
        pollIntervalMs: Long = 30_000L
    ): Flow<TrackResult> = flow {
        while (true) {
            val icy = fetchIcyTitle(streamUrl())
            if (!icy.isNullOrBlank()) {
                emit(TrackResult(icy, fromSite = false))
            } else {
                val site = fetchSiteTrack()
                if (!site.isNullOrBlank()) emit(TrackResult(site, fromSite = true))
            }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** One-shot ICY StreamTitle fetch from the audio stream. Null on failure. */
    fun fetchIcyTitle(streamUrl: String): String? {
        return try {
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Icy-MetaData", "1")
                setRequestProperty("User-Agent", "RadioRocksApp/1.0")
                connectTimeout = 8000
                readTimeout = 8000
                connect()
            }
            val metaint = conn.getHeaderField("icy-metaint")?.toIntOrNull() ?: return null
            val input = conn.inputStream
            var toSkip = metaint.toLong()
            val buf = ByteArray(4096)
            while (toSkip > 0) {
                val r = input.read(buf, 0, min(toSkip, buf.size.toLong()).toInt())
                if (r <= 0) break
                toSkip -= r
            }
            val lenByte = input.read()
            if (lenByte <= 0) return null
            val metaLen = lenByte * 16
            val metaBytes = ByteArray(metaLen)
            var read = 0
            while (read < metaLen) {
                val r = input.read(metaBytes, read, metaLen - read)
                if (r <= 0) break
                read += r
            }
            input.close()
            val meta = String(metaBytes, 0, read, Charsets.UTF_8)
            Regex("StreamTitle='([^']*)'").find(meta)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() && !it.contains("radioroks", ignoreCase = true) && !it.contains("Radio_ROKS") }
        } catch (_: Exception) {
            null
        }
    }

    /** Current song from radioroks.ua/playlist/. Null on failure. */
    fun fetchSiteTrack(): String? {
        return try {
            val conn = (URL(PLAYLIST_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) RadioRocksApp/1.0")
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return null
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseSongSpan(html)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses: <span ... class="song"><b>Artist</b> &mdash; Title</span>
     * First match = currently on air (page header).
     */
    fun parseSongSpan(html: String): String? {
        val regex = Regex(
            "<span[^>]*class=\"song\"[^>]*><b>(.*?)</b>\\s*(?:&mdash;|—|-)\\s*(.*?)</span>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = regex.find(html) ?: return null
        val artist = match.groupValues[1].trim().htmlUnescaped()
        val title = match.groupValues[2].trim().htmlUnescaped()
        if (artist.isBlank() || title.isBlank()) return null
        if (title.contains("Radio_ROKS") || title.contains("radioroks", ignoreCase = true)) return null
        return "$artist - $title"
    }

    private fun String.htmlUnescaped(): String =
        this.replace("&amp;", "&")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
}
