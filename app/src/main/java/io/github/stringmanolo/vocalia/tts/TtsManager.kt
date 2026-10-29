package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.util.Log

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        const val ENABLE_SHERPA = false
    }

    var onEngineReady: ((TtsEngine) -> Unit)? = null

    @Volatile private var currentEngine: TtsEngine? = null
    private var sherpaEngine: TtsEngine? = null
    private var espeakEngine: TtsEngine? = null

    private data class Pending(val text: String, val onComplete: (() -> Unit)?)
    private val pendingUtterances = mutableListOf<Pending>()

    fun init() {
        val engine = EspeakNgEngine(context)
        espeakEngine = engine
        engine.init(
            onReady = {
                currentEngine = engine
                flushPending()
                onEngineReady?.invoke(engine)
            },
            onError = { error ->
                Log.e(TAG, "EspeakNg falló: $error")
            }
        )
    }

    /** Sin callback: para usos que no necesitan saber cuándo termina. */
    fun speak(text: String) = speak(text, null)

    /** Con callback: se invoca cuando la locución termina o se cancela. */
    fun speak(text: String, onComplete: (() -> Unit)?) {
        val engine = currentEngine
        if (engine == null) {
            pendingUtterances.add(Pending(text, onComplete))
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
        currentEngine?.onComplete = null
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        sherpaEngine = null
        espeakEngine = null
        pendingUtterances.clear()
    }

    private fun flushPending() {
        if (pendingUtterances.isEmpty()) return
        val queue = pendingUtterances.toList()
        pendingUtterances.clear()
        queue.forEach { (text, cb) ->
            currentEngine?.onComplete = cb
            currentEngine?.speak(text)
        }
    }
}
