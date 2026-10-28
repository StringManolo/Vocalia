package io.github.stringmanolo.vocalia.tts

import android.content.Context
import io.github.stringmanolo.vocalia.util.InternalDebug
import io.github.stringmanolo.vocalia.util.VocaliaLogger

class TtsManager(private val context: Context) {

    companion object {
        /**
         * Sherpa/Piper desactivado mientras se depura el audio de espeak.
         * Antes se iniciaba SIEMPRE, en el hilo principal, justo tras el primer speak,
         * y la cola de liberación del audio se retrasaba ~9 s.
         */
        const val ENABLE_SHERPA = false
    }

    /** Se dispara cuando el primer motor está listo para hablar. */
    var onEngineReady: ((TtsEngine) -> Unit)? = null

    @Volatile private var currentEngine: TtsEngine? = null
    private var sherpaEngine: TtsEngine? = null
    private var espeakEngine: TtsEngine? = null

    private val pendingUtterances = mutableListOf<String>()

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] TtsManager: $msg")
    }

    fun init() {
        dbg("init: ENABLE_SHERPA=$ENABLE_SHERPA; creando EspeakNgEngine")
        val engine = EspeakNgEngine(context)
        espeakEngine = engine
        engine.init(
            onReady = {
                dbg("EspeakNg onReady recibido")
                VocaliaLogger.i("TtsManager", "EspeakNg listo")
                currentEngine = engine
                flushPending()
                onEngineReady?.invoke(engine)
                if (ENABLE_SHERPA) {
                    tryInitSherpa()
                } else {
                    dbg("Sherpa desactivado (ENABLE_SHERPA=false)")
                }
            },
            onError = { error ->
                dbg("EspeakNg onError: $error")
                VocaliaLogger.e("TtsManager", "EspeakNg falló al inicializar: $error")
            }
        )
    }

    /** Sherpa se inicia en un hilo propio: nunca en el hilo principal. */
    private fun tryInitSherpa() {
        Thread({
            try {
                dbg("Sherpa: init START")
                val engine = SherpaOnnxEngine(context)
                sherpaEngine = engine
                engine.init(
                    onReady = {
                        dbg("Sherpa onReady; cambiando a motor neuronal")
                        VocaliaLogger.i("TtsManager", "SherpaOnnx listo; cambiando a motor neuronal")
                        currentEngine = engine
                    },
                    onError = { error ->
                        dbg("Sherpa onError: $error")
                        VocaliaLogger.w("TtsManager", "SherpaOnnx no disponible, seguimos con espeak: $error")
                    }
                )
            } catch (t: Throwable) {
                dbg("Sherpa: THREW ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "sherpa-init").start()
    }

    fun speak(text: String) {
        val engine = currentEngine
        if (engine == null) {
            dbg("speak: motor aún no listo, encolando \"$text\"")
            VocaliaLogger.d("TtsManager", "Motor aún no listo, encolando: $text")
            pendingUtterances.add(text)
            return
        }
        dbg("speak: \"$text\" -> ${engine.name}")
        engine.speak(text)
    }

    fun stop() {
        dbg("stop")
        currentEngine?.stop()
    }

    fun shutdown() {
        dbg("shutdown")
        try { espeakEngine?.shutdown() } catch (t: Throwable) { dbg("shutdown espeak THREW ${t.message}") }
        try { sherpaEngine?.shutdown() } catch (t: Throwable) { dbg("shutdown sherpa THREW ${t.message}") }
        currentEngine = null
        sherpaEngine = null
        espeakEngine = null
        pendingUtterances.clear()
    }

    private fun flushPending() {
        if (pendingUtterances.isEmpty()) return
        val queue = pendingUtterances.toList()
        pendingUtterances.clear()
        dbg("flushPending: ${queue.size} locución(es)")
        queue.forEach { currentEngine?.speak(it) }
    }
}
