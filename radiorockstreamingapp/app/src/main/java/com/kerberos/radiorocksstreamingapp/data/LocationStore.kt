package com.kerberos.radiorocksstreamingapp.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LocationInfo(
    val cityName: String,
    val regionName: String,
    val latitude: Double,
    val longitude: Double
)

data class RegionEntry(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

object LocationStore {

    private const val PREFS_NAME = "location_store_prefs"
    private const val KEY_CITY = "last_known_city"
    private const val KEY_REGION = "last_known_region"
    private const val KEY_LAT = "last_known_lat"
    private const val KEY_LON = "last_known_lon"
    private const val KEY_MANUAL_OVERRIDE = "manual_override"

    // All supported Ukrainian regions with approximate center coordinates
    val UKRRAINIAN_REGIONS: List<RegionEntry> = listOf(
        RegionEntry("Київ", 50.45, 30.52),
        RegionEntry("Вінниця", 49.23, 28.47),
        RegionEntry("Волинь", 50.75, 25.33),
        RegionEntry("Дніпро", 48.46, 35.04),
        RegionEntry("Донецьк", 48.01, 37.80),
        RegionEntry("Житомир", 50.25, 28.66),
        RegionEntry("Закарпаття", 48.62, 22.29),
        RegionEntry("Запоріжжя", 47.89, 35.14),
        RegionEntry("Івано-Франківськ", 48.92, 24.71),
        RegionEntry("Кропивницький", 48.85, 32.23),
        RegionEntry("Луганськ", 48.57, 39.31),
        RegionEntry("Львів", 49.84, 24.03),
        RegionEntry("Миколаїв", 46.98, 32.00),
        RegionEntry("Одеса", 46.48, 30.73),
        RegionEntry("Полтава", 49.59, 34.55),
        RegionEntry("Рівне", 50.62, 26.25),
        RegionEntry("Суми", 50.91, 34.80),
        RegionEntry("Тернопіль", 49.55, 25.59),
        RegionEntry("Харків", 49.99, 36.23),
        RegionEntry("Херсон", 46.64, 32.62),
        RegionEntry("Хмельницький", 49.42, 26.99),
        RegionEntry("Черкаси", 49.44, 32.26),
        RegionEntry("Чернівці", 48.29, 25.94),
        RegionEntry("Чернігів", 51.49, 31.30)
    )

    // Default fallback: Kyiv
    private val DEFAULT_LOCATION = LocationInfo(
        cityName = "Черкаси",
        regionName = "Черкаська область",
        latitude = 49.44,
        longitude = 32.26
    )

    private val _currentLocation = MutableStateFlow(DEFAULT_LOCATION)
    val currentLocation: StateFlow<LocationInfo> = _currentLocation.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadPersistedLocation()
        }
    }

    /**
     * Manually set the user's region. This overrides IP-based geolocation.
     */
    fun setManualRegion(regionName: String) {
        val entry = UKRRAINIAN_REGIONS.find { it.name.equals(regionName, ignoreCase = true) }
        if (entry != null) {
            val info = LocationInfo(
                cityName = entry.name,
                regionName = entry.name,
                latitude = entry.latitude,
                longitude = entry.longitude
            )
            prefs?.edit()
                ?.putString(KEY_CITY, entry.name)
                ?.putString(KEY_REGION, entry.name)
                ?.putFloat(KEY_LAT, entry.latitude.toFloat())
                ?.putFloat(KEY_LON, entry.longitude.toFloat())
                ?.putBoolean(KEY_MANUAL_OVERRIDE, true)
                ?.apply()

            _currentLocation.value = info
            android.util.Log.d("LocationStore", "Manual region set: $regionName")
        }
    }

    fun isManualOverride(): Boolean {
        return prefs?.getBoolean(KEY_MANUAL_OVERRIDE, false) ?: false
    }

    fun clearManualOverride() {
        prefs?.edit()?.putBoolean(KEY_MANUAL_OVERRIDE, false)?.apply()
    }

    private fun loadPersistedLocation() {
        val p = prefs ?: return
        val city = p.getString(KEY_CITY, null)
        val region = p.getString(KEY_REGION, null)
        val lat = p.getFloat(KEY_LAT, 50.45f).toDouble()
        val lon = p.getFloat(KEY_LON, 30.52f).toDouble()

        if (!city.isNullOrBlank() && !region.isNullOrBlank()) {
            _currentLocation.value = LocationInfo(city, region, lat, lon)
        }
    }

    suspend fun refreshLocation(): LocationInfo = withContext(Dispatchers.IO) {
        // If user has manually set a region, respect it — skip auto geolocation
        if (isManualOverride()) {
            return@withContext _currentLocation.value
        }

        // 1) GPS first — accurate to where the device physically is, so it works
        //    on mobile data (IP geolocation returns the carrier's city, e.g.
        //    "Київ" for a user actually in "Черкаси").
        val context = appContext
        if (context != null) {
            val gps = requestDeviceLocation(context)
            if (gps != null && isInsideUkraine(gps.latitude, gps.longitude)) {
                val region = matchNearestRegion(gps.latitude, gps.longitude)
                val info = LocationInfo(
                    cityName = region.name,
                    regionName = region.name,
                    latitude = gps.latitude,
                    longitude = gps.longitude
                )
                persist(info)
                _currentLocation.value = info
                android.util.Log.d(
                    "LocationStore",
                    "GPS resolved to region '${region.name}' (${gps.latitude}, ${gps.longitude})"
                )
                return@withContext info
            }
        }

        // 2) Fallback: IP geolocation (used when GPS is unavailable/denied).
        val ipInfo = fetchLocationFromIp()
        if (ipInfo != null) {
            persist(ipInfo)
            _currentLocation.value = ipInfo
            return@withContext ipInfo
        }

        return@withContext _currentLocation.value
    }

    private fun persist(info: LocationInfo) {
        prefs?.edit()
            ?.putString(KEY_CITY, info.cityName)
            ?.putString(KEY_REGION, info.regionName)
            ?.putFloat(KEY_LAT, info.latitude.toFloat())
            ?.putFloat(KEY_LON, info.longitude.toFloat())
            ?.apply()
    }

    private fun fetchLocationFromIp(): LocationInfo? {
        return try {
            val url = URL("http://ip-api.com/json/")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                if (json.optString("status") == "success") {
                    LocationInfo(
                        cityName = json.optString("city", "Київ"),
                        regionName = json.optString("regionName", "Київська область"),
                        latitude = json.optDouble("lat", 50.45),
                        longitude = json.optDouble("lon", 30.52)
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.d("LocationStore", "IP geolocation failed: ${e.message}")
            null
        }
    }

    private fun isInsideUkraine(lat: Double, lon: Double): Boolean {
        return lat in 43.5..53.0 && lon in 21.5..41.0
    }

    private fun matchNearestRegion(lat: Double, lon: Double): RegionEntry {
        var best = UKRRAINIAN_REGIONS.first()
        var bestDist = Double.MAX_VALUE
        for (region in UKRRAINIAN_REGIONS) {
            val d = haversine(lat, lon, region.latitude, region.longitude)
            if (d < bestDist) {
                bestDist = d
                best = region
            }
        }
        return best
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Resolves the device's current location by requesting a single fresh
     * location update from the GPS or network provider, bounded by an 8s
     * timeout. Returns null if permission is missing or no fix arrives in time.
     */
    private suspend fun requestDeviceLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.allProviders.contains(it) }
        if (providers.isEmpty()) return null

        // Request a single fresh location update, bounded by a timeout.
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var resolved = false

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!resolved) {
                        resolved = true
                        safeRemoveUpdates(lm, this)
                        if (!cont.isCompleted) cont.resumeWith(Result.success(location))
                    }
                }
            }

            val timeout = Runnable {
                if (!resolved) {
                    resolved = true
                    safeRemoveUpdates(lm, listener)
                    if (!cont.isCompleted) cont.cancel()
                }
            }
            handler.postDelayed(timeout, 8000L)

            for (provider in providers) {
                try {
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                }
            }

            cont.invokeOnCancellation {
                if (!resolved) {
                    resolved = true
                    handler.removeCallbacks(timeout)
                }
                safeRemoveUpdates(lm, listener)
            }
        }
    }

    private fun safeRemoveUpdates(lm: LocationManager, listener: LocationListener) {
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
    }
}
