package io.github.stringmanolo.vocalia.tts

import android.content.Context
import io.github.stringmanolo.vocalia.util.InternalDebug

class TtsManager(private val context: Context) {

    enum class EngineMode { ESPEAK, SHERPA }

    var onEngineReady: ((TtsEngine) -> Unit)? = null

    @Volatile private var currentMode: EngineMode = EngineMode.ESPEAK
    @Volatile private var currentEngine: TtsEngine? = null

    private var espeakEngine: EspeakNgEngine? = null
    private var sherpaEngine: SherpaOnnxEngine? = null

    @Volatile private var sherpaReady = false
    @Volatile private var sherpaInitAttempted = false

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] TtsManager: $msg")
    }

    fun init() {
        val espeak = EspeakNgEngine(context)
        espeakEngine = espeak
        espeak.init(
            onReady = {
                dbg("EspeakNg listo (default)")
                currentEngine = espeak
                currentMode = EngineMode.ESPEAK
                onEngineReady?.invoke(espeak)
                tryInitSherpaInBackground()
            },
            onError = { error ->
                dbg("EspeakNg falló al inicializar: $error")
            }
        )
    }

    private fun tryInitSherpaInBackground() {
        if (sherpaInitAttempted) return
        sherpaInitAttempted = true
        Thread({
            try {
                dbg("Sherpa: init en segundo plano START")
                val sherpa = SherpaOnnxEngine(context)
                sherpaEngine = sherpa
                sherpa.init(
                    onReady = {
                        sherpaReady = true
                        dbg("Sherpa: listo (no activado)")
                    },
                    onError = { err ->
                        dbg("Sherpa: no disponible -> $err")
                        sherpaEngine = null
                        sherpaReady = false
                    }
                )
            } catch (t: Throwable) {
                dbg("Sherpa: init THREW ${t.javaClass.simpleName}: ${t.message}")
                sherpaEngine = null
                sherpaReady = false
            }
        }, "sherpa-init").start()
    }

    fun setEngine(mode: EngineMode): Pair<Boolean, String> {
        return when (mode) {
            EngineMode.ESPEAK -> {
                val e = espeakEngine
                if (e != null) {
                    currentEngine = e
                    currentMode = EngineMode.ESPEAK
                    dbg("Motor cambiado a EspeakNg")
                    true to "voz por defecto activada"
                } else {
                    dbg("setEngine(ESPEAK) sin motor")
                    false to "espeak no disponible"
                }
            }
            EngineMode.SHERPA -> {
                val s = sherpaEngine
                if (sherpaReady && s != null) {
                    currentEngine = s
                    currentMode = EngineMode.SHERPA
                    dbg("Motor cambiado a SherpaOnnx")
                    true to "voz inteligente activada"
                } else {
                    dbg("setEngine(SHERPA) no listo (ready=$sherpaReady, engine=${s != null})")
                    false to "voz inteligente no disponible"
                }
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val engine = currentEngine
        if (engine == null) {
            dbg("speak sin motor")
            onComplete?.invoke()
            return
        }
        dbg("speak(\"$text\") con ${engine.name}")
        engine.onComplete = onComplete
        engine.speak(text)
    }

    fun stop() {
        currentEngine?.onComplete = null
        currentEngine?.stop()
    }

    fun shutdown() {
        dbg("shutdown")
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        espeakEngine = null
        sherpaEngine = null
        sherpaReady = false
    }
}
