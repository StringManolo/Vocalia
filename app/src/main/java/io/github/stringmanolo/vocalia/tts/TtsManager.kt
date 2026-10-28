package io.github.stringmanolo.vocalia.tts
import io.github.stringmanolo.vocalia.util.VocaliaLogger

import android.content.Context

class TtsManager(private val context: Context) {
    private var currentEngine: TtsEngine? = null
    private var defaultEngine: TtsEngine? = null
    private var fallbackEngine: TtsEngine? = null

    fun init() {
        defaultEngine = SherpaOnnxEngine(context)
        fallbackEngine = EspeakNgEngine(context) // Implementación ligera

        defaultEngine?.init(
            onReady = {
                currentEngine = defaultEngine
                VocaliaLogger.i("TtsManager", "Motor Sherpa-onnx listo")
            },
            onError = { error ->
                VocaliaLogger.e("TtsManager", "Fallo al cargar Sherpa-onnx: $error")
                switchToFallback()
            }
        )
    }

    fun speak(text: String) {
        val engine = currentEngine ?: run { switchToFallback(); return }
        // Lógica de auto-fallback si el motor neuronal tarda demasiado
        engine.speak(text)
    }

    fun switchToFallback() {
        fallbackEngine?.init(
            onReady = { currentEngine = fallbackEngine },
            onError = { VocaliaLogger.e("TtsManager", "Fallo incluso en espeak-ng: $it") }
        )
    }

    fun switchToNeural() {
        currentEngine = defaultEngine
    }
}
