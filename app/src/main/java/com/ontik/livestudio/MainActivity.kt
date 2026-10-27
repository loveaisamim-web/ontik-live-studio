package com.ontik.livestudio

import android.Manifest
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
    private lateinit var keepScreenOnCheckbox: CheckBox
    private lateinit var noiseCancelCheckbox: CheckBox
    private lateinit var loopCheckbox: CheckBox
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var startBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var switchCameraBtn: Button
    private lateinit var torchBtn: Button
    private lateinit var youtubeBtn: Button
    private lateinit var pickFileBtn: Button
    private lateinit var statusText: TextView
    private lateinit var liveTimerText: TextView
    private lateinit var bitrateText: TextView
    private lateinit var cameraPreview: OpenGlView
    private lateinit var cameraControlsLayout: LinearLayout

    private val prefs by lazy { SecurePrefs(this) }
    private val ytManager by lazy { YouTubeAuthManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedFileUri: String? = null

    private var timerJob: Job? = null
    private var streamStartTime: Long = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(StreamingService.EXTRA_STATUS_MSG)
            val isLive = intent?.getBooleanExtra(StreamingService.EXTRA_IS_LIVE, false) ?: false
            val bitrate = intent?.getLongExtra(StreamingService.EXTRA_BITRATE, -1L) ?: -1L
            val isMuted = intent?.hasExtra(StreamingService.EXTRA_IS_MUTED) == true &&
                    intent.getBooleanExtra(StreamingService.EXTRA_IS_MUTED, false)

            if (!msg.isNullOrEmpty()) {
                statusText.text = "Status: $msg"
            }

            if (intent?.hasExtra(StreamingService.EXTRA_IS_MUTED) == true) {
                muteBtn.text = if (isMuted) "Unmute Mic" else "Mute Mic"
            }

            if (bitrate >= 0) {
                bitrateText.text = if (bitrate > 1000) {
                    String.format(Locale.US, "%.1f Mbps", bitrate / 1000.0)
                } else {
                    "$bitrate kbps"
                }
            }

            if (isLive && timerJob == null) {
                startTimer()
            } else if (!isLive && msg?.contains("stopped", ignoreCase = true) == true) {
                stopTimer()
                muteBtn.text = "Mute Mic"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
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
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            proceedWithCurrentMode()
        } else {
            statusText.text = "Status: Required permissions denied"
        }
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

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
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
                    statusText.text = "Status: YouTube setup failed (check channel eligibility)"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        platformSpinner      = findViewById(R.id.platformSpinner)
        modeSpinner          = findViewById(R.id.modeSpinner)
        ratioSpinner         = findViewById(R.id.ratioSpinner)
        qualitySpinner       = findViewById(R.id.qualitySpinner)
        keepScreenOnCheckbox = findViewById(R.id.keepScreenOnCheckbox)
        noiseCancelCheckbox  = findViewById(R.id.noiseCancelCheckbox)
        loopCheckbox         = findViewById(R.id.loopCheckbox)
        urlInput             = findViewById(R.id.urlInput)
        keyInput             = findViewById(R.id.keyInput)
        startBtn             = findViewById(R.id.startBtn)
        muteBtn              = findViewById(R.id.muteBtn)
        stopBtn              = findViewById(R.id.stopBtn)
        switchCameraBtn      = findViewById(R.id.switchCameraBtn)
        torchBtn             = findViewById(R.id.torchBtn)
        youtubeBtn           = findViewById(R.id.youtubeBtn)
        pickFileBtn          = findViewById(R.id.pickFileBtn)
        statusText           = findViewById(R.id.statusText)
        liveTimerText        = findViewById(R.id.liveTimerText)
        bitrateText          = findViewById(R.id.bitrateText)
        cameraPreview        = findViewById(R.id.cameraPreview)
        cameraControlsLayout = findViewById(R.id.cameraControlsLayout)

        StreamingService.openGlView = cameraPreview

        // Platform Presets
        platformSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("YouTube Live", "Facebook Live", "Twitch", "Custom RTMP")
        )

        platformSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                when (pos) {
                    0 -> urlInput.setText("rtmp://a.rtmp.youtube.com/live2")
                    1 -> urlInput.setText("rtmps://live-api-s.facebook.com:443/rtmp/")
                    2 -> urlInput.setText("rtmp://live.twitch.tv/app/")
                    3 -> if (urlInput.text.toString().contains("youtube") || 
                            urlInput.text.toString().contains("facebook") || 
                            urlInput.text.toString().contains("twitch")) {
                        urlInput.setText("")
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Stream Mode
        modeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Camera Live", "Screen Live", "Video File Live")
        )

        ratioSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "16:9 Landscape (YouTube Standard)",
                "9:16 Portrait (YouTube Shorts / Vertical)"
            )
        )

        qualitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "1080p Full HD (4.5 Mbps)",
                "720p HD (2.5 Mbps - Recommended)",
                "480p SD (1.2 Mbps - Data Saver)"
            )
        )

        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val isCamera = (pos == 0)
                val isFile = (pos == 2)

                cameraPreview.visibility = if (isCamera) View.VISIBLE else View.GONE
                cameraControlsLayout.visibility = if (isCamera) View.VISIBLE else View.GONE
                pickFileBtn.visibility = if (isFile) View.VISIBLE else View.GONE
                loopCheckbox.visibility = if (isFile) View.VISIBLE else View.GONE
                noiseCancelCheckbox.visibility = if (isFile) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        urlInput.setText(prefs.rtmpUrl.ifEmpty { "rtmp://a.rtmp.youtube.com/live2" })
        keyInput.setText(prefs.streamKey)

        startBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val key = keyInput.text.toString().trim()

            if (url.isEmpty()) {
                statusText.text = "Status: Please enter an RTMP URL"
                return@setOnClickListener
            }

            if (keepScreenOnCheckbox.isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            prefs.rtmpUrl = url
            prefs.streamKey = key
            checkPermissionsAndProceed()
        }

        muteBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_TOGGLE_MUTE
            })
        }

        switchCameraBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_SWITCH_CAMERA
            })
        }

        torchBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_TOGGLE_TORCH
            })
        }

        stopBtn.setOnClickListener {
            startService(
                Intent(this, StreamingService::class.java)
                    .setAction(StreamingService.ACTION_STOP)
            )
            stopTimer()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            muteBtn.text = "Mute Mic"
            statusText.text = "Status: Stopping stream..."
        }

        youtubeBtn.setOnClickListener { 
            youtubeLauncher.launch(ytManager.signInIntent()) 
        }

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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        when (modeSpinner.selectedItemPosition) {
            0 -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    needed += Manifest.permission.CAMERA
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed += Manifest.permission.RECORD_AUDIO
                }
            }
            1 -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed += Manifest.permission.RECORD_AUDIO
                }
            }
            2 -> {
                // SAF access
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            proceedWithCurrentMode()
        }
    }

    private fun proceedWithCurrentMode() {
        when (modeSpinner.selectedItemPosition) {
            0 -> launchService(StreamMode.CAMERA)
            1 -> {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            2 -> {
                if (selectedFileUri == null) {
                    statusText.text = "Status: Pick a video file first"
                } else {
                    launchService(StreamMode.FILE)
                }
            }
        }
    }

    private fun launchService(mode: StreamMode, resultCode: Int = 0, resultData: Intent? = null) {
        val isPortrait = (ratioSpinner.selectedItemPosition == 1)
        val qualityPos = qualitySpinner.selectedItemPosition

        val (width, height, bitrate) = when (qualityPos) {
            0 -> if (isPortrait) Triple(1080, 1920, 4_500_000) else Triple(1920, 1080, 4_500_000)
            2 -> if (isPortrait) Triple(480, 854, 1_200_000)   else Triple(854, 480, 1_200_000)
            else -> if (isPortrait) Triple(720, 1280, 2_500_000) else Triple(1280, 720, 2_500_000)
        }

        val config = StreamConfig(
            mode = mode,
            rtmpUrl = urlInput.text.toString().trim(),
            streamKey = keyInput.text.toString().trim(),
            width = width,
            height = height,
            videoBitrate = bitrate,
            echoCanceler = noiseCancelCheckbox.isChecked,
            noiseSuppressor = noiseCancelCheckbox.isChecked,
            fileUri = selectedFileUri,
            loopFile = loopCheckbox.isChecked
        )

        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_CONFIG, config)
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
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
        StreamingService.openGlView = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimer()
        scope.cancel()
        super.onDestroy()
    }
}
