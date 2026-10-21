package com.kerberos.radiorocksstreamingapp.service

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.LibraryResult
import com.kerberos.radiorocksstreamingapp.data.StreamConstants
import com.kerberos.radiorocksstreamingapp.data.TrackMetadataStore
import com.kerberos.radiorocksstreamingapp.data.TrackTitles
import com.kerberos.radiorocksstreamingapp.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

sealed class StreamState {
    object Idle : StreamState()
    object Buffering : StreamState()
    object Playing : StreamState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : StreamState()
    object Offline : StreamState()
    data class Error(val message: String) : StreamState()
}

class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var networkMonitor: NetworkMonitor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reconnectJob: Job? = null
    private var metadataJob: Job? = null
    private var weatherJob: Job? = null
    private var alertJob: Job? = null
    private var retryCount = 0

    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private var currentStreamUrl = StreamConstants.PRIMARY_STREAM_URL

    private val defaultLogoUri by lazy {
        Uri.parse("android.resource://$packageName/${com.kerberos.radiorocksstreamingapp.R.drawable.roks_logo}")
    }

    /** Latest known artwork URL (iTunes), applied to session metadata. */
    @Volatile private var currentArtworkUrl: String? = null
    @Volatile private var currentWeatherSummary: String? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        com.kerberos.radiorocksstreamingapp.data.LocationStore.initialize(this)
        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()

        initializePlayer()
        initializeMediaSession()
        observeNetworkChanges()
        startMetadataLoop()
        startWeatherLoop()
        startAlertLoop()
    }

    private fun startAlertLoop() {
        alertJob?.cancel()
        alertJob = serviceScope.launch {
            com.kerberos.radiorocksstreamingapp.network.AirAlertFetcher
                .pollAlertStatus(pollIntervalMs = 30_000L)
                .collect { level ->
                    val oldLevel = TrackMetadataStore.alertLevel.value
                    if (oldLevel != level) {
                        TrackMetadataStore.publishAlertLevel(level)
                        android.util.Log.d("PlaybackService", "Alert level changed to $level")
                        val currentTrack = TrackMetadataStore.currentTrack.value
                        if (!currentTrack.isNullOrBlank() && currentTrack != TrackTitles.LIVE_PLACEHOLDER) {
                            val parsed = TrackTitles.parse(currentTrack)
                            if (parsed != null) {
                                pushSessionTrack(parsed.first, parsed.second, fromSite = false)
                            } else {
                                pushSessionPlaceholder()
                            }
                        } else {
                            pushSessionPlaceholder()
                        }
                    }
                }
        }
    }

    private fun startWeatherLoop() {
        weatherJob?.cancel()
        weatherJob = serviceScope.launch {
            while (true) {
                val weather = com.kerberos.radiorocksstreamingapp.network.WeatherFetcher.fetchWeatherSummary()
                if (!weather.isNullOrBlank()) {
                    currentWeatherSummary = weather
                    TrackMetadataStore.publishWeather(weather)
                    android.util.Log.d("PlaybackService", "Weather updated: $weather")
                }
                delay(15 * 60 * 1000L) // Update weather every 15 minutes
            }
        }
    }

    /**
     * Dual-source loop: ICY StreamTitle first, radioroks.ua/playlist
     * fallback. Runs for the service lifetime so the card fills in
     * even when paused / ICY misses a track change.
     */
    private fun startMetadataLoop() {
        metadataJob?.cancel()
        metadataJob = serviceScope.launch {
            com.kerberos.radiorocksstreamingapp.network.RadioMetadataFetcher
                .pollTrackMetadata(streamUrl = { currentStreamUrl }, pollIntervalMs = 15_000L)
                .collect { result ->
                    android.util.Log.d(
                        "PlaybackService",
                        "LOOP track: " + result.track + " fromSite=" + result.fromSite
                    )
                    if (result.fromSite) handleSiteTitle(result.track)
                    else handleLiveTitle(result.track)
                }
        }
    }

    /**
     * SITE-sourced title (radioroks.ua/playlist fallback).
     *
     * The site also reports DJ talk / station IDs (e.g. "Radio ROKS - Рок. Тільки рок!"),
     * which must show as the placeholder instead of a stale song title.
     * The title is pushed to the media session too, so the notification and the
     * lock screen follow along.
     */
    private fun handleSiteTitle(rawTitle: String) {
        if (TrackTitles.isNonSong(rawTitle)) {
            android.util.Log.d("PlaybackService", "SITE: non-song title (raw='$rawTitle'), publishing 'Live Stream'")
            TrackMetadataStore.publishPlaceholder()
            pushSessionPlaceholder()
            return
        }
        val parsed = TrackTitles.parse(rawTitle) ?: return
        val (artist, title) = parsed
        android.util.Log.d("PlaybackService", "SITE track: $artist - $title fromSite=true")
        TrackMetadataStore.publishSite("$artist - $title")
        pushSessionTrack(artist, title, fromSite = true)
    }

    /** Service-scope LIVE title: same parse/publish as the player listener. */
    private fun handleLiveTitle(rawTitle: String) {
        val parsed = TrackTitles.parse(rawTitle, decodeUrl = true) ?: return
        val (artist, title) = parsed
        android.util.Log.d("PlaybackService", "PUBLISH track: $artist - $title fromSite=false")
        TrackMetadataStore.publish("$artist - $title", fromSite = false)
        pushSessionTrack(artist, title, fromSite = false)
    }

    /**
     * Pushes the "Live Stream" placeholder into the media session so the
     * notification / lock screen / car screen stop showing the previous song
     * while the station is between songs (DJ talk, station ID, ...).
     */
    private fun pushSessionPlaceholder() {
        val p = player ?: return
        val album = currentWeatherSummary ?: StreamConstants.STATION_SLOGAN
        val meta = MediaMetadata.Builder()
            .setTitle(TrackTitles.LIVE_PLACEHOLDER)
            .setArtist(StreamConstants.STATION_NAME)
            .setAlbumTitle(album)
            .setDisplayTitle(TrackTitles.LIVE_PLACEHOLDER)
            .setSubtitle(StreamConstants.STATION_SLOGAN)
            .setArtworkUri(defaultLogoUri)
            .setIsPlayable(true)
            .build()
        serviceScope.launch(Dispatchers.Main) {
            try {
                p.replaceMediaItem(
                    0,
                    MediaItem.Builder()
                        .setUri(Uri.parse(currentStreamUrl))
                        .setMediaId("radio_rocks_live")
                        .setMediaMetadata(meta)
                        .build()
                )
            } catch (e: Exception) {
                android.util.Log.d("PlaybackService", "replaceMediaItem (placeholder) failed: " + e.message)
                p.playlistMetadata = meta
            }
        }
    }

    /**
     * Pushes title + artwork into the media session via replaceMediaItem.
     * This is what Android Auto / lock screen / notification actually read —
     * more reliable than playlistMetadata for live streams.
     *
     * replaceMediaItem updates the current MediaItem in place when the URI matches
     * (ProgressiveMediaSource.canUpdateMediaItem returns true), so playback is NOT
     * interrupted. Only the metadata/artwork is refreshed.
     */
    private fun pushSessionTrack(artist: String, title: String, fromSite: Boolean) {
        val p = player ?: return
        // Fetch artwork async (cached per song), then push with art
        serviceScope.launch(Dispatchers.IO) {
            val art = try {
                val track = "$artist - $title"
                val url = com.kerberos.radiorocksstreamingapp.network
                    .ArtworkFetcher.fetchArtwork(track)
                currentArtworkUrl = url
                TrackMetadataStore.publishArtwork(url)
                url
            } catch (_: Exception) { currentArtworkUrl }

            val album = currentWeatherSummary ?: StreamConstants.STATION_SLOGAN
            val artUri = if (!art.isNullOrBlank()) Uri.parse(art) else defaultLogoUri
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDisplayTitle("$artist - $title")
                .setSubtitle(StreamConstants.STATION_SLOGAN)
                .setArtworkUri(artUri)
                .setIsPlayable(true)
            if (!art.isNullOrBlank()) {
                metaBuilder.setArtworkUri(Uri.parse(art))
            }
            val newItem = MediaItem.Builder()
                .setUri(Uri.parse(currentStreamUrl))
                .setMediaId("radio_rocks_live")
                .setMediaMetadata(metaBuilder.build())
                .build()
            serviceScope.launch(Dispatchers.Main) {
                try {
                    // replaceMediaItem updates metadata in-place; no prepare/play/seek needed.
                    p.replaceMediaItem(0, newItem)
                } catch (e: Exception) {
                    android.util.Log.d("PlaybackService", "replaceMediaItem failed: " + e.message)
                    // Fallback: playlistMetadata still updates some controllers
                    p.playlistMetadata = newItem.mediaMetadata
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                StreamConstants.MIN_BUFFER_MS,
                StreamConstants.MAX_BUFFER_MS,
                StreamConstants.BUFFER_FOR_PLAYBACK_MS,
                StreamConstants.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                addListener(PlayerListener())
                setMediaItem(createMediaItem(currentStreamUrl))
                prepare()
            }
    }

    @OptIn(UnstableApi::class)
    private fun initializeMediaSession() {
        player?.let { p ->
            mediaSession = MediaLibrarySession.Builder(this, p, SessionCallback())
                .setId("RadioRocksMediaSession")
                .setBitmapLoader(ArtworkBitmapLoader(this))
                .build()
        }
    }

    /**
     * Resolves artwork for the media session (car screen, lock screen,
     * notification). Media3 queries this when the session needs a bitmap —
     * we hand it the iTunes URL so the car can download & display it.
     */
    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = createMediaItem(currentStreamUrl).apply {
                mediaMetadata = mediaMetadata.buildUpon()
                    .setTitle(StreamConstants.STATION_NAME)
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .build()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(root, null)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            // Single-item library: the live stream is the only child.
            val children = if (parentId == "radio_rocks_live") {
                ImmutableList.of(createMediaItem(currentStreamUrl))
            } else {
                ImmutableList.of()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(children, null)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val item = player?.currentMediaItem ?: createMediaItem(currentStreamUrl)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
            )
        }
    }

    private fun createMediaItem(url: String): MediaItem {
        val album = currentWeatherSummary ?: StreamConstants.STATION_SLOGAN
        val metadata = MediaMetadata.Builder()
            .setTitle(StreamConstants.STATION_NAME)
            .setArtist(TrackTitles.LIVE_PLACEHOLDER)
            .setAlbumTitle(album)
            .setSubtitle(StreamConstants.STATION_SLOGAN)
            .setArtworkUri(defaultLogoUri)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId("radio_rocks_live")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun observeNetworkChanges() {
        serviceScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline) {
                    if (_streamState.value is StreamState.Offline ||
                        _streamState.value is StreamState.Error ||
                        reconnectJob?.isActive == true
                    ) {
                        retryStream(resetCount = true)
                    }
                } else {
                    _streamState.value = StreamState.Offline
                }
            }
        }
    }

    fun retryStream(resetCount: Boolean = false) {
        if (resetCount) {
            retryCount = 0
        }
        reconnectJob?.cancel()

        if (!networkMonitor.isOnline.value) {
            _streamState.value = StreamState.Offline
            return
        }

        reconnectJob = serviceScope.launch {
            retryCount++
            _streamState.value = StreamState.Reconnecting(retryCount, StreamConstants.MAX_RETRY_ATTEMPTS)

            // Switch to fallback stream url on alternate retries if needed
            currentStreamUrl = if (retryCount % 2 == 0) {
                StreamConstants.FALLBACK_STREAM_URL
            } else {
                StreamConstants.PRIMARY_STREAM_URL
            }

            player?.let { p ->
                p.stop()
                p.clearMediaItems()
                p.setMediaItem(createMediaItem(currentStreamUrl))
                p.prepare()
                p.playWhenReady = true
            }

            val backoffDelay = min(
                StreamConstants.INITIAL_RETRY_DELAY_MS * (1L shl (retryCount - 1)),
                StreamConstants.MAX_RETRY_DELAY_MS
            )
            delay(backoffDelay)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            StreamConstants.ACTION_PLAY -> {
                player?.let { p ->
                    if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                        p.prepare()
                    }
                    p.playWhenReady = true
                }
            }
            StreamConstants.ACTION_PAUSE -> {
                player?.pause()
            }
            StreamConstants.ACTION_TOGGLE -> {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
            StreamConstants.ACTION_RETRY -> {
                retryStream(resetCount = true)
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onDestroy() {
        serviceScope.launch {
            networkMonitor.stopMonitoring()
        }
        reconnectJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (_streamState.value !is StreamState.Reconnecting) {
                        _streamState.value = StreamState.Buffering
                    }
                }
                Player.STATE_READY -> {
                    retryCount = 0
                    reconnectJob?.cancel()
                    if (player?.playWhenReady == true) {
                        _streamState.value = StreamState.Playing
                    } else {
                        _streamState.value = StreamState.Idle
                    }
                }
                Player.STATE_ENDED -> {
                    _streamState.value = StreamState.Idle
                }
                Player.STATE_IDLE -> {
                    if (_streamState.value !is StreamState.Error && _streamState.value !is StreamState.Offline) {
                        _streamState.value = StreamState.Idle
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                retryCount = 0
                reconnectJob?.cancel()
                _streamState.value = StreamState.Playing
            } else if (player?.playbackState == Player.STATE_READY) {
                _streamState.value = StreamState.Idle
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!networkMonitor.isOnline.value) {
                _streamState.value = StreamState.Offline
            } else if (retryCount < StreamConstants.MAX_RETRY_ATTEMPTS) {
                retryStream(resetCount = false)
            } else {
                _streamState.value = StreamState.Error("Stream unavailable: ${error.localizedMessage}")
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            super.onMediaMetadataChanged(mediaMetadata)
            android.util.Log.d("PlaybackService", "META title='${mediaMetadata.title}' artist='${mediaMetadata.artist}' displayTitle='${mediaMetadata.displayTitle}'")
            val rawTitle = mediaMetadata.title?.toString()
                ?: mediaMetadata.displayTitle?.toString()
                ?: mediaMetadata.artist?.toString()
                ?: return

            if (rawTitle.isNotBlank() && rawTitle != StreamConstants.STATION_NAME) {
                android.util.Log.d("PlaybackService", "META: publishing LIVE title from mediaMetadata: $rawTitle")
                publishLiveTitle(rawTitle)
            }
        }

        // ICY in-stream metadata: ExoPlayer delivers StreamTitle here as
        // IcyInfo ("ICY: title="Artist - Title""). Parse and publish it.
        override fun onMetadata(metadata: Metadata) {
            super.onMetadata(metadata)
            for (i in 0 until metadata.length()) {
                val entry: Metadata.Entry = metadata[i]
                android.util.Log.d("PlaybackService", "ICY entry: $entry")
                val raw = entry.toString()
                // Format: ICY: title="Artist - Title", url="...", rawMetadata.length="N"
                val titleMatch = Regex("title=\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
                if (!titleMatch.isNullOrBlank() && !titleMatch.contains("radioroks", ignoreCase = true) &&
                    !titleMatch.contains("Radio_ROKS")
                ) {
                    android.util.Log.d("PlaybackService", "ICY: publishing LIVE title: $titleMatch")
                    publishLiveTitle(titleMatch)
                }
            }
        }

        /** LIVE title (ICY): decodes, splits Artist - Title, publishes. */
        private fun publishLiveTitle(rawTitle: String) {
            publishTrackTitle(rawTitle, fromSite = false)
        }

        private fun publishTrackTitle(rawTitle: String, fromSite: Boolean = false) {
            // If the stream is sending a non-song title (DJ talk, station ID, etc.),
            // publish "Live Stream" instead of letting a stale song title linger.
            if (TrackTitles.isNonSong(rawTitle)) {
                android.util.Log.d("PlaybackService", "PUBLISH: non-song title (raw='$rawTitle'), publishing 'Live Stream'")
                TrackMetadataStore.publishPlaceholder()
                player?.playlistMetadata = MediaMetadata.Builder()
                    .setTitle(TrackTitles.LIVE_PLACEHOLDER)
                    .setArtist(StreamConstants.STATION_NAME)
                    .setDisplayTitle(TrackTitles.LIVE_PLACEHOLDER)
                    .setSubtitle(StreamConstants.STATION_SLOGAN)
                    .setIsPlayable(true)
                    .build()
                return
            }
            val parsed = TrackTitles.parse(rawTitle, decodeUrl = true) ?: run {
                android.util.Log.d("PlaybackService", "PUBLISH: unparseable title (raw='$rawTitle'), publishing 'Live Stream'")
                TrackMetadataStore.publishPlaceholder()
                player?.playlistMetadata = MediaMetadata.Builder()
                    .setTitle(TrackTitles.LIVE_PLACEHOLDER)
                    .setArtist(StreamConstants.STATION_NAME)
                    .setDisplayTitle(TrackTitles.LIVE_PLACEHOLDER)
                    .setSubtitle(StreamConstants.STATION_SLOGAN)
                    .setIsPlayable(true)
                    .build()
                return
            }
            val (artist, title) = parsed
            android.util.Log.d("PlaybackService", "PUBLISH track: $artist - $title fromSite=$fromSite (raw='$rawTitle')")
            TrackMetadataStore.publish("$artist - $title", fromSite = fromSite)

            player?.playlistMetadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setDisplayTitle("$artist - $title")
                .setSubtitle(StreamConstants.STATION_SLOGAN)
                .setIsPlayable(true)
                .build()
        }
    }
}
