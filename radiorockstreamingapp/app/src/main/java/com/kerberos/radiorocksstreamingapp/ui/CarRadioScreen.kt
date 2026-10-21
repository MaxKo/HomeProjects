package com.kerberos.radiorocksstreamingapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.kerberos.radiorocksstreamingapp.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CarDarkBg = Color(0xFF0F0F12)
private val CarCardBg = Color(0xFF1B1B22)
private val RoksRed = Color(0xFFE50914)
private val RoksGold = Color(0xFFFFB800)
private val StatusLiveGreen = Color(0xFF00E676)
private val StatusOfflineRed = Color(0xFFFF3D00)

@Composable
fun CarRadioScreen(
    viewModel: RadioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showRegionPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = CarDarkBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header & Status Bar
            TopBarSection(uiState = uiState, onLocationClick = { showRegionPicker = true })

            // Station Branding & Metadata
            StationInfoSection(uiState = uiState)

            // Lyrics Section — toggled by car Next/Prev buttons
            if (uiState.lyricsVisible && uiState.lyrics != null) {
                LyricsSection(lyrics = uiState.lyrics!!)
            }

            // Audio Waveform Visualizer
            AudioVisualizerSection(isPlaying = uiState.isPlaying)

            // Car-optimized Main Control Panel
            ControlPanelSection(
                uiState = uiState,
                onPlayPauseToggle = { viewModel.togglePlayPause(context) },
                onRetry = { viewModel.retry(context) },
                onToggleMute = { viewModel.toggleMute() },
                onToggleLyrics = { viewModel.toggleLyrics() }
            )

            // App Version Footer — always reflects deployed version
            // (reads from BuildConfig + PackageInfo, no more hardcoding)
            VersionFooter()
        }

        // Region Picker Dialog
        if (showRegionPicker) {
            RegionPickerDialog(
                currentRegion = uiState.locationName,
                onDismiss = { showRegionPicker = false },
                onSelect = { region ->
                    viewModel.setRegion(region)
                    showRegionPicker = false
                }
            )
        }
    }
}

@Composable
private fun TopBarSection(uiState: RadioUiState, onLocationClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bluetooth Car Auto-Connect indicator & Active Region
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF282834))
                .clickable(onClick = onLocationClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothConnected,
                contentDescription = "Bluetooth Car Ready",
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "📍 ${uiState.locationName}",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Live / Status Badge
        StatusBadge(uiState = uiState)
    }
}

