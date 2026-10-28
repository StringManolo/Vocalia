package io.github.stringmanolo.vocalia.tts

import android.content.Context
import io.github.stringmanolo.vocalia.util.VocaliaLogger

class TtsManager(private val context: Context) {

    /** Se dispara cuando el primer motor está listo para hablar. */
    var onEngineReady: ((TtsEngine) -> Unit)? = null

    private var currentEngine: TtsEngine? = null
    private var sherpaEngine: TtsEngine? = null
    private var espeakEngine: TtsEngine? = null

    private val pendingUtterances = mutableListOf<String>()

    fun init() {
        // 1. Inicializar primero espeak-ng (rápido y fiable).
        espeakEngine = EspeakNgEngine(context)
        espeakEngine?.init(
            onReady = {
                VocaliaLogger.i("TtsManager", "EspeakNg listo")
                currentEngine = espeakEngine
                flushPending()
                onEngineReady?.invoke(espeakEngine!!)
                // 2. Intentar Sherpa en segundo plano (no bloquea).
                //tryInitSherpa()
            },
            onError = { error ->
                VocaliaLogger.e("TtsManager", "EspeakNg falló al inicializar: $error")
            }
        )
    }

    private fun tryInitSherpa() {
        sherpaEngine = SherpaOnnxEngine(context)
        sherpaEngine?.init(
            onReady = {
                VocaliaLogger.i("TtsManager", "SherpaOnnx listo; cambiando a motor neuronal")
                currentEngine = sherpaEngine
            },
            onError = { error ->
                VocaliaLogger.w("TtsManager", "SherpaOnnx no disponible, seguimos con espeak: $error")
            }
        )
    }

    fun speak(text: String) {
        val engine = currentEngine
        if (engine == null) {
            VocaliaLogger.d("TtsManager", "Motor aún no listo, encolando: $text")
            pendingUtterances.add(text)
            return
        }
        engine.speak(text)
    }

    fun stop() {
        currentEngine?.stop()
    }

    fun shutdown() {
        try { currentEngine?.shutdown() } catch (_: Exception) {}
        if (sherpaEngine !== currentEngine) try { sherpaEngine?.shutdown() } catch (_: Exception) {}
        if (espeakEngine !== currentEngine) try { espeakEngine?.shutdown() } catch (_: Exception) {}
        currentEngine = null
        sherpaEngine = null
        espeakEngine = null
        pendingUtterances.clear()
    }

    private fun flushPending() {
        if (pendingUtterances.isEmpty()) return
        val queue = pendingUtterances.toList()
        pendingUtterances.clear()
        queue.forEach { currentEngine?.speak(it) }
    }
}
