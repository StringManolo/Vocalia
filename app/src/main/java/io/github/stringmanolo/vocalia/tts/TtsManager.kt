package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.util.Log

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    enum class EngineMode { ESPEAK, SHERPA }

    var onEngineReady: ((TtsEngine) -> Unit)? = null

    @Volatile private var currentMode: EngineMode = EngineMode.ESPEAK
    @Volatile private var currentEngine: TtsEngine? = null

    private var espeakEngine: EspeakNgEngine? = null
    private var sherpaEngine: SherpaOnnxEngine? = null

    @Volatile private var sherpaReady = false
    @Volatile private var sherpaInitAttempted = false

    fun init() {
        val espeak = EspeakNgEngine(context)
        espeakEngine = espeak
        espeak.init(
            onReady = {
                Log.i(TAG, "EspeakNg listo (default)")
                currentEngine = espeak
                currentMode = EngineMode.ESPEAK
                onEngineReady?.invoke(espeak)
                tryInitSherpaInBackground()
            },
            onError = { error ->
                Log.e(TAG, "EspeakNg falló al inicializar: $error")
            }
        )
    }

    /** Intenta cargar Sherpa en segundo plano. No bloquea el arranque ni lo activa. */
    private fun tryInitSherpaInBackground() {
        if (sherpaInitAttempted) return
        sherpaInitAttempted = true
        Thread({
            try {
                Log.i(TAG, "Sherpa: init en segundo plano")
                val sherpa = SherpaOnnxEngine(context)
                sherpaEngine = sherpa
                sherpa.init(
                    onReady = {
                        sherpaReady = true
                        Log.i(TAG, "Sherpa: listo (no activado)")
                    },
                    onError = { err ->
                        Log.e(TAG, "Sherpa: no disponible -> $err")
                        sherpaEngine = null
                        sherpaReady = false
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Sherpa: init THREW", t)
                sherpaEngine = null
                sherpaReady = false
            }
        }, "sherpa-init").start()
    }

    /**
     * Cambia el motor activo.
     * @return (éxito, mensaje para el usuario).
     */
    fun setEngine(mode: EngineMode): Pair<Boolean, String> {
        return when (mode) {
            EngineMode.ESPEAK -> {
                val e = espeakEngine
                if (e != null) {
                    currentEngine = e
                    currentMode = EngineMode.ESPEAK
                    Log.i(TAG, "Motor cambiado a EspeakNg")
                    true to "voz por defecto activada"
                } else {
                    Log.e(TAG, "setEngine(ESPEAK) sin motor")
                    false to "espeak no disponible"
                }
            }
            EngineMode.SHERPA -> {
                val s = sherpaEngine
                if (sherpaReady && s != null) {
                    currentEngine = s
                    currentMode = EngineMode.SHERPA
                    Log.i(TAG, "Motor cambiado a SherpaOnnx")
                    true to "voz inteligente activada"
                } else {
                    Log.w(TAG, "setEngine(SHERPA) no listo")
                    false to "voz inteligente no disponible"
                }
            }
        }
    }

    fun currentMode(): EngineMode = currentMode

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val engine = currentEngine
        if (engine == null) {
            Log.w(TAG, "speak sin motor")
            onComplete?.invoke()
            return
        }
        engine.onComplete = onComplete
        engine.speak(text)
    }

    fun stop() {
        currentEngine?.onComplete = null
        currentEngine?.stop()
    }

    fun shutdown() {
        Log.i(TAG, "shutdown")
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        espeakEngine = null
        sherpaEngine = null
        sherpaReady = false
    }
}
