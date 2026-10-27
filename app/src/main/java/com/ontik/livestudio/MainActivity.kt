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

    private lateinit var platformSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var ratioSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var floatingWidgetCheckbox: CheckBox
    private lateinit var keepScreenOnCheckbox: CheckBox
    private lateinit var noiseCancelCheckbox: CheckBox
    private lateinit var loopCheckbox: CheckBox
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var toggleKeyVisibilityBtn: TextView
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var switchCameraBtn: Button
    private lateinit var torchBtn: Button
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var youtubeBtn: Button
    private lateinit var pickFileBtn: Button
    private lateinit var statusText: TextView
    private lateinit var viewersCountText: TextView
    private lateinit var liveTimerText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var cameraPreview: OpenGlView
    private lateinit var cameraControlsLayout: LinearLayout

    private val prefs by lazy { SecurePrefs(this) }
    private val ytManager by lazy { YouTubeAuthManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedFileUri: String? = null

    private var timerJob: Job? = null
    private var viewersJob: Job? = null
    private var streamStartTime: Long = 0L
    private var isPasswordVisible = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(StreamingService.EXTRA_STATUS_MSG)
            val isLive = intent?.getBooleanExtra(StreamingService.EXTRA_IS_LIVE, false) ?: false
            val bitrate = intent?.getLongExtra(StreamingService.EXTRA_BITRATE, -1L) ?: -1L
            val isMuted = intent?.getBooleanExtra(StreamingService.EXTRA_IS_MUTED, false) ?: false
            val isPaused = intent?.getBooleanExtra(StreamingService.EXTRA_IS_PAUSED, false) ?: false

            if (!msg.isNullOrEmpty()) statusText.text = "Status: $msg"
            muteBtn.text = if (isMuted) "Unmute" else "Mute"
            pauseBtn.text = if (isPaused) "Resume" else "Pause"

            if (bitrate >= 0) {
                bitrateText.text = if (bitrate > 1000) String.format(Locale.US, "%.1f Mbps", bitrate / 1000.0) else "$bitrate kbps"
            }

            if (isLive) {
                updateUiForStreamingState(true)
                if (timerJob == null) startTimer()
                if (viewersJob == null) startViewersPolling()
            } else if (msg?.contains("stopped", ignoreCase = true) == true || msg?.contains("failed", ignoreCase = true) == true) {
                updateUiForStreamingState(false)
                stopTimer()
                stopViewersPolling()
            }
        }
    }

    private fun startViewersPolling() {
        viewersJob?.cancel()
        viewersJob = scope.launch {
            while (isActive) {
                val v = ytManager.getLiveViewerCount()
                if (v != null) {
                    viewersCountText.text = "👥 Viewers: $v"
                    val i = Intent(this@MainActivity, StreamingService::class.java).apply {
                        action = StreamingService.ACTION_UPDATE_VIEWERS
                        putExtra(StreamingService.EXTRA_VIEWERS, v)
                    }
                    startService(i)
                }
                delay(20000)
            }
        }
    }

    private fun stopViewersPolling() {
        viewersJob?.cancel()
        viewersJob = null
        viewersCountText.text = "👥 Viewers: --"
    }

    private fun updateUiForStreamingState(isStreaming: Boolean) {
        if (isStreaming) {
            startBtn.isEnabled = false
            startBtn.setBackgroundResource(R.drawable.bg_btn_disabled)
            startBtn.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

            pauseBtn.isEnabled = true
            pauseBtn.setBackgroundResource(R.drawable.bg_btn_mute)
            pauseBtn.setTextColor(ContextCompat.getColor(this, android.R.color.white))

            muteBtn.isEnabled = true
            muteBtn.setBackgroundResource(R.drawable.bg_btn_mute)
            muteBtn.setTextColor(ContextCompat.getColor(this, android.R.color.white))

            stopBtn.isEnabled = true
            stopBtn.setBackgroundResource(R.drawable.bg_btn_stop)
            stopBtn.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            startBtn.isEnabled = true
            startBtn.setBackgroundResource(R.drawable.bg_btn_start)
            startBtn.setTextColor(ContextCompat.getColor(this, android.R.color.white))

            pauseBtn.isEnabled = false
            pauseBtn.setBackgroundResource(R.drawable.bg_btn_disabled)
            pauseBtn.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            pauseBtn.text = "Pause"

            muteBtn.isEnabled = false
            muteBtn.setBackgroundResource(R.drawable.bg_btn_disabled)
            muteBtn.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            muteBtn.text = "Mute"

            stopBtn.isEnabled = false
            stopBtn.setBackgroundResource(R.drawable.bg_btn_disabled)
            stopBtn.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun startTimer() {
        streamStartTime = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - streamStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                val hours = (elapsed / (1000 * 60 * 60))
                liveTimerText.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        liveTimerText.text = "00:00:00"
        bitrateText.text = "0 kbps"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) proceedWithCurrentMode()
        else statusText.text = "Status: Required permissions denied"
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchService(StreamMode.SCREEN, result.resultCode, result.data)
        } else {
            statusText.text = "Status: Screen capture permission denied"
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                selectedFileUri = uri.toString()
                statusText.text = "Status: Video file selected"
            }
        }
    }

    private val youtubeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            statusText.text = "Status: Creating YouTube live broadcast..."
            scope.launch {
                val r = ytManager.createLiveStream()
                if (r != null) {
                    platformSpinner.setSelection(0)
                    urlInput.setText(r.first)
                    keyInput.setText(r.second)
                    prefs.rtmpUrl = r.first
                    prefs.streamKey = r.second
                    statusText.text = "Status: YouTube stream ready"
                } else {
                    statusText.text = "Status: YouTube setup failed"
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        platformSpinner         = findViewById(R.id.platformSpinner)
        modeSpinner             = findViewById(R.id.modeSpinner)
        ratioSpinner            = findViewById(R.id.ratioSpinner)
        qualitySpinner          = findViewById(R.id.qualitySpinner)
        floatingWidgetCheckbox  = findViewById(R.id.floatingWidgetCheckbox)
        keepScreenOnCheckbox    = findViewById(R.id.keepScreenOnCheckbox)
        noiseCancelCheckbox     = findViewById(R.id.noiseCancelCheckbox)
        loopCheckbox            = findViewById(R.id.loopCheckbox)
        urlInput                = findViewById(R.id.urlInput)
        keyInput                = findViewById(R.id.keyInput)
        toggleKeyVisibilityBtn  = findViewById(R.id.toggleKeyVisibilityBtn)
        startBtn                = findViewById(R.id.startBtn)
        pauseBtn                = findViewById(R.id.pauseBtn)
        muteBtn                 = findViewById(R.id.muteBtn)
        stopBtn                 = findViewById(R.id.stopBtn)
        switchCameraBtn         = findViewById(R.id.switchCameraBtn)
        torchBtn                = findViewById(R.id.torchBtn)
        zoomSeekBar             = findViewById(R.id.zoomSeekBar)
        zoomLabel               = findViewById(R.id.zoomLabel)
        youtubeBtn              = findViewById(R.id.youtubeBtn)
        pickFileBtn             = findViewById(R.id.pickFileBtn)
        statusText              = findViewById(R.id.statusText)
        viewersCountText        = findViewById(R.id.viewersCountText)
        liveTimerText           = findViewById(R.id.liveTimerText)
        bitrateText             = findViewById(R.id.bitrateText)
        cameraPreview           = findViewById(R.id.cameraPreview)
        cameraControlsLayout    = findViewById(R.id.cameraControlsLayout)

        StreamingService.openGlView = cameraPreview

        toggleKeyVisibilityBtn.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            keyInput.inputType = if (isPasswordVisible) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            toggleKeyVisibilityBtn.text = if (isPasswordVisible) "HIDE" else "SHOW"
            keyInput.setSelection(keyInput.text.length)
        }

        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoomLevel = 1.0f + (progress / 10.0f)
                zoomLabel.text = String.format(Locale.US, "Zoom: %.1fx", zoomLevel)
                StreamingService.setCameraZoom(zoomLevel)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        cameraPreview.setOnTouchListener { _, event ->
            StreamingService.handleCameraTouch(event)
            true
        }

        fun createSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
            return ArrayAdapter(this, R.layout.item_spinner, items).apply {
                setDropDownViewResource(R.layout.item_spinner_dropdown)
            }
        }

        platformSpinner.adapter = createSpinnerAdapter(listOf("YouTube Live", "Facebook Live", "Twitch", "Custom RTMP"))
        modeSpinner.adapter = createSpinnerAdapter(listOf("Camera Live", "Screen Live", "Video File Live"))
        ratioSpinner.adapter = createSpinnerAdapter(listOf("16:9 Landscape (YouTube Standard)", "9:16 Portrait (YouTube Shorts / Vertical)"))
        qualitySpinner.adapter = createSpinnerAdapter(listOf("1080p Full HD (4.5 Mbps)", "720p HD (2.5 Mbps - Recommended)", "480p SD (1.2 Mbps - Data Saver)"))

        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                cameraPreview.visibility = if (pos == 0) View.VISIBLE else View.GONE
                cameraControlsLayout.visibility = if (pos == 0) View.VISIBLE else View.GONE
                floatingWidgetCheckbox.visibility = if (pos == 1) View.VISIBLE else View.GONE
                pickFileBtn.visibility = if (pos == 2) View.VISIBLE else View.GONE
                loopCheckbox.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        urlInput.setText(prefs.rtmpUrl.ifEmpty { "rtmp://a.rtmp.youtube.com/live2" })
        keyInput.setText(prefs.streamKey)

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val key = keyInput.text.toString().trim()
            if (url.isEmpty()) { statusText.text = "Status: Enter RTMP URL"; return@setOnClickListener }
            if (keepScreenOnCheckbox.isChecked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            prefs.rtmpUrl = url
            prefs.streamKey = key
            checkPermissionsAndProceed()
        }

        pauseBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_TOGGLE_PAUSE })
        }

        muteBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_TOGGLE_MUTE })
        }

        switchCameraBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_SWITCH_CAMERA })
        }

        torchBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_TOGGLE_TORCH })
        }

        stopBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
            stopTimer()
            stopViewersPolling()
            updateUiForStreamingState(false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusText.text = "Status: Stopping stream..."
        }

        youtubeBtn.setOnClickListener { youtubeLauncher.launch(ytManager.signInIntent()) }

        pickFileBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
            filePicker.launch(intent)
        }

        val filter = IntentFilter(StreamingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun checkPermissionsAndProceed() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (modeSpinner.selectedItemPosition == 0) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        } else if (modeSpinner.selectedItemPosition == 1) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else proceedWithCurrentMode()
    }

    private fun proceedWithCurrentMode() {
        when (modeSpinner.selectedItemPosition) {
            0 -> launchService(StreamMode.CAMERA)
            1 -> {
                if (floatingWidgetCheckbox.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
            2 -> if (selectedFileUri == null) statusText.text = "Status: Pick video first" else launchService(StreamMode.FILE)
        }
    }

    private fun launchService(mode: StreamMode, resultCode: Int = 0, resultData: Intent? = null) {
        val isPortrait = (ratioSpinner.selectedItemPosition == 1)
        val (width, height, bitrate) = when (qualitySpinner.selectedItemPosition) {
            0 -> if (isPortrait) Triple(1080, 1920, 4_500_000) else Triple(1920, 1080, 4_500_000)
            2 -> if (isPortrait) Triple(480, 854, 1_200_000) else Triple(854, 480, 1_200_000)
            else -> if (isPortrait) Triple(720, 1280, 2_500_000) else Triple(1280, 720, 2_500_000)
        }
        val config = StreamConfig(
            mode = mode,
            rtmpUrl = urlInput.text.toString().trim(),
            streamKey = keyInput.text.toString().trim(),
            width = width, height = height, videoBitrate = bitrate,
            echoCanceler = noiseCancelCheckbox.isChecked,
            noiseSuppressor = noiseCancelCheckbox.isChecked,
            fileUri = selectedFileUri,
            loopFile = loopCheckbox.isChecked
        )
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_CONFIG, config)
            putExtra(StreamingService.EXTRA_ENABLE_FLOATING, floatingWidgetCheckbox.isChecked)
            if (resultData != null) {
                putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamingService.EXTRA_RESULT_DATA, resultData)
            }
            if (mode == StreamMode.FILE && selectedFileUri != null) {
                data = Uri.parse(selectedFileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        StreamingService.openGlView = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimer()
        stopViewersPolling()
        scope.cancel()
        super.onDestroy()
    }
}
