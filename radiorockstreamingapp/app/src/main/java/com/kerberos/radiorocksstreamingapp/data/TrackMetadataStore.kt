package com.kerberos.radiorocksstreamingapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process track title bus. PlaybackService publishes ICY StreamTitle here;
 * RadioViewModel collects it. Direct — no MediaSession round-trip needed.
 * fromSite=true means the title came from radioroks.ua/playlist fallback.
 */
object TrackMetadataStore {
    private val _currentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = _currentTrack.asStateFlow()

    private val _fromSite = MutableStateFlow(false)
    val fromSite: StateFlow<Boolean> = _fromSite.asStateFlow()

    private val _artworkUrl = MutableStateFlow<String?>(null)
    val artworkUrl: StateFlow<String?> = _artworkUrl.asStateFlow()

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()

    private val _lyricsVisible = MutableStateFlow(false)
    val lyricsVisible: StateFlow<Boolean> = _lyricsVisible.asStateFlow()

    private val _weatherSummary = MutableStateFlow<String?>(null)
    val weatherSummary: StateFlow<String?> = _weatherSummary.asStateFlow()

    private val _alertLevel = MutableStateFlow(com.kerberos.radiorocksstreamingapp.network.AlertLevel.NONE)
    val alertLevel: StateFlow<com.kerberos.radiorocksstreamingapp.network.AlertLevel> = _alertLevel.asStateFlow()

    fun publishWeather(weather: String?) {
        _weatherSummary.value = weather
    }

    fun publishAlertLevel(level: com.kerberos.radiorocksstreamingapp.network.AlertLevel) {
        _alertLevel.value = level
    }

    fun toggleLyricsVisible() {
        _lyricsVisible.value = !_lyricsVisible.value
    }

    fun publishLyrics(lyrics: String?) {
        _lyrics.value = lyrics
    }

    fun publish(track: String, fromSite: Boolean = false) {
        if (track.isBlank()) return
        val current = _currentTrack.value
        if (current == null) {
            _currentTrack.value = track
            _fromSite.value = fromSite
            return
        }
        if (current == track) return
        // The placeholder is not a real title: it must never block a real one.
        if (current == TrackTitles.LIVE_PLACEHOLDER) {
            _currentTrack.value = track
            _fromSite.value = fromSite
            return
        }
        // LIVE (ICY) always wins over the SITE fallback.
        if (!fromSite) {
            _currentTrack.value = track
            _fromSite.value = fromSite
        } else if (_fromSite.value) {
            // Already showing a SITE title: let the site refresh it.
            _currentTrack.value = track
        }
    }

    /** SITE result applies only when no LIVE title has been seen. */
    fun publishSite(track: String) = publish(track, fromSite = true)

    /**
     * Publishes the "Live Stream" placeholder (DJ talk, station ID, ...).
     *
     * Unlike [publish], the placeholder ALWAYS overrides the current title:
     * a stale song title must not linger while nothing is playing.
     * A later real title replaces the placeholder again (see [publish]).
     *
     * The placeholder is always attributed to the LIVE source so the UI
     * badge stays deterministic (green "LIVE") for non-song content.
     */
    fun publishPlaceholder() {
        if (_currentTrack.value == TrackTitles.LIVE_PLACEHOLDER) return
        _currentTrack.value = TrackTitles.LIVE_PLACEHOLDER
        _fromSite.value = false
    }

    fun publishArtwork(url: String?) {
        _artworkUrl.value = url
    }

    fun clear() {
        _currentTrack.value = null
        _fromSite.value = false
        _artworkUrl.value = null
        _lyrics.value = null
        _lyricsVisible.value = false
    }
}
