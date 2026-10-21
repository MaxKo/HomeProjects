package com.kerberos.radiorocksstreamingapp.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kerberos.radiorocksstreamingapp.data.StreamConstants
import com.kerberos.radiorocksstreamingapp.data.TrackMetadataStore
import com.kerberos.radiorocksstreamingapp.data.TrackTitles
import com.kerberos.radiorocksstreamingapp.network.LyricsFetcher
import com.kerberos.radiorocksstreamingapp.network.NetworkMonitor
import com.kerberos.radiorocksstreamingapp.service.PlaybackService
import com.kerberos.radiorocksstreamingapp.service.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadioUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReconnecting: Boolean = false,
    val isOffline: Boolean = false,
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = StreamConstants.MAX_RETRY_ATTEMPTS,
    val stationName: String = StreamConstants.STATION_NAME,
    val slogan: String = StreamConstants.STATION_SLOGAN,
    val currentTrack: String = TrackTitles.LIVE_PLACEHOLDER,
    /** Artist (group) name extracted from the current track; empty when placeholder. */
    val artist: String = "",
    val trackFromSite: Boolean = false,
    val artworkUrl: String? = null,
    val weatherSummary: String? = null,
    val locationName: String = "Київ",
    val alertLevel: com.kerberos.radiorocksstreamingapp.network.AlertLevel = com.kerberos.radiorocksstreamingapp.network.AlertLevel.NONE,
    val lyrics: String? = null,
    val lyricsVisible: Boolean = false,
    val errorMessage: String? = null,
    val isMuted: Boolean = false,
    val volume: Float = 1.0f
)

class RadioViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var networkMonitor: NetworkMonitor? = null

    fun initialize(context: Context) {
        if (networkMonitor == null) {
            networkMonitor = NetworkMonitor(context.applicationContext).apply {
                startMonitoring()
            }
            viewModelScope.launch {
                networkMonitor?.isOnline?.collect { isOnline ->
                    _uiState.update { it.copy(isOffline = !isOnline) }
                }
            }
            // Direct track titles from PlaybackService (ICY StreamTitle).
            viewModelScope.launch {
                TrackMetadataStore.currentTrack.collect { track ->
                    if (!track.isNullOrBlank()) {
                        android.util.Log.d("RadioViewModel", "STORE collector: track='$track' (updating UI currentTrack)")
                        val parsedArtist = if (track == TrackTitles.LIVE_PLACEHOLDER) "" else TrackTitles.parse(track)?.first ?: ""
                        _uiState.update { it.copy(currentTrack = track, artist = parsedArtist, artworkUrl = null) }
                        if (track == TrackTitles.LIVE_PLACEHOLDER) {
                            // No real song: drop the artwork/lyrics of the previous track.
                            android.util.Log.d("RadioViewModel", "STORE: placeholder title, clearing artwork/lyrics")
                            TrackMetadataStore.publishArtwork(null)
                            TrackMetadataStore.publishLyrics(null)
                            return@collect
                        }
                        // Fetch album art for the new track (cached per song)
                        viewModelScope.launch {
                            val art = com.kerberos.radiorocksstreamingapp.network
                                .ArtworkFetcher.fetchArtwork(track)
                            android.util.Log.d("RadioViewModel", "ARTWORK: $art")
                            TrackMetadataStore.publishArtwork(art)
                        }
                        // Fetch lyrics for the new track (cached per song)
                        viewModelScope.launch {
                            val parsed = TrackTitles.parse(track)
                            if (parsed != null) {
                                val (artist, title) = parsed
                                android.util.Log.d("RadioViewModel", "FETCH lyrics: artist='$artist' title='$title'")
                                val lyrics = LyricsFetcher.fetchLyrics(artist, title)
                                android.util.Log.d("RadioViewModel", "LYRICS: ${if (lyrics != null) "FOUND (len=${lyrics.length})" else "not found"}")
                                TrackMetadataStore.publishLyrics(lyrics)
                            } else {
                                android.util.Log.d("RadioViewModel", "FETCH lyrics: SKIPPED (could not parse track='$track')")
                            }
                        }
                    }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.artworkUrl.collect { art ->
                    _uiState.update { it.copy(artworkUrl = art) }
                }
            }
            viewModelScope.launch {
                com.kerberos.radiorocksstreamingapp.data.LocationStore.currentLocation.collect { loc ->
                    val displayName = if (loc.cityName.isNotBlank()) loc.cityName else loc.regionName
                    _uiState.update { it.copy(locationName = displayName) }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.weatherSummary.collect { weather ->
                    _uiState.update { it.copy(weatherSummary = weather) }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.alertLevel.collect { level ->
                    _uiState.update { it.copy(alertLevel = level) }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.fromSite.collect { fromSite ->
                    _uiState.update { it.copy(trackFromSite = fromSite) }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.lyricsVisible.collect { visible ->
                    _uiState.update { it.copy(lyricsVisible = visible) }
                }
            }
            viewModelScope.launch {
                TrackMetadataStore.lyrics.collect { lyrics ->
                    _uiState.update { it.copy(lyrics = lyrics) }
                }
            }
            // SITE fallback that works even when the service isn't running
            // (paused / before first play): polls radioroks.ua/playlist.
            // Yields to LIVE titles — only applies when store is empty.
            viewModelScope.launch {
                com.kerberos.radiorocksstreamingapp.network.RadioMetadataFetcher
                    .pollTrackMetadata(
                        streamUrl = { StreamConstants.PRIMARY_STREAM_URL },
                        pollIntervalMs = 15_000L
                    )
                    .collect { result ->
                        android.util.Log.d("RadioViewModel", "POLL result: track='${result.track}' fromSite=${result.fromSite}")
                        if (TrackTitles.isNonSong(result.track)) {
                            // DJ talk / station ID: show the placeholder, but let a
                            // later real title (LIVE or SITE) replace it again.
                            android.util.Log.d("RadioViewModel", "POLL: non-song title (raw='${result.track}'), publishing 'Live Stream'")
                            TrackMetadataStore.publishPlaceholder()
                        } else if (result.fromSite) {
                            TrackMetadataStore.publishSite(result.track)
                            // If service hasn't published LIVE yet, show SITE now
                            val cur = _uiState.value.currentTrack
                            if (cur == TrackTitles.LIVE_PLACEHOLDER || cur.isBlank()) {
                                android.util.Log.d("RadioViewModel", "POLL: showing SITE fallback as UI currentTrack (no LIVE yet)")
                                val siteArtist = TrackTitles.parse(result.track)?.first ?: ""
                                _uiState.update {
                                    it.copy(currentTrack = result.track, artist = siteArtist, trackFromSite = true)
                                }
                            }
                        } else {
                            TrackMetadataStore.publish(result.track)
                        }
                    }
            }
        }

        if (controllerFuture == null) {
            val sessionToken = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java)
            )
            controllerFuture = MediaController.Builder(context.applicationContext, sessionToken).buildAsync()
            controllerFuture?.addListener(
                {
                    try {
                        val controller = controllerFuture?.get()
                        mediaController = controller
                        controller?.addListener(PlayerListener())
                        updateFromController(controller)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(errorMessage = "Service connection error") }
                    }
                },
                MoreExecutors.directExecutor()
            )
        }
    }

    private fun updateFromController(controller: MediaController?) {
        controller ?: return
        val isPlaying = controller.isPlaying
        val isBuffering = controller.playbackState == Player.STATE_BUFFERING

        _uiState.update { current ->
            current.copy(
                isPlaying = isPlaying,
                isBuffering = isBuffering
            )
        }
        // NOTE: currentTrack is NOT taken from the controller here.
        // The player metadata of a live stream shows the station name
        // ("Radio ROKS" / "Live Stream"), not the current song.
        // TrackMetadataStore is the single source of truth for the title.
    }

    fun togglePlayPause(context: Context) {
        val controller = mediaController
        if (controller != null) {
            if (controller.isPlaying) {
                controller.pause()
            } else {
                val intent = Intent(context, PlaybackService::class.java).apply {
                    action = StreamConstants.ACTION_PLAY
                }
                context.startForegroundService(intent)
                controller.prepare()
                controller.play()
            }
        } else {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = StreamConstants.ACTION_PLAY
            }
            context.startForegroundService(intent)
        }
    }

    fun retry(context: Context) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = StreamConstants.ACTION_RETRY
        }
        context.startForegroundService(intent)
    }

    fun toggleMute() {
        mediaController?.let { controller ->
            val currentVol = controller.volume
            if (currentVol > 0f) {
                controller.volume = 0f
                _uiState.update { it.copy(isMuted = true, volume = 0f) }
            } else {
                controller.volume = 1f
                _uiState.update { it.copy(isMuted = false, volume = 1f) }
            }
        }
    }

    fun toggleLyrics() {
        TrackMetadataStore.toggleLyricsVisible()
    }

    fun setRegion(regionName: String) {
        com.kerberos.radiorocksstreamingapp.data.LocationStore.setManualRegion(regionName)
        // Refresh weather for the new region
        viewModelScope.launch {
            val weather = com.kerberos.radiorocksstreamingapp.network.WeatherFetcher.fetchWeatherSummary()
            _uiState.update { it.copy(weatherSummary = weather) }
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        networkMonitor?.stopMonitoring()
        super.onCleared()
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            _uiState.update { it.copy(isBuffering = isBuffering) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            android.util.Log.d(
                "RadioViewModel",
                "MEDIA meta: title=${mediaMetadata.title} artist=${mediaMetadata.artist} displayTitle=${mediaMetadata.displayTitle} " +
                    "(currentTrack sourced from TrackMetadataStore to avoid live-stream race condition)"
            )
            // NOTE: currentTrack is the single source of truth from TrackMetadataStore (ICY + polling).
            // Player mediaMetadata for a live stream often shows the station name, not the track title.
            // Updating currentTrack here would overwrite the store's LIVE title and cause flicker/wrong title.
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            android.util.Log.d(
                "RadioViewModel",
                "PLAYLIST meta: displayTitle=${mediaMetadata.displayTitle} title=${mediaMetadata.title} artist=${mediaMetadata.artist} " +
                    "(currentTrack sourced from TrackMetadataStore to avoid live-stream race condition)"
            )
            // NOTE: currentTrack is the single source of truth from TrackMetadataStore (ICY + polling).
            // Player playlistMetadata is used for Android Auto / notification / lock screen, not UI title.
        }
    }
}
