package io.github.stringmanolo.vocalia

import android.app.Application
import io.github.stringmanolo.vocalia.util.CrashHandler
import io.github.stringmanolo.vocalia.util.InternalDebug

class VocaliaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Inicializar el logger a disco lo antes posible
        InternalDebug.init(this)
        InternalDebug.mark("VocaliaApp.onCreate START")

        // 2. Instalar el capturador de excepciones Java (no captura SIGSEGV)
        try {
            CrashHandler.install(this)
            InternalDebug.mark("CrashHandler installed")
        } catch (t: Throwable) {
            InternalDebug.mark("CrashHandler install FAILED: ${t.message}")
        }

        // 3. Volcar logcat del proceso anterior (para capturar mensajes nativos
        //    que sobreviven al crash de Sherpa, Vosk, espeak, etc.)
        dumpPreviousLogcat()
    }

    /**
     * Lee las últimas 800 líneas de logcat y filtra las relevantes para
     * el diagnóstico (sherpa, espeak, vocalia, SIGSEGV, Fatal signal...).
     * Las escribe en INTERNAL_DEBUG_FILE para que el usuario pueda leerlas
     * desde Termux sin necesitar adb.
     *
     * Se ejecuta en un hilo aparte para no bloquear el arranque.
     */
    private fun dumpPreviousLogcat() {
        Thread({
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800"))
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()

                val filtered = out.lines()
                    .filter { line ->
                        line.contains("sherpa", ignoreCase = true) ||
                        line.contains("espeak", ignoreCase = true) ||
                        line.contains("vocalia", ignoreCase = true) ||
                        line.contains("SIGSEGV", ignoreCase = true) ||
                        line.contains("SIGABRT", ignoreCase = true) ||
                        line.contains("Fatal signal", ignoreCase = true) ||
                        line.contains("libonnxruntime", ignoreCase = true) ||
                        line.contains("dlopen", ignoreCase = true) ||
                        line.contains("UnsatisfiedLink", ignoreCase = true)
                    }
                    .joinToString("\n")

                if (filtered.isNotBlank()) {
                    InternalDebug.mark("[LOGCAT-ANTERIOR] inicio")
                    // Una sola línea con todo el bloque, para no fragmentar el archivo
                    InternalDebug.mark(filtered)
                    InternalDebug.mark("[LOGCAT-ANTERIOR] fin")
                } else {
                    InternalDebug.mark("[LOGCAT-ANTERIOR] sin líneas relevantes")
                }
            } catch (t: Throwable) {
                InternalDebug.mark("[LOGCAT-ANTERIOR] THREW ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "logcat-dump").start()
    }
}
