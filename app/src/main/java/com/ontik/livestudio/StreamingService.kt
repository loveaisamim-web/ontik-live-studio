package com.ontik.livestudio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.rtmp.RtmpFromFile
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.*

class StreamingService : Service(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    companion object {
        const val ACTION_START = "com.ontik.START"
        const val ACTION_STOP  = "com.ontik.STOP"
        const val ACTION_TOGGLE_MUTE = "com.ontik.TOGGLE_MUTE"
        const val ACTION_SWITCH_CAMERA = "com.ontik.SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.ontik.TOGGLE_TORCH"
        const val ACTION_STATUS = "com.ontik.STREAM_STATUS"
        const val EXTRA_STATUS_MSG = "status_message"
        const val EXTRA_BITRATE = "bitrate_kbps"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_MUTED = "is_muted"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "StreamingService"

        var openGlView: OpenGlView? = null
    }

    private var rtmpCamera2: RtmpCamera2? = null
    private var rtmpDisplay: RtmpDisplay? = null
    private var rtmpFromFile: RtmpFromFile? = null

    private var activeConfig: StreamConfig? = null
    private var isStreamingActive = false
    private var isAudioMuted = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
                if (config != null) {
                    activeConfig = config
                    isStreamingActive = true
                    isAudioMuted = false
                    reconnectAttempts = 0
                    startStreaming(config, intent)
                }
            }
            ACTION_TOGGLE_MUTE -> toggleAudioMute()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_STOP -> {
                isStreamingActive = false
                stopStreaming()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ONTIKLiveStudio::StreamWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release error: ${e.message}")
        }
        wakeLock = null
    }

    private fun switchCamera() {
        try {
            rtmpCamera2?.let {
                it.switchCamera()
                broadcastStatus("Camera switched")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Switch camera failed: ${e.message}")
        }
    }

    private fun toggleTorch() {
        try {
            rtmpCamera2?.let {
                if (it.isLanternEnabled) {
                    it.disableLantern()
                    broadcastStatus("Torch OFF")
                } else {
                    it.enableLantern()
                    broadcastStatus("Torch ON")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Torch toggle failed: ${e.message}")
        }
    }

    private fun toggleAudioMute() {
        val streamer: Any? = rtmpCamera2 ?: rtmpDisplay ?: rtmpFromFile
        if (streamer == null || !isStreamingActive) {
            broadcastStatus("Stream is not active to toggle mic")
            return
        }

        try {
            val targetMute = !isAudioMuted
            val methodName = if (targetMute) "muteAudio" else "unMuteAudio"
            val fallbackMethodName = if (targetMute) "disableAudio" else "enableAudio"

            val method = try {
                streamer.javaClass.getMethod(methodName)
            } catch (_: NoSuchMethodException) {
                streamer.javaClass.getMethod(fallbackMethodName)
            }

            method.invoke(streamer)
            isAudioMuted = targetMute

            val intent = Intent(ACTION_STATUS).apply {
                putExtra(EXTRA_IS_MUTED, isAudioMuted)
                putExtra(EXTRA_STATUS_MSG, if (isAudioMuted) "Microphone Muted" else "Microphone Live")
                putExtra(EXTRA_IS_LIVE, true)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Audio mute toggle error: ${e.message}", e)
        }
    }

    private fun broadcastStatus(msg: String, isLive: Boolean = false) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
            putExtra(EXTRA_IS_LIVE, isLive)
            putExtra(EXTRA_IS_MUTED, isAudioMuted)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastBitrate(kbps: Long) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_BITRATE, kbps)
            putExtra(EXTRA_IS_LIVE, true)
            putExtra(EXTRA_IS_MUTED, isAudioMuted)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun startStreaming(config: StreamConfig, intent: Intent) {
        acquireWakeLock()
        NotificationHelper.createChannel(this)

        val fgsType = if (Build.VERSION.SDK_INT >= 34) {
            when (config.mode) {
                StreamMode.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.FILE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            when (config.mode) {
                StreamMode.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.FILE -> 0
            }
        } else {
            0
        }

        ServiceCompat.startForeground(
            this, NotificationHelper.id(),
            NotificationHelper.build(this, "Streaming ${config.mode.name.lowercase()}..."),
            fgsType
        )

        broadcastStatus("Initializing stream...")

        when (config.mode) {
            StreamMode.CAMERA -> startCamera(config)
            StreamMode.SCREEN -> startScreen(config, intent)
            StreamMode.FILE -> startFile(config)
        }
    }

    private fun startCamera(config: StreamConfig) {
        val view = openGlView
        rtmpCamera2 = if (view != null) {
            RtmpCamera2(view, this)
        } else {
            RtmpCamera2(this, this)
        }
        
        rtmpCamera2?.apply {
            getStreamClient().setReTries(0)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 0)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startScreen(config: StreamConfig, intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            broadcastStatus("Screen capture data missing")
            stopStreaming()
            return
        }
        rtmpDisplay = RtmpDisplay(this, true, this).apply {
            getStreamClient().setReTries(0)
            setIntentResult(resultCode, resultData)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 320)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startFile(config: StreamConfig) {
        val path = config.fileUri
        if (path.isNullOrBlank()) {
            broadcastStatus("No video file selected")
            stopStreaming()
            return
        }

        val uri = android.net.Uri.parse(path)
        rtmpFromFile = RtmpFromFile(
            this, this, this, this
        ).apply {
            getStreamClient().setReTries(0)
            setLoopMode(config.loopFile)

            val videoPrepared = prepareVideo(this@StreamingService, uri)
            val audioPrepared = prepareAudio(this@StreamingService, uri)
            if (!videoPrepared && !audioPrepared) {
                broadcastStatus("Unable to prepare video file")
                stopStreaming()
                return
            }

            startStream(config.fullRtmpUrl)
        }
    }

    private fun handleAutoReconnect(reason: String) {
        if (!isStreamingActive || activeConfig == null) return

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            broadcastStatus("Dropped ($reason). Reconnecting ($reconnectAttempts/$maxReconnectAttempts)...")
            reconnectJob?.cancel()
            reconnectJob = serviceScope.launch {
                delay(3000)
                if (isStreamingActive && activeConfig != null) {
                    val url = activeConfig!!.fullRtmpUrl
                    when (activeConfig!!.mode) {
                        StreamMode.CAMERA -> rtmpCamera2?.let { if (!it.isStreaming) it.startStream(url) }
                        StreamMode.SCREEN -> rtmpDisplay?.let { if (!it.isStreaming) it.startStream(url) }
                        StreamMode.FILE -> rtmpFromFile?.let { if (!it.isStreaming) it.startStream(url) }
                    }
                }
            }
        } else {
            broadcastStatus("Connection failed after $maxReconnectAttempts retries.")
            stopStreaming()
        }
    }

    private fun stopStreaming() {
        isStreamingActive = false
        isAudioMuted = false
        reconnectJob?.cancel()
        releaseWakeLock()
        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream(); it.stopPreview() } } catch (_: Exception) {}
        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        rtmpCamera2 = null
        rtmpDisplay = null
        rtmpFromFile = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        broadcastStatus("Stream stopped", isLive = false)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopStreaming()
        super.onDestroy()
    }

    override fun onVideoDecoderFinished() {}
    override fun onAudioDecoderFinished() {}
    override fun onConnectionStarted(url: String) { 
        Log.d(TAG, "Connecting: $url")
        broadcastStatus("Connecting to RTMP...")
    }
    override fun onConnectionSuccess() { 
        Log.d(TAG, "Connected")
        reconnectAttempts = 0
        broadcastStatus("Connected (Live)", isLive = true)
    }
    override fun onConnectionFailed(reason: String) { 
        Log.e(TAG, "Failed: $reason")
        handleAutoReconnect(reason)
    }
    override fun onNewBitrate(bitrate: Long) {
        broadcastBitrate(bitrate / 1000)
    }
    override fun onDisconnect() { 
        Log.d(TAG, "Disconnected")
        handleAutoReconnect("Disconnected")
    }
    override fun onAuthError() { 
        Log.e(TAG, "Auth error")
        broadcastStatus("RTMP Authentication failed")
        stopStreaming()
    }
    override fun onAuthSuccess() {}
}
