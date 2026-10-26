package com.ontik.livestudio

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

class StreamingService : Service(), ConnectChecker {

    companion object {
        const val ACTION_START = "com.ontik.START"
        const val ACTION_STOP  = "com.ontik.STOP"
        const val EXTRA_CONFIG = "config"
        private const val TAG = "StreamingService"
    }

    private var rtmpCamera2: RtmpCamera2? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
                if (config != null) startCamera(config)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startCamera(config: StreamConfig) {
        NotificationHelper.createChannel(this)
        ServiceCompat.startForeground(
            this,
            NotificationHelper.id(),
            NotificationHelper.build(this, "Camera streaming…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        val view = OpenGlView(this).apply { startGlThread() }
        rtmpCamera2 = RtmpCamera2(view, this).apply {
            setReTries(10)
            val vOk = prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 0)
            val aOk = prepareAudio(config.audioBitrate, config.sampleRate, config.stereo)
            if (vOk && aOk) {
                startPreview()
                startStream(config.fullRtmpUrl)
            } else {
                Log.e(TAG, "Prepare failed")
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        rtmpCamera2 = null
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
