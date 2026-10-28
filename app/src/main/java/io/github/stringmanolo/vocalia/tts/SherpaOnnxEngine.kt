package io.github.stringmanolo.vocalia.tts
import io.github.stringmanolo.vocalia.util.VocaliaLogger

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*

class SherpaOnnxEngine(private val context: Context) : TtsEngine {
    override val name = "SherpaOnnx"
    private var tts: OfflineTts? = null
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "tts/vits-piper-es_ES-miro-high/es_ES-miro-high.onnx",
                        tokens = "tts/vits-piper-es_ES-miro-high/tokens.txt",
                        dataDir = "tts/vits-piper-es_ES-miro-high/espeak-ng-data"
                    ),
                    numThreads = 2
                )
            )
            tts = OfflineTts(context.assets, config)
            onReady()
        } catch (e: Exception) {
            onError(e.message ?: "Error desconocido en SherpaOnnx")
        }
    }

    override fun speak(text: String, interruptible: Boolean) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val audio = tts?.generate(text, 0, 1.0f) ?: return@launch
                playAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                VocaliaLogger.e("SherpaOnnxEngine", "Error al sintetizar: ${e.message}")
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val bufferSize = samples.size * 4
        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        audioTrack.play()
        // Liberar recursos después de reproducir (puedes usar un listener para onPlaybackPositionUpdate)
    }

    override fun stop() { currentJob?.cancel() }
    override fun setSpeed(rate: Float) { /* Sherpa-onnx permite ajustar la velocidad en generate() */ }
    override fun shutdown() { scope.cancel(); tts?.release() }
}
