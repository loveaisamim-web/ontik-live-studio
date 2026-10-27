package com.ontik.livestudio

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
        const val ACTION_TOGGLE_PAUSE = "com.ontik.TOGGLE_PAUSE"
        const val ACTION_SWITCH_CAMERA = "com.ontik.SWITCH_CAMERA"
        const val ACTION_TOGGLE_TORCH = "com.ontik.TOGGLE_TORCH"
        const val ACTION_SET_ZOOM = "com.ontik.SET_ZOOM"
        const val ACTION_UPDATE_VIEWERS = "com.ontik.UPDATE_VIEWERS"
        const val ACTION_STATUS = "com.ontik.STREAM_STATUS"
        const val EXTRA_STATUS_MSG = "status_message"
        const val EXTRA_BITRATE = "bitrate_kbps"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_MUTED = "is_muted"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_VIEWERS = "viewers_count"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ZOOM_LEVEL = "zoom_level"
        const val EXTRA_ENABLE_FLOATING = "enable_floating"
        private const val TAG = "StreamingService"

        var openGlView: OpenGlView? = null
        private var activeServiceInstance: StreamingService? = null

        fun setCameraZoom(level: Float) { activeServiceInstance?.applyCameraZoom(level) }
        fun handleCameraTouch(event: MotionEvent) { activeServiceInstance?.applyCameraTouch(event) }
    }

    private var rtmpCamera2: RtmpCamera2? = null
    private var rtmpDisplay: RtmpDisplay? = null
    private var rtmpFromFile: RtmpFromFile? = null

    private var activeConfig: StreamConfig? = null
    private var isStreamingActive = false
    private var isAudioMuted = false
    private var isStreamPaused = false
    private var currentViewers = 0L
    private var currentBitrateKbps = 0L

    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var windowManager: WindowManager? = null
    private var floatingWidgetView: View? = null
    private var floatingDetailsCard: LinearLayout? = null
    private var floatViewersText: TextView? = null
    private var floatBitrateText: TextView? = null
    private var floatPauseBtn: Button? = null
    private var floatMuteBtn: Button? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isDetailsOpen = false

    private val autoFadeRunnable = Runnable {
        floatingWidgetView?.animate()?.alpha(0.28f)?.setDuration(400)?.start()
    }

    private fun resetAutoFadeTimer() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        floatingWidgetView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        uiHandler.postDelayed(autoFadeRunnable, 3000)
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
                    activeConfig = config; isStreamingActive = true; isAudioMuted = false; isStreamPaused = false; reconnectAttempts = 0
                    startStreaming(config, intent)
                    if (enableFloating) showFloatingWidget()
                }
            }
            ACTION_TOGGLE_PAUSE -> toggleStreamPause()
            ACTION_TOGGLE_MUTE -> toggleAudioMute()
            ACTION_SWITCH_CAMERA -> switchCamera()
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_SET_ZOOM -> applyCameraZoom(intent.getFloatExtra(EXTRA_ZOOM_LEVEL, 1.0f))
            ACTION_UPDATE_VIEWERS -> { currentViewers = intent.getLongExtra(EXTRA_VIEWERS, 0L); updateFloatingStats() }
            ACTION_STOP -> { isStreamingActive = false; stopStreaming(keepPreview = false) }
        }
        return START_NOT_STICKY
    }

    fun applyCameraZoom(level: Float) {
        rtmpCamera2?.let { cam ->
            try { cam.javaClass.getMethod("setZoom", Float::class.javaPrimitiveType).invoke(cam, level) } 
            catch (_: Exception) { try { cam.javaClass.getMethod("setZoom", Float::class.javaObjectType).invoke(cam, level) } catch (_: Exception) {} }
        }
    }

    fun applyCameraTouch(event: MotionEvent) {
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
                    try { obj.javaClass.getMethod("muteAudio").invoke(obj) } 
                    catch (_: Exception) { try { obj.javaClass.getMethod("disableAudio").invoke(obj) } catch (_: Exception) {} }
                } else {
                    gl?.javaClass?.getMethod("unMuteVideo")?.invoke(gl)
                    if (!isAudioMuted) {
                        try { obj.javaClass.getMethod("unMuteAudio").invoke(obj) } 
                        catch (_: Exception) { try { obj.javaClass.getMethod("enableAudio").invoke(obj) } catch (_: Exception) {} }
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Pause invoke error: ${e.message}") }
        }

        rtmpCamera2?.let { invokePause(it) }
        rtmpDisplay?.let { invokePause(it) }
        rtmpFromFile?.let { invokePause(it) }

        broadcastStatus(if (isStreamPaused) "Stream Paused" else "Stream Resumed", isLive = true)
        updateFloatingStats()
    }

    private fun toggleAudioMute() {
        if (!isStreamingActive) return
        isAudioMuted = !isAudioMuted

        fun invokeMute(obj: Any) {
            val method = if (isAudioMuted) "muteAudio" else "unMuteAudio"
            val fallback = if (isAudioMuted) "disableAudio" else "enableAudio"
            try { obj.javaClass.getMethod(method).invoke(obj) } 
            catch (_: NoSuchMethodException) { try { obj.javaClass.getMethod(fallback).invoke(obj) } catch (_: Exception) {} }
        }

        rtmpCamera2?.let { invokeMute(it) }
        rtmpDisplay?.let { invokeMute(it) }
        rtmpFromFile?.let { invokeMute(it) }

        updateFloatingStats()
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_IS_MUTED, isAudioMuted); putExtra(EXTRA_STATUS_MSG, if (isAudioMuted) "Microphone Muted" else "Microphone Live")
            putExtra(EXTRA_IS_LIVE, true); setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (floatingWidgetView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 200 }

            val density = resources.displayMetrics.density
            val bubbleSize = (48 * density).toInt()

            val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

            val bubbleIcon = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(bubbleSize, bubbleSize); gravity = Gravity.CENTER
                text = "🔴"; textSize = 18f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EE12141D")); setStroke((1.5f * density).toInt(), Color.parseColor("#FF1744")) }
            }

            topRow.addView(bubbleIcon)

            val detailsCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; visibility = View.GONE
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 14 * density; setColor(Color.parseColor("#F2161925")); setStroke((1 * density).toInt(), Color.parseColor("#2E354B")) }
                layoutParams = LinearLayout.LayoutParams((210 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).toInt() }
            }
            floatingDetailsCard = detailsCard

            val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

            val viewersTxt = TextView(this).apply {
                text = "👥 Viewers: --"
                setTextColor(Color.parseColor("#00E676")); textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            floatViewersText = viewersTxt

            val bitrateTxt = TextView(this).apply { text = "0 kbps"; setTextColor(Color.parseColor("#8E95AA")); textSize = 11f }
            floatBitrateText = bitrateTxt

            statsRow.addView(viewersTxt); statsRow.addView(bitrateTxt)

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() }
            }

            val pauseBtn = Button(this).apply {
                text = "Pause"; textSize = 10f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 10 * density; setColor(Color.parseColor("#F57C00")) }
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0); layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f)
                setOnClickListener { resetAutoFadeTimer(); toggleStreamPause() }
            }
            floatPauseBtn = pauseBtn

            val muteBtn = Button(this).apply {
                text = "Mute"; textSize = 10f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 10 * density; setColor(Color.parseColor("#2A3147")) }
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0); layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply { leftMargin = (6 * density).toInt() }
                setOnClickListener { resetAutoFadeTimer(); toggleAudioMute() }
            }
            floatMuteBtn = muteBtn

            val stopBtn = Button(this).apply {
                text = "Stop"; textSize = 10f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 10 * density; setColor(Color.parseColor("#D50000")) }
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0); layoutParams = LinearLayout.LayoutParams(0, (32 * density).toInt(), 1f).apply { leftMargin = (6 * density).toInt() }
                setOnClickListener { stopStreaming(keepPreview = false) }
            }

            actionRow.addView(pauseBtn); actionRow.addView(muteBtn); actionRow.addView(stopBtn)
            detailsCard.addView(statsRow); detailsCard.addView(actionRow)
            rootLayout.addView(topRow); rootLayout.addView(detailsCard)

            bubbleIcon.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0; private var initialY = 0
                private var touchX = 0f; private var touchY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    resetAutoFadeTimer()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; touchX = event.rawX; touchY = event.rawY; return true }
                        MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - touchX).toInt(); params.y = initialY + (event.rawY - touchY).toInt(); windowManager?.updateViewLayout(rootLayout, params); return true }
                        MotionEvent.ACTION_UP -> {
                            if (abs(event.rawX - touchX) < 15 && abs(event.rawY - touchY) < 15) {
                                isDetailsOpen = !isDetailsOpen; detailsCard.visibility = if (isDetailsOpen) View.VISIBLE else View.GONE
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
        } catch (e: Exception) { Log.e(TAG, "Floating error: ${e.message}", e) }
    }

    private fun updateFloatingStats() {
        floatPauseBtn?.text = if (isStreamPaused) "Resume" else "Pause"
        floatMuteBtn?.text = if (isAudioMuted) "Unmute" else "Mute"
        floatViewersText?.text = if (currentViewers > 0) "👥 $currentViewers watching" else "👥 Viewers: --"
        floatBitrateText?.text = if (currentBitrateKbps > 1000) String.format("%.1f Mbps", currentBitrateKbps / 1000f) else "$currentBitrateKbps kbps"
    }

    private fun removeFloatingWidget() {
        uiHandler.removeCallbacks(autoFadeRunnable)
        try { floatingWidgetView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        floatingWidgetView = null; floatingDetailsCard = null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ONTIKLiveStudio::StreamWakeLock").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() { try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wakeLock = null }
    private fun switchCamera() { try { rtmpCamera2?.switchCamera(); broadcastStatus("Camera switched") } catch (_: Exception) {} }
    private fun toggleTorch() { try { rtmpCamera2?.let { if (it.isLanternEnabled) { it.disableLantern(); broadcastStatus("Torch OFF") } else { it.enableLantern(); broadcastStatus("Torch ON") } } } catch (_: Exception) {} }

    private fun broadcastStatus(msg: String, isLive: Boolean = false) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg); putExtra(EXTRA_IS_LIVE, isLive)
            putExtra(EXTRA_IS_MUTED, isAudioMuted); putExtra(EXTRA_IS_PAUSED, isStreamPaused)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastBitrate(kbps: Long) {
        currentBitrateKbps = kbps; updateFloatingStats()
        val intent = Intent(ACTION_STATUS).apply { putExtra(EXTRA_BITRATE, kbps); putExtra(EXTRA_IS_LIVE, true); setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun startStreaming(config: StreamConfig, intent: Intent) {
        acquireWakeLock(); NotificationHelper.createChannel(this)

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

        ServiceCompat.startForeground(this, NotificationHelper.id(), NotificationHelper.build(this, "Streaming Live..."), fgsType)
        broadcastStatus("Initializing stream...")

        when (config.mode) {
            StreamMode.CAMERA -> startCamera(config)
            StreamMode.SCREEN -> startScreen(config, intent)
            StreamMode.FILE -> startFile(config)
        }
    }

    private fun startCamera(config: StreamConfig) {
        val view = openGlView
        rtmpCamera2 = if (view != null) RtmpCamera2(view, this) else RtmpCamera2(this, this)
        
        val camRot = try {
            com.pedro.encoder.input.video.CameraHelper.getCameraOrientation(this@StreamingService)
        } catch (_: Exception) { 90 }

        rtmpCamera2?.apply {
            getStreamClient().setReTries(0)
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 2, camRot)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startScreen(config: StreamConfig, intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return
        rtmpDisplay = RtmpDisplay(this, true, this).apply {
            getStreamClient().setReTries(0)
            setIntentResult(resultCode, resultData)

            // Keeping rotation exactly 0 and passing explicit width/height
            // This natively feeds screen frames to the dimension chosen without triggering compiler bugs
            prepareVideo(config.width, config.height, config.fps, config.videoBitrate, 0, 320)
            prepareAudio(config.audioBitrate, config.sampleRate, config.stereo, config.echoCanceler, config.noiseSuppressor)
            
            startStream(config.fullRtmpUrl)
        }
    }

    private fun startFile(config: StreamConfig) {
        val path = config.fileUri ?: return
        val uri = android.net.Uri.parse(path)
        rtmpFromFile = RtmpFromFile(this, this, this, this).apply {
            getStreamClient().setReTries(0)
            setLoopMode(config.loopFile)
            if (prepareVideo(this@StreamingService, uri) || prepareAudio(this@StreamingService, uri)) {
                startStream(config.fullRtmpUrl)
            }
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
        isStreamingActive = false; isAudioMuted = false; isStreamPaused = false
        reconnectJob?.cancel(); releaseWakeLock(); removeFloatingWidget()

        try { rtmpCamera2?.let { if (it.isStreaming) it.stopStream(); if (!keepPreview) it.stopPreview() } } catch (_: Exception) {}
        try { rtmpDisplay?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}
        try { rtmpFromFile?.let { if (it.isStreaming) it.stopStream() } } catch (_: Exception) {}

        if (!keepPreview) {
            rtmpCamera2 = null; rtmpDisplay = null; rtmpFromFile = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        broadcastStatus("Stream stopped", isLive = false)
    }

    override fun onDestroy() { activeServiceInstance = null; serviceScope.cancel(); stopStreaming(keepPreview = false); super.onDestroy() }
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
