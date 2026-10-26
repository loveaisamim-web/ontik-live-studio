package com.ontik.livestudio

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.rtmp.RtmpFromFile
import com.pedro.library.view.OpenGlView

object MediaProjectionHolder {
    var projection: MediaProjection? = null
}

class StreamingService : Service(), ConnectChecker {

    companion object {
        const val ACTION_START = "com.ontik.START"
        const val ACTION_STOP  = "com.ontik.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "StreamingService"
    }

    private var rtmpCamera2: RtmpCamera2? = null
    private var rtmpDisplay: RtmpDisplay? = null
    private var rtmpFromFile: RtmpFromFile? = null
    private var mediaProjection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
                if (config != null) startStreaming(config, intent)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(config: StreamConfig, intent: Intent) {
        NotificationHelper.createChannel(this)

        val fgsType = when (config.mode) {
            StreamMode.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            StreamMode.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            StreamMode.FILE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }

        ServiceCompat.startForeground(
            this,
            NotificationHelper.id(),
            NotificationHelper.build(this, "Preparing ${config.mode.name.lowercase()}…"),
            fgsType
        )

        when (config.mode) {
            StreamMode.CAMERA -> startCamera(config)
            StreamMode.SCREEN -> startScreen(config, intent)
            StreamMode.FILE   -> startFile(config)
        }
    }

    private fun startCamera(config: StreamConfig) {
        val view = OpenGlView(this).apply { startGlThread() }
        rtmpCamera2 = RtmpCamera2(view, this).apply {
            setReTries(10)
            val vOk = prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 0)
            val aOk = prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            if (vOk && aOk) {
                startPreview()
                startStream(config.fullRtmpUrl)
            } else {
                Log.e(TAG, "Camera prepare failed")
                stopStreaming()
            }
        }
    }

    private fun startScreen(config: StreamConfig, intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: run {
            Log.e(TAG, "No MediaProjection result data")
            stopStreaming(); return
        }

        rtmpDisplay = RtmpDisplay(this, true, this).apply {
            setReTries(10)
            setIntentResult(resultCode, resultData)
            val vOk = prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 320)
            val aOk = prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            if (vOk && aOk) startStream(config.fullRtmpUrl)
            else { Log.e(TAG, "Screen prepare failed"); stopStreaming() }
        }
    }

    private fun startFile(config: StreamConfig) {
        val path = config.fileUri ?: run {
            Log.e(TAG, "No file path"); stopStreaming(); return
        }
        val view = OpenGlView(this).apply { startGlThread() }
        rtmpFromFile = RtmpFromFile(view, this).apply {
            setReTries(10)
            setLoopMode(config.loopFile)
            // RootEncoder 2.x FromFile API: prepareVideo(filePath, bitrate)
            @Suppress("DEPRECATION")
            val vOk = prepareVideo(path, config.videoBitrate)
            val aOk = prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            if (vOk && aOk) startStream(config.fullRtmpUrl)
            else { Log.e(TAG, "File prepare failed"); stopStreaming() }
        }
    }

    private fun stopStreaming() {
        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        rtmpCamera2 = null; rtmpDisplay = null; rtmpFromFile = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopStreaming(); super.onDestroy() }

    override fun onConnectionStarted(url: String) { Log.d(TAG, "Connecting: $url") }
    override fun onConnectionSuccess() { Log.d(TAG, "Connected") }
    override fun onConnectionFailed(reason: String) { Log.e(TAG, "Failed: $reason") }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { Log.d(TAG, "Disconnected") }
    override fun onAuthError() { Log.e(TAG, "Auth error") }
    override fun onAuthSuccess() {}
}
