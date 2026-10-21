package com.kerberos.radiorocksstreamingapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

object WeatherFetcher {

    /**
     * Advanced Weather Prediction & Severe Warning Engine (Ukrainian locale):
     * Analyzes 7-day hourly and daily forecast data from Open-Meteo.
     */
    suspend fun fetchWeatherSummary(): String? = withContext(Dispatchers.IO) {
        try {
            val (lat, lon) = fetchCoordinates()
            fetchOpenMeteoForecast(lat, lon)
        } catch (e: Exception) {
            android.util.Log.d("WeatherFetcher", "Error fetching weather: ${e.message}")
            null
        }
    }

    private suspend fun fetchCoordinates(): Pair<Double, Double> {
        val loc = com.kerberos.radiorocksstreamingapp.data.LocationStore.refreshLocation()
        return Pair(loc.latitude, loc.longitude)
    }

    private fun fetchOpenMeteoForecast(lat: Double, lon: Double): String? {
        val apiUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=precipitation_probability,precipitation,weathercode,wind_speed_10m,wind_gusts_10m&daily=weathercode,precipitation_sum,precipitation_probability_max,temperature_2m_max,temperature_2m_min,wind_speed_10m_max&forecast_days=7&timezone=auto"
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
        }

        if (connection.responseCode != 200) return null

        val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        val hourly = root.optJSONObject("hourly") ?: return null
        val daily = root.optJSONObject("daily")

        val times = hourly.optJSONArray("time") ?: return null
        val probs = hourly.optJSONArray("precipitation_probability") ?: return null
        val precips = hourly.optJSONArray("precipitation") ?: return null
        val codes = hourly.optJSONArray("weathercode") ?: return null
        val windGusts = hourly.optJSONArray("wind_gusts_10m") ?: hourly.optJSONArray("wind_speed_10m")

        val currentHourIndex = getCurrentHourIndex(times)
        val endOfDayIndex = getEndOfDayIndex(times, currentHourIndex)

        // =========================================================================
        // PRIORITY 1: EXTREME WEATHER WARNINGS (Thunderstorm, Blizzard, High Wind)
        // =========================================================================

        // 1A. Thunderstorm Check (WMO 95, 96, 99) in the next 12 hours
        for (i in currentHourIndex until minOf(times.length(), currentHourIndex + 12)) {
            val code = codes.optInt(i, 0)
            if (code in 95..99) {
                val hoursUntil = i - currentHourIndex
                return if (hoursUntil == 0) "⚡️ Зараз гроза!" else "⚡️ Гроза через ~$hoursUntil год"
            }
        }

        // 1B. Blizzard / Heavy Snowstorm Check (WMO 75, 85, 86 or snow + wind > 30 km/h)
        for (i in currentHourIndex until minOf(times.length(), currentHourIndex + 12)) {
            val code = codes.optInt(i, 0)
            val gust = windGusts?.optDouble(i, 0.0) ?: 0.0
            if (code in listOf(75, 85, 86) || (isSnowCode(code) && gust >= 35.0)) {
                val hoursUntil = i - currentHourIndex
                return if (hoursUntil == 0) "💨 Завірюха / Снігова буря!" else "💨 Завірюха через ~$hoursUntil год"
            }
        }

        // 1C. High Wind / Hurricane / Storm Check (Gusts >= 48 km/h ~ 13.5 m/s)
        for (i in currentHourIndex until minOf(times.length(), currentHourIndex + 12)) {
            val gustKmH = windGusts?.optDouble(i, 0.0) ?: 0.0
            if (gustKmH >= 48.0) {
                val gustMS = (gustKmH / 3.6).roundToInt()
                val hoursUntil = i - currentHourIndex
                val label = if (gustKmH >= 65.0) "🌪 Ураган" else "💨 Штормовий вітер"
                return if (hoursUntil == 0) "$label ($gustMS м/с)!" else "$label ($gustMS м/с) через ~$hoursUntil год"
            }
        }

        // =========================================================================
        // PRIORITY 2: SHARP TEMPERATURE SHIFT (Похолодання / Потепління сьогодні-завтра)
        // =========================================================================
        if (daily != null) {
            val dailyMax = daily.optJSONArray("temperature_2m_max")
            if (dailyMax != null && dailyMax.length() > 1) {
                val todayMax = dailyMax.optDouble(0, 0.0).roundToInt()
                val tomorrowMax = dailyMax.optDouble(1, 0.0).roundToInt()
                val tempDiff = tomorrowMax - todayMax

                // Sharp temperature drop (>= 6°C)
                if (tempDiff <= -6) {
                    val sign = if (tomorrowMax > 0) "+$tomorrowMax" else "$tomorrowMax"
                    return "📉 Завтра різке похолодання (до $sign°C)"
                }
                // Sharp temperature jump (>= 6°C)
                if (tempDiff >= 6) {
                    val sign = if (tomorrowMax > 0) "+$tomorrowMax" else "$tomorrowMax"
                    return "📈 Завтра різке потепління (до $sign°C)"
                }
            }
        }

        // =========================================================================
        // PRIORITY 3: RAIN / SNOW PREDICTIONS
        // =========================================================================
        val isCurrentlyPrecip = isPrecipAt(currentHourIndex, codes, precips, probs)
        val currentCode = codes.optInt(currentHourIndex, 0)
        val currentPrecipIcon = if (isSnowCode(currentCode)) "❄️ Сніг" else "🌧 Дощ"

