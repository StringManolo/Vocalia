package io.github.stringmanolo.vocalia

import android.content.Context
import io.github.stringmanolo.vocalia.util.VocaliaLogger
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskEngine(private val context: Context) {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var currentRecognizer: Recognizer? = null

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun init() {
        StorageService.unpack(
            context,
            "vosk-model-small-es-0.42",
            "model",
            { loadedModel ->
                model = loadedModel
                VocaliaLogger.i("VoskEngine", "Modelo Vosk cargado")
            },
            { exception ->
                VocaliaLogger.e("VoskEngine", "Error al cargar el modelo", exception)
                onError?.invoke("Error al cargar el modelo: ${exception.message}")
            }
        )
    }

    fun startListening(grammar: String? = null) {
        val m = model
        if (m == null) {
            onError?.invoke("Modelo no cargado todavía")
            return
        }
        try {
            val recognizer = if (grammar != null) {
                Recognizer(m, 16000.0f, grammar)
            } else {
                Recognizer(m, 16000.0f)
            }
            currentRecognizer = recognizer
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
                    VocaliaLogger.e("VoskEngine", "Error de reconocimiento", exception)
                    onError?.invoke(exception?.message ?: "Error de reconocimiento")
                }
                override fun onTimeout() {
                    VocaliaLogger.w("VoskEngine", "Timeout de reconocimiento")
                }
            })
            VocaliaLogger.i("VoskEngine", "Escuchando (grammar=${grammar != null})")
        } catch (e: IOException) {
            VocaliaLogger.e("VoskEngine", "Error al iniciar el reconocimiento", e)
            onError?.invoke("Error al iniciar: ${e.message}")
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
        currentRecognizer?.close()
        currentRecognizer = null
    }

    fun shutdown() {
        stopListening()
        model?.close()
        model = null
    }
}
