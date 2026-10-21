package com.kerberos.radiorocksstreamingapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics via https://api.lyrics.ovh/v1/{artist}/{title} (free, no API key).
 * Caches per track so we fetch once per song.
 */
object LyricsFetcher {

    private val cache = mutableMapOf<String, String?>()

    suspend fun fetchLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext null
        val key = "$artist - $title".lowercase()
        cache.getOrPut(key) {
            try {
                val encArtist = URLEncoder.encode(artist, "UTF-8")
                val encTitle = URLEncoder.encode(title, "UTF-8")
                val url = URL("https://api.lyrics.ovh/v1/$encArtist/$encTitle")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "RadioRocksApp/1.0")
                }
                if (conn.responseCode != 200) return@getOrPut null
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val lyrics = JSONObject(json).optString("lyrics", "")
                if (lyrics.isBlank() || lyrics == "Instrumental") null else lyrics
            } catch (_: Exception) {
                null
            }
        }
    }
}