@Composable
private fun StatusBadge(uiState: RadioUiState) {
    val (badgeBg, badgeText, badgeColor) = when {
        uiState.isOffline -> Triple(
            Color(0xFF331414),
            "OFFLINE",
            StatusOfflineRed
        )
        uiState.isReconnecting -> Triple(
            Color(0xFF332B14),
            "RECONNECTING...",
            RoksGold
        )
        uiState.isBuffering -> Triple(
            Color(0xFF332B14),
            "BUFFERING...",
            RoksGold
        )
        uiState.isPlaying -> Triple(
            Color(0xFF143320),
            "● LIVE STREAM",
            StatusLiveGreen
        )
        else -> Triple(
            Color(0xFF282834),
            "READY",
            Color.LightGray
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(badgeBg)
            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        if (uiState.isBuffering || uiState.isReconnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = badgeColor,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (uiState.isOffline) {
            Icon(
                imageVector = Icons.Default.SignalCellularConnectedNoInternet0Bar,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = badgeText,
            color = badgeColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun StationInfoSection(uiState: RadioUiState) {
    val alertLevel = uiState.alertLevel
    val (artBorderColor, artBorderWidth) = when (alertLevel) {
        com.kerberos.radiorocksstreamingapp.network.AlertLevel.RED -> Color.Red to 5.dp
        com.kerberos.radiorocksstreamingapp.network.AlertLevel.YELLOW -> Color.Yellow to 5.dp
        com.kerberos.radiorocksstreamingapp.network.AlertLevel.NONE -> RoksGold.copy(alpha = 0.6f) to 2.dp
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CarCardBg)
            .padding(24.dp)
    ) {
        // Air Raid Alert Warning Banner (if active)
        if (alertLevel != com.kerberos.radiorocksstreamingapp.network.AlertLevel.NONE) {
            val alertText = if (alertLevel == com.kerberos.radiorocksstreamingapp.network.AlertLevel.RED) "🚨 ПОВІТРЯНА ТРИВОГА" else "⚠️ ЗАГРОЗА БПЛА"
            val alertBg = if (alertLevel == com.kerberos.radiorocksstreamingapp.network.AlertLevel.RED) Color(0xFF420000) else Color(0xFF423800)
            val alertColor = if (alertLevel == com.kerberos.radiorocksstreamingapp.network.AlertLevel.RED) Color.Red else Color.Yellow

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(alertBg)
                    .border(1.dp, alertColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = alertText,
                    color = alertColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // HERO: large album art (iTunes) or station logo fallback with dynamic Alert Border
        if (uiState.artworkUrl != null) {
            AsyncImage(
                model = uiState.artworkUrl,
                contentDescription = "Album art",
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(artBorderWidth, artBorderColor, RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.roks_logo),
                contentDescription = "Radio ROKS logo",
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(artBorderWidth, artBorderColor, RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // HERO: big song title
        Text(
            text = uiState.currentTrack,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Source badge: LIVE = ICY in-stream, SITE = radioroks.ua fallback
        val badgeColor = if (uiState.trackFromSite) Color(0xFF40C4FF) else StatusLiveGreen
        val badgeLabel = if (uiState.trackFromSite) "SITE" else "LIVE"
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badgeColor.copy(alpha = 0.15f))
                .border(
                    1.dp,
                    badgeColor.copy(alpha = 0.5f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeLabel,
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Compact station row: small logo + name + slogan
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(id = R.drawable.roks_logo),
                contentDescription = "Radio ROKS logo",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = uiState.stationName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = uiState.slogan,
                    color = RoksGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (!uiState.weatherSummary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF252530))
                    .border(1.dp, RoksGold.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = uiState.weatherSummary,
                    color = Color(0xFFFFE082),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AudioVisualizerSection(isPlaying: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "visualizer")

    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar1"
    )
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar2"
    )
    val scale3 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bar3"
    )

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        val bars = listOf(scale1, scale2, scale3, scale1, scale2, scale3, scale1)
        bars.forEach { scale ->
            val barHeight = if (isPlaying) (40 * scale).dp else 8.dp
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(8.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isPlaying) RoksRed else Color.DarkGray)
            )
        }
    }
}

@Composable
private fun ControlPanelSection(
    uiState: RadioUiState,
    onPlayPauseToggle: () -> Unit,
    onRetry: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLyrics: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mute / Unmute Button
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF282834))
        ) {
            Icon(
                imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Mute Toggle",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Lyrics Toggle — shows/hides the lyrics panel below
        IconButton(
            onClick = onToggleLyrics,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (uiState.lyricsVisible) Color(0xFF282834) else Color(0xFF1B1B22))
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = if (uiState.lyricsVisible) "Hide Lyrics" else "Show Lyrics",
                tint = if (uiState.lyricsVisible) RoksGold else Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // Main Car Play / Pause Toggle Button (100dp x 100dp touch area)
        Button(
            onClick = onPlayPauseToggle,
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isPlaying) Color(0xFF282834) else RoksRed
            )
        ) {
            Icon(
                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }

        // Reconnect / Retry Button
        IconButton(
            onClick = onRetry,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF282834))
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry Connection",
                tint = if (uiState.isReconnecting) RoksGold else Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

    @Composable
    private fun LyricsSection(lyrics: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(CarCardBg, RoundedCornerShape(12.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Lyrics",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = RoksGold
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = lyrics,
                style = TextStyle(
                    fontSize = 14.sp,
                    color = Color.White
                ),
                textAlign = TextAlign.Center,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis
            )
        }
    }


@Composable
private fun VersionFooter() {
    val context = LocalContext.current
    val versionText = remember {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                info.versionCode.toString()
            }
            "v${info.versionName} (Build $code)"
        } catch (_: Exception) {
            "v${com.kerberos.radiorocksstreamingapp.BuildConfig.VERSION_NAME} " +
                "(Build ${com.kerberos.radiorocksstreamingapp.BuildConfig.VERSION_CODE})"
        }
    }
    Text(
        text = versionText,
        color = Color.DarkGray,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun RegionPickerDialog(
    currentRegion: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val regions = com.kerberos.radiorocksstreamingapp.data.LocationStore.UKRRAINIAN_REGIONS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarCardBg,
        titleContentColor = Color.White,
        title = {
            Text(
                text = "📍 Select your region",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                regions.forEach { region ->
                    val isSelected = region.name.equals(currentRegion, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color(0xFF3A3A4A)
                                else Color.Transparent
                            )
                            .clickable { onSelect(region.name) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "✓ ${region.name}" else "  ${region.name}",
                            color = if (isSelected) RoksGold else Color.LightGray,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282834))
            ) {
                Text("Close", color = Color.White)
            }
        }
    )
}
