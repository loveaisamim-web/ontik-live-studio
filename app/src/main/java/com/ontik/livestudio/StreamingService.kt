package com.ontik.livestudio

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.rtmp.RtmpFromFile
import com.pedro.library.view.OpenGlView

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
    private var openGlView: OpenGlView? = null

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
            this, NotificationHelper.id(),
            NotificationHelper.build(this, "Streaming ${config.mode.name.lowercase()}..."),
            fgsType
        )

        when (config.mode) {
            StreamMode.CAMERA -> startCamera(config)
            StreamMode.SCREEN -> startScreen(config, intent)
            StreamMode.FILE -> startFile(config)
        }
    }

    private fun startCamera(config: StreamConfig) {
        val view = OpenGlView(this).apply { startGlThread() }
        openGlView = view
        rtmpCamera2 = RtmpCamera2(view, this).apply {
            setReTries(10)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 0)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startScreen(config: StreamConfig, intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            Log.e(TAG, "Missing projection result data")
            stopStreaming()
            return
        }
        rtmpDisplay = RtmpDisplay(this, true, this).apply {
            setReTries(10)
            setIntentResult(resultCode, resultData)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 320)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startFile(config: StreamConfig) {
        val path = config.fileUri
        if (path.isNullOrBlank()) {
            Log.e(TAG, "No file path")
            stopStreaming()
            return
        }
        val view = OpenGlView(this).apply { startGlThread() }
        openGlView = view
        rtmpFromFile = RtmpFromFile(view, this).apply {
            setReTries(10)
            setVideoFile(path)
            setLoopMode(config.loopFile)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun stopStreaming() {
        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        rtmpCamera2 = null
        rtmpDisplay = null
        rtmpFromFile = null
        openGlView = null
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
