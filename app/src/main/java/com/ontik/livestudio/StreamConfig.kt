package com.ontik.livestudio

import java.io.Serializable

enum class StreamMode : Serializable { CAMERA, SCREEN, FILE }

data class StreamConfig(
    val mode: StreamMode,
    val rtmpUrl: String,
    val streamKey: String,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrate: Int = 4_000_000,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 44_100,
    val stereo: Boolean = true,
    val loopFile: Boolean = false,
    val fileUri: String? = null
) : Serializable {
    val fullRtmpUrl: String
        get() = if (rtmpUrl.endsWith("/")) "$rtmpUrl$streamKey" else "$rtmpUrl/$streamKey"
}
