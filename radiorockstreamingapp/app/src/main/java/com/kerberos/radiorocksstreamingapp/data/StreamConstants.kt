package com.kerberos.radiorocksstreamingapp.data

object StreamConstants {
    const val STATION_NAME = "Radio ROKS"
    const val STATION_SLOGAN = "Rok-Khvylya Ukrayiny"
    const val PRIMARY_STREAM_URL = "https://online.radioroks.ua/RadioROKS"
    const val FALLBACK_STREAM_URL = "https://online.radioroks.ua/RadioROKS_AAC"

    // Buffer settings tuned for mobile network resilience while driving
    const val MIN_BUFFER_MS = 15_000
    const val MAX_BUFFER_MS = 45_000
    const val BUFFER_FOR_PLAYBACK_MS = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    // Reconnection parameters
    const val MAX_RETRY_ATTEMPTS = 15
    const val INITIAL_RETRY_DELAY_MS = 2_000L
    const val MAX_RETRY_DELAY_MS = 30_000L

    // Actions for intents / broadcasts
    const val ACTION_PLAY = "com.kerberos.radiorocksstreamingapp.ACTION_PLAY"
    const val ACTION_PAUSE = "com.kerberos.radiorocksstreamingapp.ACTION_PAUSE"
    const val ACTION_TOGGLE = "com.kerberos.radiorocksstreamingapp.ACTION_TOGGLE"
    const val ACTION_RETRY = "com.kerberos.radiorocksstreamingapp.ACTION_RETRY"
}
