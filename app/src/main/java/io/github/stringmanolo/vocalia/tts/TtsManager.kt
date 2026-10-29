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
    @Volatile private var sherpaLoading = false

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
            },
            onError = { error ->
                dbg("EspeakNg falló al inicializar: $error")
            }
        )
    }

    /**
     * Cambia el motor. Asíncrono para SHERPA (devuelve antes de cargar).
     * @param callback se invoca en un hilo cualquiera: postea al main si lo necesitas.
     */
    fun setEngineAsync(mode: EngineMode, callback: (Boolean, String) -> Unit) {
        when (mode) {
            EngineMode.ESPEAK -> {
                val e = espeakEngine
                if (e != null) {
                    currentEngine = e
                    currentMode = EngineMode.ESPEAK
                    dbg("Motor -> EspeakNg")
                    callback(true, "voz por defecto activada")
                } else {
                    dbg("setEngineAsync(ESPEAK) sin motor")
                    callback(false, "espeak no disponible")
                }
            }
            EngineMode.SHERPA -> {
                if (sherpaReady) {
                    currentEngine = sherpaEngine
                    currentMode = EngineMode.SHERPA
                    dbg("Motor -> SherpaOnnx (ya cargado)")
                    callback(true, "voz inteligente activada")
                    return
                }
                if (sherpaLoading) {
                    callback(false, "voz inteligente cargándose, espera")
                    return
                }
                sherpaLoading = true
                Thread({
                    try {
                        dbg("Sherpa: carga START")
                        val sherpa = SherpaOnnxEngine(context)
                        sherpa.init(
                            onReady = {
                                sherpaEngine = sherpa
                                sherpaReady = true
                                sherpaLoading = false
                                currentEngine = sherpa
                                currentMode = EngineMode.SHERPA
                                dbg("Motor -> SherpaOnnx (recién cargado)")
                                callback(true, "voz inteligente activada")
                            },
                            onError = { err ->
                                dbg("Sherpa: no disponible -> $err")
                                sherpaEngine = null
                                sherpaReady = false
                                sherpaLoading = false
                                callback(false, "voz inteligente no disponible")
                            }
                        )
                    } catch (t: Throwable) {
                        dbg("Sherpa: setEngineAsync THREW ${t.javaClass.simpleName}: ${t.message}")
                        sherpaEngine = null
                        sherpaReady = false
                        sherpaLoading = false
                        callback(false, "voz inteligente no disponible")
                    }
                }, "sherpa-load").start()
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
        sherpaLoading = false
    }
}
