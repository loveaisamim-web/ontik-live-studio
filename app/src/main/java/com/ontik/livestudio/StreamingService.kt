package com.ontik.livestudio

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.library.rtmp.RtmpFromFile
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.*
import kotlin.math.abs

class StreamingService : Service(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    companion object {
        const val ACTION_START = "com.ontik.START"
        const val ACTION_STOP  = "com.ontik.STOP"
        const val ACTION_TOGGLE_MUTE = "com.ontik.TOGGLE_MUTE"
        const val ACTION_SWITCH_CAMERA = "com.ontik.SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.ontik.TOGGLE_TORCH"
        const val ACTION_SET_ZOOM = "com.ontik.SET_ZOOM"
        const val ACTION_STATUS = "com.ontik.STREAM_STATUS"
        const val EXTRA_STATUS_MSG = "status_message"
        const val EXTRA_BITRATE = "bitrate_kbps"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_MUTED = "is_muted"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ZOOM_LEVEL = "zoom_level"
        const val EXTRA_ENABLE_FLOATING = "enable_floating"
        private const val TAG = "StreamingService"

        var openGlView: OpenGlView? = null
        private var activeServiceInstance: StreamingService? = null

        fun setCameraZoom(level: Float) {
            activeServiceInstance?.applyCameraZoom(level)
        }

        fun handleCameraTouch(event: MotionEvent) {
            activeServiceInstance?.applyCameraTouch(event)
        }
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

    // Floating Bubble & Menu
    private var windowManager: WindowManager? = null
    private var floatingWidgetView: View? = null
    private var floatingMenuLayout: LinearLayout? = null
    private var floatingMuteBtn: Button? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isMenuExpanded = false

    private val autoFadeRunnable = Runnable {
        floatingWidgetView?.animate()?.alpha(0.25f)?.setDuration(400)?.start()
    }

    private fun resetAutoFadeTimer() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        floatingWidgetView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        uiHandler.postDelayed(autoFadeRunnable, 2000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
                val enableFloating = intent.getBooleanExtra(EXTRA_ENABLE_FLOATING, true)
                if (config != null) {
                    activeConfig = config
                    isStreamingActive = true
                    isAudioMuted = false
                    reconnectAttempts = 0
                    startStreaming(config, intent)
                    if (config.mode == StreamMode.SCREEN && enableFloating) {
                        showFloatingWidget()
                    }
                }
            }
            ACTION_TOGGLE_MUTE -> toggleAudioMute()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_SET_ZOOM -> {
                val zoom = intent.getFloatExtra(EXTRA_ZOOM_LEVEL, 1.0f)
                applyCameraZoom(zoom)
            }
            ACTION_STOP -> {
                isStreamingActive = false
                stopStreaming(keepPreview = false)
            }
        }
        return START_NOT_STICKY
    }

    fun applyCameraZoom(level: Float) {
        try {
            val method = rtmpCamera2?.javaClass?.getMethod("setZoom", Float::class.javaPrimitiveType)
            method?.invoke(rtmpCamera2, level)
        } catch (_: Exception) {
            try {
                val method = rtmpCamera2?.javaClass?.getMethod("setZoom", Float::class.javaObjectType)
                method?.invoke(rtmpCamera2, level)
            } catch (_: Exception) {}
        }
    }

    fun applyCameraTouch(event: MotionEvent) {
        try {
            val method = rtmpCamera2?.javaClass?.getMethod("setZoom", MotionEvent::class.java)
            method?.invoke(rtmpCamera2, event)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        if (floatingWidgetView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 200
            }

            val density = resources.displayMetrics.density
            val bubbleSize = (48 * density).toInt()

            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Small Round Floating Bubble Icon
            val bubbleIcon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize)
                gravity = Gravity.CENTER
                text = "🔴"
                textSize = 18f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E612141D"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#FF1744"))
                }
            }

            // Collapsible Menu Pill
            val menuPill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                visibility = View.GONE
                background = GradientDrawable().apply {
                    cornerRadius = 24 * density
                    setColor(Color.parseColor("#F0181A26"))
                    setStroke((1 * density).toInt(), Color.parseColor("#3B4259"))
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (8 * density).toInt() }
                layoutParams = lp
            }
            floatingMenuLayout = menuPill

            val muteBtn = Button(this).apply {
                text = "Mic On"
                textSize = 10f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#2A3147"))
                }
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                setOnClickListener {
                    resetAutoFadeTimer()
                    toggleAudioMute()
                }
            }
            floatingMuteBtn = muteBtn

            val stopBtn = Button(this).apply {
                text = "Stop"
                textSize = 10f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#D50000"))
                }
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (34 * density).toInt()
                ).apply { leftMargin = (6 * density).toInt() }
                layoutParams = lp
                setOnClickListener {
                    stopStreaming(keepPreview = false)
                }
            }

            menuPill.addView(muteBtn)
            menuPill.addView(stopBtn)

            rootLayout.addView(bubbleIcon)
            rootLayout.addView(menuPill)

            // Drag vs Click Detection with Auto-fade
            bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var touchX = 0f
                private var touchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    resetAutoFadeTimer()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - touchX).toInt()
                            params.y = initialY + (event.rawY - touchY).toInt()
                            windowManager?.updateViewLayout(rootLayout, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (abs(event.rawX - touchX) < 15 && abs(event.rawY - touchY) < 15) {
                                isMenuExpanded = !isMenuExpanded
                                menuPill.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(rootLayout, params)
            floatingWidgetView = rootLayout
            resetAutoFadeTimer()
        } catch (e: Exception) {
            Log.e(TAG, "Floating bubble error: ${e.message}", e)
        }
    }

    private fun removeFloatingWidget() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        try {
            floatingWidgetView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingWidgetView = null
        floatingMenuLayout = null
        floatingMuteBtn = null
    }

    private fun getCameraRotation(): Int {
        return try {
            com.pedro.encoder.input.video.CameraHelper.getCameraOrientation(this)
        } catch (_: Throwable) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.rotation
            }
            when (rot) {
                Surface.ROTATION_0 -> 90
                Surface.ROTATION_90 -> 0
                Surface.ROTATION_180 -> 270
                Surface.ROTATION_270 -> 180
                else -> 90
            }
        }
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
        } catch (_: Exception) {}
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

            floatingMuteBtn?.text = if (isAudioMuted) "Mic Off" else "Mic On"

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

        val rotation = getCameraRotation()

        rtmpCamera2?.apply {
            getStreamClient().setReTries(0)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 2, rotation)
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
            stopStreaming(keepPreview = false)
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
            stopStreaming(keepPreview = false)
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
                stopStreaming(keepPreview = false)
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
            broadcastStatus("Failed: Check RTMP Key or Internet")
            stopStreaming(keepPreview = true)
        }
    }

    private fun stopStreaming(keepPreview: Boolean = false) {
        isStreamingActive = false
        isAudioMuted = false
        reconnectJob?.cancel()
        releaseWakeLock()
        removeFloatingWidget()

        try {
            rtmpCamera2?.let {
                if (it.isStreaming) it.stopStream()
                if (!keepPreview) it.stopPreview()
            }
        } catch (_: Exception) {}

        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}

        if (!keepPreview) {
            rtmpCamera2 = null
            rtmpDisplay = null
            rtmpFromFile = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        broadcastStatus("Stream stopped", isLive = false)
    }

    override fun onDestroy() {
        activeServiceInstance = null
        serviceScope.cancel()
        stopStreaming(keepPreview = false)
        super.onDestroy()
    }

    override fun onVideoDecoderFinished() {}
    override fun onAudioDecoderFinished() {}
    override fun onConnectionStarted(url: String) { broadcastStatus("Connecting to RTMP...") }
    override fun onConnectionSuccess() { reconnectAttempts = 0; broadcastStatus("Connected (Live)", isLive = true) }
    override fun onConnectionFailed(reason: String) { handleAutoReconnect(reason) }
    override fun onNewBitrate(bitrate: Long) { broadcastBitrate(bitrate / 1000) }
    override fun onDisconnect() { handleAutoReconnect("Disconnected") }
    override fun onAuthError() { broadcastStatus("RTMP Authentication failed"); stopStreaming(keepPreview = true) }
    override fun onAuthSuccess() {}
}
