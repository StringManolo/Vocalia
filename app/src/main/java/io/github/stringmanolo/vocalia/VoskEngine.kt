package io.github.stringmanolo.vocalia

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskEngine(private val context: Context) {
    private var model: Model? = null
    private var speechService: SpeechService? = null

    // Callback para enviar resultados a quien corresponda (servicio, actividad...)
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun init() {
        // Cargar el modelo desde assets en un hilo secundario
        StorageService.unpack(context, "vosk-model-small-es-0.42", "model",
            { loadedModel ->
                model = loadedModel
                // Una vez cargado, podríamos iniciar la escucha si quisiéramos
            },
            { exception ->
                onError?.invoke("Error al cargar el modelo: ${exception.message}")
            })
    }

    fun startListening(grammar: String? = null) {
        model?.let { m ->
            try {
                val recognizer = if (grammar != null) {
                    Recognizer(m, 16000.0f, grammar) // Gramática para comandos
                } else {
                    Recognizer(m, 16000.0f) // Dictado libre
                }
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening { result ->
                    // 'result' es un JSON con el texto reconocido
                    onResult?.invoke(result)
                }
            } catch (e: IOException) {
                onError?.invoke("Error al iniciar el reconocimiento: ${e.message}")
            }
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
