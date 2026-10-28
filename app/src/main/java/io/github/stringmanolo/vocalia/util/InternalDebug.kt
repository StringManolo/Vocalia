package io.github.stringmanolo.vocalia.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger a disco que sobrevive a crashes nativos (SIGSEGV, SIGABRT).
 *
 * Escribe de forma SÍNCRONA (appendText cierra el FileOutputStream antes
 * de retornar, así que el contenido llega al page cache del kernel y
 * sobrevive a la muerte del proceso).
 *
 * Uso:
 *   InternalDebug.init(context)   // una vez, al arrancar la app
 *   InternalDebug.mark("label")   // N veces, en cualquier sitio
 */
object InternalDebug {

    private const val FILE_NAME = "INTERNAL_DEBUG_FILE"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            mark("=== InternalDebug initialized (pid=${android.os.Process.myPid()}) ===")
        }
    }

    fun mark(label: String) {
        val ctx = appContext ?: return
        val line = "${dateFormat.format(Date())} [pid=${android.os.Process.myPid()}] $label\n"

        // 1) Almacenamiento interno: siempre accesible con run-as
        try {
            File(ctx.filesDir, FILE_NAME).appendText(line)
        } catch (_: Throwable) {}

        // 2) Almacenamiento externo de la app: accesible desde Termux en Android 10
        try {
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) {
                if (!ext.exists()) ext.mkdirs()
                File(ext, FILE_NAME).appendText(line)
            }
        } catch (_: Throwable) {}
    }

    /** Borra el archivo. Útil antes de cada test. */
    fun clear() {
        val ctx = appContext ?: return
        try { File(ctx.filesDir, FILE_NAME).delete() } catch (_: Throwable) {}
        try { ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME).delete() } } catch (_: Throwable) {}
    }
}
