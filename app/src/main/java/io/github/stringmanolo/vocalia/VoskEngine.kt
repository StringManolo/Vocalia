package io.github.stringmanolo.vocalia

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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
        private const val DEFAULT_GRAMMAR = """["asistente", "asistentes", "[unk]"]"""
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    @Volatile private var currentGrammar: String = DEFAULT_GRAMMAR

    var onResult: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { onPartialResult?.invoke(hypothesis ?: "") }
        override fun onResult(hypothesis: String?) { onResult?.invoke(hypothesis ?: "") }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Error reconocimiento", exception)
            onError?.invoke(exception?.message ?: "Error")
        }
        override fun onTimeout() {}
    }

    fun init(grammar: String, onReady: () -> Unit) {
        currentGrammar = grammar
        StorageService.unpack(
            context,
            "vosk-model-small-es-0.42",
            "model",
            { loadedModel ->
                model = loadedModel
                Log.i(TAG, "Modelo cargado, grammar de ${grammar.length} chars")
                onReady()
            },
            { exception ->
                Log.e(TAG, "Error cargando modelo", exception)
                onError?.invoke("Error cargando modelo: ${exception.message}")
            }
        )
    }

    fun startListening() {
        if (speechService != null) return
        val m = model ?: run {
            Log.w(TAG, "startListening sin modelo")
            return
        }
        try {
            val recognizer = Recognizer(m, 16000.0f, currentGrammar)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(listener)
            Log.i(TAG, "startListening OK")
        } catch (e: IOException) {
            Log.e(TAG, "startListening falló", e)
            onError?.invoke("Error startListening: ${e.message}")
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

    fun beep() {
        Thread {
            var tg: ToneGenerator? = null
            try {
                tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                Thread.sleep(150)
            } catch (_: Throwable) {} finally {
                try { tg?.release() } catch (_: Throwable) {}
            }
        }.start()
    }
}
