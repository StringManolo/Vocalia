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
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

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

    fun startListening(grammar: String? = null) {
        val m = model ?: run {
            onError?.invoke("Modelo no cargado")
            return
        }
        try {
            val recognizer = if (grammar != null) {
                Recognizer(m, 16000.0f, grammar)
            } else {
                Recognizer(m, 16000.0f)
            }
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    onPartialResult?.invoke(hypothesis ?: "")
                }
                override fun onResult(hypothesis: String?) {
                    onResult?.invoke(hypothesis ?: "")
                }
                override fun onFinalResult(hypothesis: String?) {
                    onFinalResult?.invoke(hypothesis ?: "")
                }
                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Error reconocimiento", exception)
                    onError?.invoke(exception?.message ?: "Error reconocimiento")
                }
                override fun onTimeout() {
                    Log.w(TAG, "Timeout")
                }
            })
            Log.i(TAG, "Escuchando")
        } catch (e: IOException) {
            Log.e(TAG, "Error iniciando reconocimiento", e)
            onError?.invoke("Error iniciando: ${e.message}")
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    fun shutdown() {
        stopListening()
        model?.close()
        model = null
    }
}
