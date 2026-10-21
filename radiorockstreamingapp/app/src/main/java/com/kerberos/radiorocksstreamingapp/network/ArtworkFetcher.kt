package com.kerberos.radiorocksstreamingapp.network

import com.kerberos.radiorocksstreamingapp.data.TrackTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Album artwork via iTunes Search API (free, no key).
 * Query: "Artist Title" → artworkUrl100, upscaled to 300x300.
 * In-memory cache per track so we fetch once per song.
 */
object ArtworkFetcher {

    private val cache = mutableMapOf<String, String?>()

    suspend fun fetchArtwork(track: String): String? = withContext(Dispatchers.IO) {
        if (track.isBlank() || track == TrackTitles.LIVE_PLACEHOLDER) return@withContext null
        cache.getOrPut(track) {
            try {
                val query = URLEncoder.encode(track, "UTF-8")
                val url = URL(
                    "https://itunes.apple.com/search?term=$query" +
                        "&media=music&entity=song&limit=1"
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                if (conn.responseCode != 200) return@getOrPut null
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val results = JSONObject(json).optJSONArray("results") ?: return@getOrPut null
                if (results.length() == 0) return@getOrPut null
                val art100 = results.getJSONObject(0).optString("artworkUrl100", "")
                if (art100.isBlank()) null
                // 100x100 → 300x300 (same image, higher-res variant exists on mzstatic)
                else art100.replace("100x100bb.jpg", "300x300bb.jpg")
            } catch (_: Exception) {
                null
            }
        }
    }
}