        // Raining/Snowing RIGHT NOW
        if (isCurrentlyPrecip) {
            var stopIndex: Int? = null
            for (i in (currentHourIndex + 1) until minOf(times.length(), currentHourIndex + 24)) {
                if (!isPrecipAt(i, codes, precips, probs)) {
                    stopIndex = i
                    break
                }
            }

            return if (stopIndex == null || stopIndex > endOfDayIndex) {
                "$currentPrecipIcon увесь день"
            } else {
                val hoursRemaining = stopIndex - currentHourIndex
                "$currentPrecipIcon ще ~$hoursRemaining год, потім без опадів"
            }
        }

        // Rain/Snow starts TODAY
        var precipStartIndexToday: Int? = null
        var precipStartCode = 0

        for (i in (currentHourIndex + 1)..endOfDayIndex) {
            if (isPrecipAt(i, codes, precips, probs)) {
                precipStartIndexToday = i
                precipStartCode = codes.optInt(i, 0)
                break
            }
        }

        if (precipStartIndexToday != null) {
            val hoursUntil = precipStartIndexToday - currentHourIndex
            val icon = if (isSnowCode(precipStartCode)) "❄️ Сніг" else "🌧 Дощ"

            var precipStopIndex: Int? = null
            for (j in (precipStartIndexToday + 1)..endOfDayIndex) {
                if (!isPrecipAt(j, codes, precips, probs)) {
                    precipStopIndex = j
                    break
                }
            }

            return when {
                hoursUntil >= 8 -> {
                    val hourStr = times.optString(precipStartIndexToday, "").takeLast(5)
                    "$icon пізно ввечері (~$hourStr)"
                }
                precipStopIndex == null || precipStopIndex > endOfDayIndex -> {
                    "$icon через ~$hoursUntil год до кінця дня"
                }
                else -> {
                    "$icon через ~$hoursUntil год"
                }
            }
        }

        // =========================================================================
        // PRIORITY 4: DRY WEATHER & MULTI-DAY FORECAST
        // =========================================================================
        val currentSkyIcon = if (currentCode in 0..1) "☀️" else "☁️"

        if (daily != null) {
            val dailyPrecipSums = daily.optJSONArray("precipitation_sum")
            val dailyProbs = daily.optJSONArray("precipitation_probability_max")
            val dailyTimes = daily.optJSONArray("time")

            if (dailyPrecipSums != null && dailyTimes != null && dailyPrecipSums.length() > 1) {
                // Check Tomorrow (index 1)
                val tomorrowPrecip = dailyPrecipSums.optDouble(1, 0.0)
                val tomorrowProb = dailyProbs?.optInt(1, 0) ?: 0

                if (tomorrowPrecip > 0.4 || tomorrowProb >= 40) {
                    val label = if (currentSkyIcon == "☀️") "☀️ До кінця дня ясно, завтра дощ" else "☁️ Без опадів, завтра дощ"
                    return label
                }

                // Check rest of the week (index 2..6)
                for (d in 2 until dailyTimes.length()) {
                    val dayPrecip = dailyPrecipSums.optDouble(d, 0.0)
                    val dayProb = dailyProbs?.optInt(d, 0) ?: 0

                    if (dayPrecip > 0.5 || dayProb >= 45) {
                        val dateStr = dailyTimes.optString(d, "")
                        val dayName = getDayOfWeekNameName(dateStr)
                        return if (dayName != null) {
                            "$currentSkyIcon До $dayName без опадів"
                        } else {
                            "$currentSkyIcon До кінця тижня без опадів"
                        }
                    }
                }
            }
        }

        return if (currentSkyIcon == "☀️") "☀️ До кінця тижня без опадів" else "☁️ До кінця тижня без опадів"
    }

    private fun isPrecipAt(
        index: Int,
        codes: org.json.JSONArray,
        precips: org.json.JSONArray,
        probs: org.json.JSONArray
    ): Boolean {
        if (index < 0 || index >= codes.length()) return false
        val code = codes.optInt(index, 0)
        val precip = precips.optDouble(index, 0.0)
        val prob = probs.optInt(index, 0)
        return isPrecipitationCode(code) || precip >= 0.25 || prob >= 40
    }

    private fun getCurrentHourIndex(times: org.json.JSONArray): Int {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        return currentHour.coerceIn(0, times.length() - 1)
    }

    private fun getEndOfDayIndex(times: org.json.JSONArray, currentHourIndex: Int): Int {
        val calendar = Calendar.getInstance()
        val remainingHoursToday = 23 - calendar.get(Calendar.HOUR_OF_DAY)
        return (currentHourIndex + remainingHoursToday).coerceAtMost(times.length() - 1)
    }

    private fun isPrecipitationCode(code: Int): Boolean {
        return code in 51..67 || code in 80..82 || code in 95..99 || isSnowCode(code)
    }

    private fun isSnowCode(code: Int): Boolean {
        return code in 71..77 || code in 85..86
    }

    private fun getDayOfWeekNameName(dateIsoStr: String): String? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = sdf.parse(dateIsoStr) ?: return null
            val cal = Calendar.getInstance()
            cal.time = date
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "понеділка"
                Calendar.TUESDAY -> "вівторка"
                Calendar.WEDNESDAY -> "середи"
                Calendar.THURSDAY -> "четверга"
                Calendar.FRIDAY -> "п'ятниці"
                Calendar.SATURDAY -> "суботи"
                Calendar.SUNDAY -> "неділі"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
