package io.github.stringmanolo.vocalia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.stringmanolo.vocalia.tts.TtsManager
import io.github.stringmanolo.vocalia.util.InternalDebug
import io.github.stringmanolo.vocalia.util.VocaliaLogger

class VocaliaService : Service() {

    companion object {
        private const val TAG = "VocaliaService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vocalia_main_channel"
        private const val HEARTBEAT_MS = 500L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var ttsManager: TtsManager

    // Latido del hilo principal: si algo lo bloquea, aparece "MAIN-THREAD STALL" en el log.
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var heartbeatOn = false
    private var lastBeat = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!heartbeatOn) return
            val now = SystemClock.elapsedRealtime()
            if (lastBeat != 0L) {
                val late = now - lastBeat - HEARTBEAT_MS
                if (late > 300) {
                    InternalDebug.mark("MAIN-THREAD STALL: el hilo principal estuvo bloqueado ~${late}ms")
                }
            }
            lastBeat = now
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] Service: $msg")
    }

    override fun onCreate() {
        super.onCreate()
        dbg("onCreate START")
        VocaliaLogger.i(TAG, "onCreate")

        heartbeatOn = true
        lastBeat = 0L
        mainHandler.post(heartbeat)

        ttsManager = TtsManager(this)
        ttsManager.onEngineReady = { engine ->
            dbg("onEngineReady: ${engine.name} -> speak(\"Vocalia activa\")")
            VocaliaLogger.i(TAG, "Motor TTS listo: ${engine.name}")
            ttsManager.speak("Vocalia activa")
        }
        ttsManager.init()
        dbg("onCreate END")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dbg("onStartCommand startId=$startId")
        VocaliaLogger.i(TAG, "onStartCommand")
        startForegroundCompat()
        isRunning = true
        dbg("onStartCommand: foreground OK")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dbg("onDestroy")
        VocaliaLogger.i(TAG, "onDestroy")
        heartbeatOn = false
        mainHandler.removeCallbacks(heartbeat)
        try { ttsManager.shutdown() } catch (_: Exception) {}
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
