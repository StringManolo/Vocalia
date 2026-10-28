package io.github.stringmanolo.vocalia

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i("BootReceiver", "Boot event: $action")

        // En Android 12+ no se puede arrancar un FGS directamente desde BOOT_COMPLETED.
        // Programamos una alarma ~5 s después, y esa alarma (WatchdogReceiver) sí arranca el servicio.
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + 5_000L
        val pi = WatchdogReceiver.pendingIntent(context, WatchdogReceiver.ACTION_BOOT_START, 100)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)

        // Arrancamos también el ciclo del watchdog
        WatchdogReceiver.scheduleWatchdog(context)
    }
}
