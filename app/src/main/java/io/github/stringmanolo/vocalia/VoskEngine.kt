package io.github.stringmanolo.vocalia

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskEngine"
        //private const val WAKE_GRAMMAR = """["vocalia", "[unk]"]"""
        private const val WAKE_GRAMMAR = """["asistente", "asistentes", "[unk]"]"""
    }

    enum class Mode { WAKE, COMMAND }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var currentMode: Mode? = null

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            onPartialResult?.invoke(hypothesis ?: "")
        }
        override fun onResult(hypothesis: String?) {
            onResult?.invoke(hypothesis ?: "")
        }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Error reconocimiento", exception)
            onError?.invoke(exception?.message ?: "Error")
        }
        override fun onTimeout() {}
    }

    fun init(onReady: () -> Unit) {
        StorageService.unpack(
            context,
            "vosk-model-small-es-0.42",
            "model",
            { loadedModel ->
                model = loadedModel
                Log.i(TAG, "Modelo cargado")
                onReady()
            },
            { exception ->
                Log.e(TAG, "Error cargando modelo", exception)
                onError?.invoke("Error cargando modelo: ${exception.message}")
            }
        )
    }

    /** Cambia la gramática activa. Recrea Recognizer+SpeechService. Idempotente. */
    fun setMode(mode: Mode) {
        if (currentMode == mode) return
        stopListening()

        val m = model ?: run {
            Log.w(TAG, "setMode sin modelo")
            return
        }
        try {
            val recognizer = when (mode) {
                Mode.WAKE -> Recognizer(m, 16000.0f, WAKE_GRAMMAR)
                Mode.COMMAND -> Recognizer(m, 16000.0f)
            }
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(listener)
            currentMode = mode
            Log.i(TAG, "setMode($mode)")
        } catch (e: IOException) {
            Log.e(TAG, "setMode($mode) falló", e)
            onError?.invoke("Error setMode: ${e.message}")
        }
    }

    /** Pausa el reconocimiento sin descargar el modelo. */
    fun pause() {
        stopListening()
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
        currentMode = null
    }

    fun shutdown() {
        stopListening()
        model?.close()
        model = null
    }
}
