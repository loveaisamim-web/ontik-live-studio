package com.ontik.livestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: OpenGlView
    private lateinit var screenModeBg: LinearLayout
    private lateinit var liveStatusText: TextView
    private lateinit var liveTimerText: TextView
    private lateinit var viewersCountText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var switchCameraBtn: Button
    private lateinit var torchBtn: Button
    private lateinit var quickControlsLayout: LinearLayout

    private lateinit var openSettingsBtn: Button
    private lateinit var goLiveBtn: Button
    private lateinit var micBtn: Button

    private lateinit var settingsOverlay: RelativeLayout
    private lateinit var closeSettingsArea: View
    private lateinit var tabCamera: Button
    private lateinit var tabScreen: Button
    private lateinit var qualitySpinner: Spinner
    private lateinit var ratioSpinner: Spinner
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var youtubeBtn: Button

    private val prefs by lazy { SecurePrefs(this) }
    private val ytManager by lazy { YouTubeAuthManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentMode = StreamMode.CAMERA
    private var isStreamingActive = false
    private var isMicMuted = false
    private var timerJob: Job? = null
    private var viewersJob: Job? = null
    private var streamStartTime: Long = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(StreamingService.EXTRA_STATUS_MSG)
            val isLive = intent?.getBooleanExtra(StreamingService.EXTRA_IS_LIVE, false) ?: false
            val bitrate = intent?.getLongExtra(StreamingService.EXTRA_BITRATE, -1L) ?: -1L
            val isMuted = intent?.getBooleanExtra(StreamingService.EXTRA_IS_MUTED, false) ?: false

            if (!msg.isNullOrEmpty()) liveStatusText.text = msg.uppercase()
            
            isMicMuted = isMuted
            micBtn.text = if (isMicMuted) "🔇" else "🎙️"
            
            if (bitrate >= 0) bitrateText.text = if (bitrate > 1000) String.format(Locale.US, "%.1f Mbps", bitrate / 1000.0) else "$bitrate kbps"

            if (isLive && !isStreamingActive) {
                updateUiForStreamingState(true)
            } else if (!isLive && (msg?.contains("stopped", true) == true || msg?.contains("failed", true) == true || msg?.contains("Error", true) == true)) {
                updateUiForStreamingState(false)
            }
        }
    }

    private fun startViewersPolling() {
        viewersJob?.cancel()
        viewersJob = scope.launch {
            while (isActive) {
                val v = ytManager.getLiveViewerCount()
                if (v != null) viewersCountText.text = "👥 $v"
                delay(20000)
            }
        }
    }

    private fun stopViewersPolling() { viewersJob?.cancel(); viewersJob = null; viewersCountText.text = "👥 --" }

    private fun updateUiForStreamingState(isStreaming: Boolean) {
        isStreamingActive = isStreaming
        if (isStreaming) {
            goLiveBtn.text = "END STREAM"
            goLiveBtn.setBackgroundResource(R.drawable.bg_btn_stop)
            openSettingsBtn.alpha = 0.5f; openSettingsBtn.isEnabled = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (timerJob == null) startTimer()
            if (viewersJob == null) startViewersPolling()
        } else {
            goLiveBtn.text = "GO LIVE"
            goLiveBtn.setBackgroundResource(R.drawable.bg_btn_start)
            openSettingsBtn.alpha = 1.0f; openSettingsBtn.isEnabled = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            stopTimer()
            stopViewersPolling()
        }
    }

    private fun startTimer() {
        streamStartTime = SystemClock.elapsedRealtime(); timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - streamStartTime
                val s = (elapsed / 1000) % 60; val m = (elapsed / (1000 * 60)) % 60; val h = (elapsed / (1000 * 60 * 60))
                liveTimerText.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }

    private fun stopTimer() { timerJob?.cancel(); timerJob = null; liveTimerText.text = "00:00:00"; bitrateText.text = "0 kbps" }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        if (p.values.all { it }) {
            if (currentMode == StreamMode.CAMERA) ensureCameraPreview() else proceedWithScreenMode()
        } else liveStatusText.text = "PERMISSIONS DENIED"
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) launchService(StreamMode.SCREEN, result.resultCode, result.data)
        else liveStatusText.text = "SCREEN DENIED"
    }

    private val youtubeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            liveStatusText.text = "CREATING YT STREAM..."
            scope.launch {
                val r = ytManager.createLiveStream()
                if (r != null) {
                    urlInput.setText(r.first); keyInput.setText(r.second)
                    prefs.rtmpUrl = r.first; prefs.streamKey = r.second
                    liveStatusText.text = "YOUTUBE READY"
                } else liveStatusText.text = "YOUTUBE FAILED"
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.cameraPreview); screenModeBg = findViewById(R.id.screenModeBg)
        liveStatusText = findViewById(R.id.liveStatusText); liveTimerText = findViewById(R.id.liveTimerText)
        viewersCountText = findViewById(R.id.viewersCountText); bitrateText = findViewById(R.id.bitrateText)
        switchCameraBtn = findViewById(R.id.switchCameraBtn); torchBtn = findViewById(R.id.torchBtn)
        quickControlsLayout = findViewById(R.id.quickControlsLayout)

        openSettingsBtn = findViewById(R.id.openSettingsBtn); goLiveBtn = findViewById(R.id.goLiveBtn)
        micBtn = findViewById(R.id.micBtn)

        settingsOverlay = findViewById(R.id.settingsOverlay); closeSettingsArea = findViewById(R.id.closeSettingsArea)
        tabCamera = findViewById(R.id.tabCamera); tabScreen = findViewById(R.id.tabScreen)
        qualitySpinner = findViewById(R.id.qualitySpinner); ratioSpinner = findViewById(R.id.ratioSpinner)
        urlInput = findViewById(R.id.urlInput); keyInput = findViewById(R.id.keyInput)
        youtubeBtn = findViewById(R.id.youtubeBtn)

        StreamingService.openGlView = cameraPreview
        cameraPreview.setOnTouchListener { _, event -> StreamingService.handleCameraTouch(event); true }

        fun createSpinnerAdapter(items: List<String>): ArrayAdapter<String> = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        
        ratioSpinner.adapter = createSpinnerAdapter(listOf("9:16 Portrait (Shorts)", "16:9 Landscape"))
        qualitySpinner.adapter = createSpinnerAdapter(listOf("1080p Full HD (3.5 Mbps)", "720p HD (2.0 Mbps)", "480p SD (1.0 Mbps)"))

        urlInput.setText(prefs.rtmpUrl.ifEmpty { "rtmp://a.rtmp.youtube.com/live2" }); keyInput.setText(prefs.streamKey)

        // Settings Overlay Logic
        openSettingsBtn.setOnClickListener { settingsOverlay.visibility = View.VISIBLE }
        closeSettingsArea.setOnClickListener { settingsOverlay.visibility = View.GONE; prefs.rtmpUrl = urlInput.text.toString(); prefs.streamKey = keyInput.text.toString() }

        // Tabs Logic
        tabCamera.setOnClickListener { switchMode(StreamMode.CAMERA) }
        tabScreen.setOnClickListener { switchMode(StreamMode.SCREEN) }

        // Go Live Logic
        goLiveBtn.setOnClickListener {
            if (isStreamingActive) {
                startService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
                updateUiForStreamingState(false)
            } else {
                settingsOverlay.visibility = View.GONE
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) { liveStatusText.text = "URL EMPTY"; return@setOnClickListener }
                checkPermissionsAndProceed()
            }
        }

        micBtn.setOnClickListener { startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_TOGGLE_MUTE }) }
        switchCameraBtn.setOnClickListener { startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_SWITCH_CAMERA }) }
        torchBtn.setOnClickListener { startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_TOGGLE_TORCH }) }
        youtubeBtn.setOnClickListener { youtubeLauncher.launch(ytManager.signInIntent()) }

        val filter = IntentFilter(StreamingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(statusReceiver, filter)
        
        // Initial state
        switchMode(StreamMode.CAMERA)
    }

    private fun switchMode(mode: StreamMode) {
        currentMode = mode
        if (mode == StreamMode.CAMERA) {
            tabCamera.setBackgroundResource(R.drawable.bg_btn_start); tabCamera.setTextColor(Color.WHITE)
            tabScreen.setBackgroundResource(R.drawable.bg_input); tabScreen.setTextColor(Color.parseColor("#8E95AA"))
            cameraPreview.visibility = View.VISIBLE; screenModeBg.visibility = View.GONE; quickControlsLayout.visibility = View.VISIBLE
            checkCameraPermissionAndStart()
        } else {
            tabScreen.setBackgroundResource(R.drawable.bg_btn_start); tabScreen.setTextColor(Color.WHITE)
            tabCamera.setBackgroundResource(R.drawable.bg_input); tabCamera.setTextColor(Color.parseColor("#8E95AA"))
            cameraPreview.visibility = View.GONE; screenModeBg.visibility = View.VISIBLE; quickControlsLayout.visibility = View.GONE
            stopCameraPreview()
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ensureCameraPreview()
        else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun ensureCameraPreview() { startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_START_PREVIEW }) }
    private fun stopCameraPreview() { startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP_PREVIEW }) }

    private fun checkPermissionsAndProceed() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (currentMode == StreamMode.CAMERA && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else if (currentMode == StreamMode.CAMERA) launchService(StreamMode.CAMERA)
        else proceedWithScreenMode()
    }

    private fun proceedWithScreenMode() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun launchService(mode: StreamMode, resultCode: Int = 0, resultData: Intent? = null) {
        val isPortrait = (ratioSpinner.selectedItemPosition == 0)
        val qualityPos = qualitySpinner.selectedItemPosition

        val (width, height, bitrate) = when (qualityPos) {
            1 -> if (isPortrait) Triple(720, 1280, 2_000_000) else Triple(1280, 720, 2_000_000)
            2 -> if (isPortrait) Triple(480, 854, 1_000_000) else Triple(854, 480, 1_000_000)
            else -> if (isPortrait) Triple(1080, 1920, 3_500_000) else Triple(1920, 1080, 3_500_000)
        }

        val config = StreamConfig(
            mode = mode, rtmpUrl = urlInput.text.toString().trim(), streamKey = keyInput.text.toString().trim(),
            width = width, height = height, videoBitrate = bitrate,
            audioBitrate = 96_000, sampleRate = 44100, stereo = false, echoCanceler = false, noiseSuppressor = false
        )

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START; putExtra(StreamingService.EXTRA_CONFIG, config)
            if (resultData != null) { putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode); putExtra(StreamingService.EXTRA_RESULT_DATA, resultData) }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        StreamingService.openGlView = null; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimer(); stopViewersPolling(); scope.cancel(); super.onDestroy()
    }
}
