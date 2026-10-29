package io.github.stringmanolo.vocalia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        requestIgnoreBatteryOptimizations()
        requestRecordAudioPermissionIfNeeded()

        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton  = findViewById<Button>(R.id.stopButton)
        findViewById<Button>(R.id.showLogsButton).setOnClickListener {
          startActivity(Intent(this, LogActivity::class.java))
        }

        startButton.setOnClickListener {
          val intent = Intent(this, VocaliaService::class.java)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.postDelayed({ updateStatus() }, 300)
        }
        stopButton.setOnClickListener {
            stopService(Intent(this, VocaliaService::class.java))
            statusText.postDelayed({ updateStatus() }, 300)
        }

        requestNotificationPermissionIfNeeded()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = getString(
            if (VocaliaService.isRunning) R.string.status_running else R.string.status_stopped
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun requestRecordAudioPermissionIfNeeded() {
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            201
        )
    }
}

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    200
                )
            }
        }
    }
}
