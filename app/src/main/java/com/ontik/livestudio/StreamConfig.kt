package com.ontik.livestudio

import java.io.Serializable

enum class StreamMode {
    CAMERA,
    SCREEN,
    FILE
}

data class StreamConfig(
    val mode: StreamMode,
    val rtmpUrl: String,
    val streamKey: String,
    val width: Int = 720,
    val height: Int = 1280,
    val fps: Int = 30,
    val videoBitrate: Int = 2_000_000,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 44100,
    val stereo: Boolean = false, // Mono eliminates crackling & choppy sound
    val echoCanceler: Boolean = false, // Disabling AEC eliminates audio dropouts
    val noiseSuppressor: Boolean = false,
    val fileUri: String? = null,
    val loopFile: Boolean = true
) : Serializable {
    val fullRtmpUrl: String
        get() = if (rtmpUrl.endsWith("/")) "$rtmpUrl$streamKey" else "$rtmpUrl/$streamKey"
}
