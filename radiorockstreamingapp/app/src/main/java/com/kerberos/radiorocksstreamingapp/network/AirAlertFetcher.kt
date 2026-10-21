package com.kerberos.radiorocksstreamingapp.network

import com.kerberos.radiorocksstreamingapp.data.LocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL

enum class AlertLevel {
    NONE,   // Normal / No Alert
    YELLOW, // Yellow Alert (Drone Threat)
    RED     // Red Alert (Missile / Mass Air Raid)
}

object AirAlertFetcher {

    private const val ALERTS_REPORT_URL = "https://alerts.in.ua/llm"

    /**
     * Polls alerts.in.ua live report every 30 seconds for the user''s
     * specific region (or last known persisted region).
     */
    fun pollAlertStatus(pollIntervalMs: Long = 30_000L): Flow<AlertLevel> = flow {
        while (true) {
            val loc = LocationStore.currentLocation.value
            val level = fetchAlertLevelForRegion(loc.regionName, loc.cityName)
            emit(level)
            kotlinx.coroutines.delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    fun fetchAlertLevelForRegion(regionName: String, cityName: String): AlertLevel {
        val reportText = fetchReportText() ?: return AlertLevel.NONE
        val lowerReport = reportText.lowercase()

        val regionTerm = extractSearchTerm(regionName)
        val cityTerm = extractSearchTerm(cityName)

        // Scope strictly to section 3 (stop at next "## " header)
        val section3Marker = "## 3. current warning status"
        val section3Start = lowerReport.indexOf(section3Marker)
        if (section3Start == -1) return AlertLevel.NONE

        val nextSection = lowerReport.indexOf("\n## ", section3Start + section3Marker.length)
        val section3 = if (nextSection == -1)
            lowerReport.substring(section3Start)
        else
            lowerReport.substring(section3Start, nextSection)

        // Only match if the region is a bolded entry: **Region Name**
        // (meaning it is currently under active warning in section 3)
        var entryStart = -1
        if (regionTerm.isNotBlank()) {
            entryStart = section3.indexOf("**$regionTerm")
        }
        if (entryStart == -1 && cityTerm.isNotBlank()) {
            entryStart = section3.indexOf("**$cityTerm")
        }

        if (entryStart == -1) {
            return AlertLevel.NONE
        }

        // Grab entry text up to the next bolded entry or end of section
        val nextEntry = section3.indexOf("\n**", entryStart + 2)
        val snippetEnd = if (nextEntry == -1) section3.length else nextEntry
        val snippet = section3.substring(entryStart, snippetEnd)

        return when {
            snippet.contains("red") || snippet.contains("missile") || snippet.contains("mass-strike") -> AlertLevel.RED
            snippet.contains("yellow") || snippet.contains("drone") -> AlertLevel.YELLOW
            else -> AlertLevel.NONE
        }
    }

    private fun fetchReportText(): String? {
        return try {
            val url = URL(ALERTS_REPORT_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                instanceFollowRedirects = true
            }

            if (connection.responseCode == 200 || connection.responseCode == 302) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            android.util.Log.d("AirAlertFetcher", "Error fetching report: ${e.message}")
            null
        }
    }

    private fun extractSearchTerm(name: String): String {
        return name.lowercase()
            .replace("oblast", "")
            .replace("область", "")
            .replace("город", "")
            .replace("місто", "")
            .replace("m.", "")
            .trim()
            .take(6) // Take root e.g. "cherka", "kyiv", "dnipro"
    }
}