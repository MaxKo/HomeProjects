package com.kerberos.radiorocksstreamingapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the track-title pipeline.
 *
 * Covers the two regressions that were fixed:
 *  1. A DJ/station announcement must be shown as "Live Stream" (placeholder).
 *  2. A real song that arrives AFTER the placeholder must replace it
 *     (the placeholder must never block a real title).
 */
class TrackTitlesTest {

    @Before
    fun setUp() {
        TrackMetadataStore.clear()
    }

    // --- TrackTitles.isNonSong -------------------------------------------------

    @Test
    fun stationAnnouncementIsNonSong() {
        assertTrue(TrackTitles.isNonSong("Radio ROKS - Рок. Тільки рок!"))
        assertTrue(TrackTitles.isNonSong("Radio ROKS"))
        assertTrue(TrackTitles.isNonSong("radioroks.ua"))
        assertTrue(TrackTitles.isNonSong("Radio_ROKS"))
        assertTrue(TrackTitles.isNonSong("DJ Megawhite"))
        assertTrue(TrackTitles.isNonSong("LIVE"))
        assertTrue(TrackTitles.isNonSong("Live Stream"))
        assertTrue(TrackTitles.isNonSong("On Air"))
        assertTrue(TrackTitles.isNonSong("Прямий ефір"))
        assertTrue(TrackTitles.isNonSong("   "))
    }

    @Test
    fun realSongIsNotNonSong() {
        assertFalse(TrackTitles.isNonSong("Megawhite - Мій брате"))
        assertFalse(TrackTitles.isNonSong("Аквариум - Мой друг Кит"))
        // "Live" must not swallow a real song title.
        assertFalse(TrackTitles.isNonSong("Take That - Live Here Now"))
        assertFalse(TrackTitles.isNonSong("Eagles - Live And Let Die"))
    }

    // --- TrackTitles.parse ----------------------------------------------------

    @Test
    fun parseSplitsArtistAndTitle() {
        val (artist, title) = TrackTitles.parse("Megawhite - Мій брате")!!
        assertEquals("Megawhite", artist)
        assertEquals("Мій брате", title)
    }

    @Test
    fun parseWithoutSeparatorUsesStationAsArtist() {
        val (artist, title) = TrackTitles.parse("Some Song Title")!!
        assertEquals(StreamConstants.STATION_NAME, artist)
        assertEquals("Some Song Title", title)
    }

    @Test
    fun parseDecodesUrlEncodedIcyTitle() {
        val (artist, title) =
            TrackTitles.parse("Megawhite - %D0%9C%D1%96%D0%B9 %D0%B1%D1%80%D0%B0%D1%82%D0%B5", decodeUrl = true)!!
        assertEquals("Megawhite", artist)
        assertEquals("Мій брате", title)
    }

    @Test
    fun parseReturnsNullForBlank() {
        assertNull(TrackTitles.parse(""))
        assertNull(TrackTitles.parse("   "))
    }

    // --- TrackMetadataStore ----------------------------------------------------

    @Test
    fun placeholderOverridesStaleRealTitle() {
        TrackMetadataStore.publish("Megawhite - Мій брате")
        TrackMetadataStore.publishPlaceholder()
        assertEquals(TrackTitles.LIVE_PLACEHOLDER, TrackMetadataStore.currentTrack.value)
    }

    @Test
    fun realSiteTitleReplacesPlaceholder() {
        TrackMetadataStore.publishPlaceholder()
        TrackMetadataStore.publishSite("Megawhite - Мій брате")
        assertEquals("Megawhite - Мій брате", TrackMetadataStore.currentTrack.value)
        assertTrue(TrackMetadataStore.fromSite.value)
    }

    @Test
    fun realLiveTitleReplacesPlaceholder() {
        TrackMetadataStore.publishPlaceholder()
        TrackMetadataStore.publish("Аквариум - Мой друг Кит", fromSite = false)
        assertEquals("Аквариум - Мой друг Кит", TrackMetadataStore.currentTrack.value)
        assertFalse(TrackMetadataStore.fromSite.value)
    }

    @Test
    fun liveTitleWinsOverSiteTitle() {
        TrackMetadataStore.publishSite("Site Artist - Site Title")
        TrackMetadataStore.publish("Live Artist - Live Title", fromSite = false)
        assertEquals("Live Artist - Live Title", TrackMetadataStore.currentTrack.value)
        assertFalse(TrackMetadataStore.fromSite.value)
    }

    @Test
    fun siteTitleDoesNotOverrideLiveTitle() {
        TrackMetadataStore.publish("Live Artist - Live Title", fromSite = false)
        TrackMetadataStore.publishSite("Site Artist - Site Title")
        assertEquals("Live Artist - Live Title", TrackMetadataStore.currentTrack.value)
    }

    @Test
    fun placeholderKeepsLiveSourceBadge() {
        TrackMetadataStore.publishPlaceholder()
        assertFalse(TrackMetadataStore.fromSite.value)
    }
}
