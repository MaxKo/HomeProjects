package com.kerberos.radioroksstreamingapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RadioMetadataFetcher {

    private const val STATUS_JSON_URL = "https://online.radioroks.ua/status-json.xsl"

    fun pollTrackMetadata(pollIntervalMs: Long = 10_000L): Flow<String?> = flow {
        while (true) {
            val trackTitle = fetchCurrentTrack()
            if (!trackTitle.isNullOrBlank()) {
                emit(trackTitle)
            }
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchCurrentTrack(): String? {
        return try {
            val url = URL(STATUS_JSON_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                parseTrackTitleFromJson(jsonString)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTrackTitleFromJson(jsonString: String): String? {
        return try {
            val root = JSONObject(jsonString)
            val icestats = root.optJSONObject("icestats") ?: return null
            val sources = icestats.optJSONArray("source") ?: return null

            var mainTitle: String? = null
            var fallbackTitle: String? = null

            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val listenUrl = source.optString("listenurl", "")
                val title = source.optString("title", "").trim()

                if (title.isNotEmpty() && title != "null" && title != "Radio_ROKS" && title != "Radio ROKS") {
                    if (listenUrl.contains("RadioROKS") || listenUrl.contains("Radio_ROKS")) {
                        mainTitle = title
                        break
                    } else if (fallbackTitle == null) {
                        fallbackTitle = title
                    }
                }
            }
            mainTitle ?: fallbackTitle
        } catch (_: Exception) {
            null
        }
    }
}
