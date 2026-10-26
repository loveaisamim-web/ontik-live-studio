package com.ontik.livestudio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var youtubeBtn: Button
    private lateinit var statusText: TextView

    private val prefs by lazy { SecurePrefs(this) }
    private val ytManager by lazy { YouTubeAuthManager(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { requestPermissionsAndStart() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            MediaProjectionHolder.projection =
                mpm.getMediaProjection(result.resultCode, result.data)
            launchService(StreamMode.SCREEN)
        } else statusText.text = "Screen capture denied"
    }

    private val youtubeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            statusText.text = "Creating YouTube live stream…"
            scope.launch {
                val r = ytManager.createLiveStream()
                if (r != null) {
                    urlInput.setText(r.first)
                    keyInput.setText(r.second)
                    prefs.rtmpUrl = r.first; prefs.streamKey = r.second
                    statusText.text = "YouTube stream ready"
                } else statusText.text = "YouTube auth failed"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeSpinner = findViewById(R.id.modeSpinner)
        urlInput    = findViewById(R.id.urlInput)
        keyInput    = findViewById(R.id.keyInput)
        startBtn    = findViewById(R.id.startBtn)
        stopBtn     = findViewById(R.id.stopBtn)
        youtubeBtn  = findViewById(R.id.youtubeBtn)
        statusText  = findViewById(R.id.statusText)

        modeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Camera", "Screen", "Video file")
        )
        urlInput.setText(prefs.rtmpUrl)
        keyInput.setText(prefs.streamKey)

        startBtn.setOnClickListener {
            prefs.rtmpUrl = urlInput.text.toString().trim()
            prefs.streamKey = keyInput.text.toString().trim()
            requestPermissionsAndStart()
        }
        stopBtn.setOnClickListener {
            startService(Intent(this, StreamingService::class.java)
                .setAction(StreamingService.ACTION_STOP))
        }
        youtubeBtn.setOnClickListener { youtubeLauncher.launch(ytManager.signInIntent()) }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return
        }

        when (modeSpinner.selectedItemPosition) {
            0 -> launchService(StreamMode.CAMERA)
            1 -> {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            2 -> launchService(StreamMode.FILE)
        }
    }

    private fun launchService(mode: StreamMode) {
        val config = StreamConfig(
            mode = mode,
            rtmpUrl = urlInput.text.toString().trim(),
            streamKey = keyInput.text.toString().trim()
        )
        val intent = Intent(this, StreamingService::class.java)
            .setAction(StreamingService.ACTION_START)
            .putExtra(StreamingService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Streaming ${mode.name.lowercase()}…"
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
