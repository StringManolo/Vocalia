package io.github.stringmanolo.vocalia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.stringmanolo.vocalia.core.CommandParser
import io.github.stringmanolo.vocalia.core.VocaliaCore
import io.github.stringmanolo.vocalia.tts.TtsManager
import io.github.stringmanolo.vocalia.util.InternalDebug

class VocaliaService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vocalia_main_channel"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var ttsManager: TtsManager
    private lateinit var voskEngine: VoskEngine
    private lateinit var core: VocaliaCore

    override fun onCreate() {
        super.onCreate()
        InternalDebug.mark("Service: onCreate")

        ttsManager = TtsManager(this)
        voskEngine = VoskEngine(this)
        core = VocaliaCore(voskEngine, ttsManager, CommandParser()) { msg ->
            InternalDebug.mark("[core] $msg")
        }

        ttsManager.onEngineReady = {
            InternalDebug.mark("Service: TTS listo, arrancando core")
            core.start()
        }
        ttsManager.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        InternalDebug.mark("Service: onDestroy")
        try { core.stop() } catch (_: Exception) {}
        try { voskEngine.shutdown() } catch (_: Exception) {}
        try { ttsManager.shutdown() } catch (_: Exception) {}
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            ).apply { description = getString(R.string.notification_channel_description) }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION") Notification.Builder(this)
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
