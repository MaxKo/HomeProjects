package com.kerberos.radiorocksstreamingapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kerberos.radiorocksstreamingapp.data.LocationStore
import com.kerberos.radiorocksstreamingapp.data.StreamConstants
import com.kerberos.radiorocksstreamingapp.data.TrackMetadataStore
import com.kerberos.radiorocksstreamingapp.network.WeatherFetcher
import com.kerberos.radiorocksstreamingapp.service.PlaybackService
import com.kerberos.radiorocksstreamingapp.ui.CarRadioScreen
import com.kerberos.radiorocksstreamingapp.ui.RadioViewModel
import com.kerberos.radiorocksstreamingapp.ui.theme.RadioRocksStreamingAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val radioViewModel: RadioViewModel by viewModels()

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // GPS is now available — resolve the real region (and weather) right away.
            lifecycleScope.launch(Dispatchers.IO) {
                LocationStore.refreshLocation()
                val weather = WeatherFetcher.fetchWeatherSummary()
                if (!weather.isNullOrBlank()) {
                    TrackMetadataStore.publishWeather(weather)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        LocationStore.initialize(applicationContext)
        radioViewModel.initialize(applicationContext)

        // Location is detected by GPS, which is accurate to where the device
        // physically is — so it works on mobile data, where IP geolocation
        // returns the carrier's city (e.g. "Київ" for a user in "Черкаси").
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Already granted — refresh the region now.
            lifecycleScope.launch(Dispatchers.IO) {
                LocationStore.refreshLocation()
            }
        }

        // Auto-start streaming when launched in car / opened by user
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            action = StreamConstants.ACTION_PLAY
        }
        try {
            startForegroundService(serviceIntent)
        } catch (_: Exception) {
            startService(serviceIntent)
        }

        setContent {
            RadioRocksStreamingAppTheme {
                CarRadioScreen(viewModel = radioViewModel)
            }
        }
    }
}
