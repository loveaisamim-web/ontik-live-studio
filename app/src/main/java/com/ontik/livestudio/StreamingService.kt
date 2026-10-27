package com.ontik.livestudio

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
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
import java.util.Locale

class StreamingService : Service(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    companion object {
        const val ACTION_START = "com.ontik.START"
        const val ACTION_STOP  = "com.ontik.STOP"
        const val ACTION_START_PREVIEW = "com.ontik.START_PREVIEW"
        const val ACTION_STOP_PREVIEW  = "com.ontik.STOP_PREVIEW"
        const val ACTION_TOGGLE_MUTE = "com.ontik.TOGGLE_MUTE"
        const val ACTION_TOGGLE_PAUSE = "com.ontik.TOGGLE_PAUSE"
        const val ACTION_SWITCH_CAMERA = "com.ontik.SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.ontik.TOGGLE_TORCH"
        const val ACTION_STATUS = "com.ontik.STREAM_STATUS"
        const val EXTRA_STATUS_MSG = "status_message"
        const val EXTRA_BITRATE = "bitrate_kbps"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_MUTED = "is_muted"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "StreamingService"

        var openGlView: OpenGlView? = null
        var activeServiceInstance: StreamingService? = null
        fun handleCameraTouch(event: MotionEvent) { activeServiceInstance?.applyCameraTouch(event) }
    }

    private var rtmpCamera2: RtmpCamera2? = null
    private var rtmpDisplay: RtmpDisplay? = null
    private var rtmpFromFile: RtmpFromFile? = null

    private var activeConfig: StreamConfig? = null
    private var isStreamingActive = false
    private var isAudioMuted = false
    private var isStreamPaused = false
    private var currentBitrateKbps = 0L

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var windowManager: WindowManager? = null
    private var floatingWidgetView: View? = null
    private var floatPauseBtn: Button? = null
    private var floatMuteBtn: Button? = null
    private var floatBitrateText: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val autoFadeRunnable = Runnable { floatingWidgetView?.animate()?.alpha(0.30f)?.setDuration(400)?.start() }
    private fun resetAutoFadeTimer() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        floatingWidgetView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        uiHandler.postDelayed(autoFadeRunnable, 3000)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); activeServiceInstance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PREVIEW -> ensureCameraPreviewRunning()
            ACTION_STOP_PREVIEW -> {
                if (!isStreamingActive) {
                    try { rtmpCamera2?.stopPreview() } catch (_: Exception) {}
                }
            }
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getSerializableExtra(EXTRA_CONFIG) as? StreamConfig
                if (config != null) {
                    activeConfig = config; isStreamingActive = true; isAudioMuted = false; isStreamPaused = false; reconnectAttempts = 0
                    startStreaming(config, intent)
                    if (config.mode == StreamMode.SCREEN) uiHandler.post { showFloatingWidget() }
                }
            }
            ACTION_TOGGLE_PAUSE -> toggleStreamPause()
            ACTION_TOGGLE_MUTE -> toggleAudioMute()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_STOP -> { isStreamingActive = false; stopStreaming(keepPreview = true) }
        }
        return START_STICKY
    }

    private fun ensureCameraPreviewRunning() {
        val view = openGlView ?: return
        if (rtmpCamera2 == null) rtmpCamera2 = RtmpCamera2(view, this)
        if (rtmpCamera2?.isOnPreview == false) {
            try {
                rtmpCamera2?.startPreview()
                broadcastStatus("Camera Preview Active")
            } catch (e: Exception) { Log.e(TAG, "Camera preview error: ${e.message}") }
        }
    }

    private fun applyCameraTouch(event: MotionEvent) {
        rtmpCamera2?.let { cam -> try { cam.javaClass.getMethod("setZoom", MotionEvent::class.java).invoke(cam, event) } catch (_: Exception) {} }
    }

    private fun toggleStreamPause() {
        if (!isStreamingActive) return
        isStreamPaused = !isStreamPaused
        fun invokePause(obj: Any) {
            try {
                val gl = obj.javaClass.getMethod("getGlInterface").invoke(obj)
                if (isStreamPaused) {
                    gl?.javaClass?.getMethod("muteVideo")?.invoke(gl)
                    try { obj.javaClass.getMethod("disableAudio").invoke(obj) } catch (_: Exception) {}
                } else {
                    gl?.javaClass?.getMethod("unMuteVideo")?.invoke(gl)
                    if (!isAudioMuted) {
                        try { obj.javaClass.getMethod("enableAudio").invoke(obj) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        rtmpCamera2?.let { invokePause(it) }; rtmpDisplay?.let { invokePause(it) }; rtmpFromFile?.let { invokePause(it) }
        broadcastStatus(if (isStreamPaused) "PAUSED" else "LIVE (CONNECTED)", isLive = true)
        updateFloatingStats()
    }

    private fun toggleAudioMute() {
        if (!isStreamingActive) return
        isAudioMuted = !isAudioMuted
        val streamer = rtmpCamera2 ?: rtmpDisplay ?: rtmpFromFile ?: return
        try {
            val method = if (isAudioMuted) "disableAudio" else "enableAudio"
            streamer.javaClass.getMethod(method).invoke(streamer)
        } catch (_: Exception) {}
        updateFloatingStats()
        broadcastStatus(if (isAudioMuted) "MIC MUTED" else "LIVE (CONNECTED)", isLive = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (floatingWidgetView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 180 }
            val density = resources.displayMetrics.density
            val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
            
            val bubbleIcon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()); gravity = Gravity.CENTER
                text = "🔴"; textSize = 16f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EE12141D")); setStroke((1.5f * density).toInt(), Color.parseColor("#FF1744")) }
            }
            val detailsCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(Color.parseColor("#F2161925")); setStroke((1 * density).toInt(), Color.parseColor("#2E354B")) }
                layoutParams = LinearLayout.LayoutParams((180 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).toInt() }
            }
            
            val bitrateTxt = TextView(this).apply { text = "0 kbps"; setTextColor(Color.parseColor("#00E676")); textSize = 11f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            floatBitrateText = bitrateTxt
            detailsCard.addView(bitrateTxt)

            val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).toInt() } }
            
            floatPauseBtn = Button(this).apply { text = "⏸️"; textSize = 12f; background = GradientDrawable().apply { cornerRadius = 8 * density; setColor(Color.parseColor("#2A3147")) }; layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f); setOnClickListener { resetAutoFadeTimer(); toggleStreamPause() } }
            floatMuteBtn = Button(this).apply { text = "🎙️"; textSize = 12f; background = GradientDrawable().apply { cornerRadius = 8 * density; setColor(Color.parseColor("#2A3147")) }; layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply { leftMargin = (4 * density).toInt() }; setOnClickListener { resetAutoFadeTimer(); toggleAudioMute() } }
            val stopBtn = Button(this).apply { text = "⏹️"; textSize = 12f; background = GradientDrawable().apply { cornerRadius = 8 * density; setColor(Color.parseColor("#D50000")) }; layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply { leftMargin = (4 * density).toInt() }; setOnClickListener { stopStreaming(keepPreview = true) } }
            
            actionRow.addView(floatPauseBtn); actionRow.addView(floatMuteBtn); actionRow.addView(stopBtn)
            detailsCard.addView(actionRow)
            rootLayout.addView(bubbleIcon); rootLayout.addView(detailsCard)

            bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0; private var touchX = 0f; private var touchY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    resetAutoFadeTimer()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY; return true }
                        MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - touchX).toInt(); params.y = initialY + (event.rawY - touchY).toInt(); windowManager?.updateViewLayout(rootLayout, params); return true }
                        MotionEvent.ACTION_UP -> { if (abs(event.rawX - touchX) < 15 && abs(event.rawY - touchY) < 15) { detailsCard.visibility = if (detailsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE }; return true }
                    }
                    return false
                }
            })
            windowManager?.addView(rootLayout, params); floatingWidgetView = rootLayout; resetAutoFadeTimer()
        } catch (_: Exception) {}
    }

    private fun updateFloatingStats() {
        uiHandler.post {
            floatPauseBtn?.text = if (isStreamPaused) "▶️" else "⏸️"
            floatMuteBtn?.text = if (isAudioMuted) "🔇" else "🎙️"
            floatBitrateText?.text = if (currentBitrateKbps > 1000) String.format(Locale.US, "%.1f Mbps", currentBitrateKbps / 1000f) else "$currentBitrateKbps kbps"
        }
    }

    private fun removeFloatingWidget() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        try { floatingWidgetView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingWidgetView = null
    }

    private fun acquireWakeAndWifiLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ONTIKLiveStudio::StreamWakeLock").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ONTIKLiveStudio::StreamWifiLock").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseWakeAndWifiLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
    }

    private fun switchCamera() { try { rtmpCamera2?.switchCamera(); broadcastStatus("CAMERA SWITCHED") } catch (_: Exception) {} }
    private fun toggleTorch() { try { rtmpCamera2?.let { if (it.isLanternEnabled) { it.disableLantern(); broadcastStatus("TORCH OFF") } else { it.enableLantern(); broadcastStatus("TORCH ON") } } } catch (_: Exception) {} }

    private fun broadcastStatus(msg: String, isLive: Boolean = false) {
        val intent = Intent(ACTION_STATUS).apply { putExtra(EXTRA_STATUS_MSG, msg); putExtra(EXTRA_IS_LIVE, isLive); putExtra(EXTRA_IS_MUTED, isAudioMuted); putExtra(EXTRA_IS_PAUSED, isStreamPaused); setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun broadcastBitrate(kbps: Long) {
        currentBitrateKbps = kbps; updateFloatingStats()
        val intent = Intent(ACTION_STATUS).apply { putExtra(EXTRA_BITRATE, kbps); putExtra(EXTRA_IS_LIVE, true); setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun startStreaming(config: StreamConfig, intent: Intent) {
        acquireWakeAndWifiLocks(); NotificationHelper.createChannel(this)

        val fgsType = if (Build.VERSION.SDK_INT >= 34) {
            when (config.mode) {
                StreamMode.CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.FILE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            when (config.mode) {
                StreamMode.CAMERA, StreamMode.SCREEN -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                StreamMode.FILE -> 0
            }
        } else 0

        ServiceCompat.startForeground(this, NotificationHelper.id(), NotificationHelper.build(this, "Live Broadcast Active"), fgsType)
        broadcastStatus("INITIALIZING...")

        when (config.mode) {
            StreamMode.CAMERA -> startCamera(config)
            StreamMode.SCREEN -> startScreen(config, intent)
            StreamMode.FILE -> startFile(config)
        }
    }

    private fun startCamera(config: StreamConfig) {
        ensureCameraPreviewRunning()
        val cam = rtmpCamera2 ?: return
        
        val isPortrait = config.width < config.height
        val encW = if (isPortrait) config.height else config.width
        val encH = if (isPortrait) config.width else config.height
        val rot = if (isPortrait) 90 else 0

        val videoOk = cam.prepareVideo(encW, encH, config.fps, config.videoBitrate, 2, rot)
        val audioOk = cam.prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
        
        if (videoOk && audioOk) {
            cam.startStream(config.fullRtmpUrl)
        } else {
            broadcastStatus("CAMERA ENCODER FAILED")
            stopStreaming(keepPreview = true)
        }
    }

    private fun startScreen(config: StreamConfig, intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            broadcastStatus("SCREEN PERMISSION MISSING")
            stopStreaming(keepPreview = false)
            return
        }

        rtmpDisplay = RtmpDisplay(this, true, this).apply {
            setIntentResult(resultCode, resultData) 
            
            // 🔥 100% YOUTUBE SHORTS FIX 🔥
            // ALWAYS SEND EXACT RESOLUTION (e.g. 1080x1920). 
            // If you change this math, YouTube will throw it into a 16:9 player as a small square!
            val encW = config.width
            val encH = config.height
            val dpi = resources.displayMetrics.densityDpi

            val videoOk = prepareVideo(encW, encH, config.fps, config.videoBitrate, 0, dpi)
            val audioOk = prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
            
            if (videoOk && audioOk) {
                // Prevent squashing internally using OpenGL AspectRatioMode.ADJUST
                try {
                    val gl = javaClass.getMethod("getGlInterface").invoke(this)
                    if (gl != null) {
                        var modeClass: Class<*>? = null
                        try { modeClass = Class.forName("com.pedro.encoder.utils.gl.AspectRatioMode") } catch (_: Exception) {}
                        if (modeClass == null) {
                            try { modeClass = Class.forName("com.pedro.library.view.AspectRatioMode") } catch (_: Exception) {}
                        }
                        if (modeClass != null) {
                            val adjustMode = modeClass.enumConstants?.firstOrNull { it.toString() == "ADJUST" }
                            if (adjustMode != null) {
                                gl.javaClass.getMethod("setAspectRatioMode", modeClass).invoke(gl, adjustMode)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Aspect Ratio Mode Error: ${e.message}")
                }

                startStream(config.fullRtmpUrl)
            } else {
                broadcastStatus("SCREEN ENCODER ERROR")
                stopStreaming(keepPreview = false)
            }
        }
    }

    private fun startFile(config: StreamConfig) {
        val path = config.fileUri ?: return
        val uri = android.net.Uri.parse(path)
        rtmpFromFile = RtmpFromFile(this, this, this, this).apply {
            setLoopMode(config.loopFile)
            if (prepareVideo(this@StreamingService, uri) || prepareAudio(this@StreamingService, uri)) { startStream(config.fullRtmpUrl) }
        }
    }

    private fun handleAutoReconnect(reason: String) {
        if (!isStreamingActive || activeConfig == null) return
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            broadcastStatus("RECONNECTING ($reconnectAttempts/$maxReconnectAttempts)...", isLive = true)
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
            broadcastStatus("DISCONNECTED (SLOW NET)")
            stopStreaming(keepPreview = true)
        }
    }

    private fun stopStreaming(keepPreview: Boolean = true) {
        isStreamingActive = false; isAudioMuted = false; isStreamPaused = false
        reconnectJob?.cancel(); releaseWakeAndWifiLocks(); removeFloatingWidget()

        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}

        if (!keepPreview) {
            try { rtmpCamera2?.stopPreview() } catch (_: Exception) {}
            rtmpCamera2 = null; rtmpDisplay = null; rtmpFromFile = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        broadcastStatus("STREAM STOPPED", isLive = false)
    }

    override fun onDestroy() { activeServiceInstance = null; serviceScope.cancel(); stopStreaming(keepPreview = false); super.onDestroy() }
    override fun onVideoDecoderFinished() {}
    override fun onAudioDecoderFinished() {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { reconnectAttempts = 0; broadcastStatus("LIVE (CONNECTED)", isLive = true) }
    override fun onConnectionFailed(reason: String) { handleAutoReconnect(reason) }
    override fun onNewBitrate(bitrate: Long) { broadcastBitrate(bitrate / 1000) }
    override fun onDisconnect() { if (isStreamingActive) handleAutoReconnect("NETWORK DROP") }
    override fun onAuthError() { broadcastStatus("INVALID STREAM KEY"); stopStreaming(keepPreview = true) }
    override fun onAuthSuccess() {}
}
