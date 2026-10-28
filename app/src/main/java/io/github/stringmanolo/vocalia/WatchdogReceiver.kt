package io.github.stringmanolo.vocalia

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "WatchdogReceiver"
        const val ACTION_BOOT_START = "io.github.stringmanolo.vocalia.action.BOOT_START"
        const val ACTION_WATCHDOG   = "io.github.stringmanolo.vocalia.action.WATCHDOG"
        private const val REQUEST_BOOT = 100
        private const val REQUEST_WATCHDOG = 101
        // setAndAllowWhileIdle respeta como mínimo ~60 s entre disparos.
        private const val WATCHDOG_INTERVAL_MS = 59_000L

        fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            val pi = pendingIntent(context, ACTION_WATCHDOG, REQUEST_WATCHDOG)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }

        private fun startServiceSafely(context: Context) {
            try {
                val intent = Intent(context, VocaliaService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ puede lanzar ForegroundServiceStartNotAllowedException
                Log.w(TAG, "No se pudo arrancar el servicio: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BOOT_START -> {
                Log.i(TAG, "Arranque post-boot")
                if (!VocaliaService.isRunning) startServiceSafely(context)
                scheduleWatchdog(context)
            }
            ACTION_WATCHDOG -> {
                Log.d(TAG, "Tick watchdog (running=${VocaliaService.isRunning})")
                if (!VocaliaService.isRunning) startServiceSafely(context)
                scheduleWatchdog(context)
            }
        }
    }
}
