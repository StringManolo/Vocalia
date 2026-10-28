package io.github.stringmanolo.vocalia.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                ).format(Date())
                val entry = "=== CRASH $timestamp (thread: ${thread.name}) ===\n${sw}\n\n"

                // Interno (siempre accesible por la app)
                try {
                    File(appContext.filesDir, "crash.log").appendText(entry)
                } catch (_: Throwable) {}

                // Externo (accesible desde Termux en Android 10)
                try {
                    val extDir = appContext.getExternalFilesDir("logs")
                    extDir?.mkdirs()
                    File(extDir, "crash.log").appendText(entry)
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
