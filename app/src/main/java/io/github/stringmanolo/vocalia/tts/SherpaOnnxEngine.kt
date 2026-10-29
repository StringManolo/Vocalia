package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class SherpaOnnxEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val TAG = "SherpaOnnxEngine"
    }

    override val name = "SherpaOnnx"
    @Volatile override var onComplete: (() -> Unit)? = null

    private var tts: OfflineTts? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var shuttingDown = false
    @Volatile private var currentSpeed = 1.0f

    // ---------------------------------------------------------------
    // init
    // ---------------------------------------------------------------

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
            Log.i(TAG, "Sherpa-onnx inicializado")
            onReady()
        } catch (t: Throwable) {
            Log.e(TAG, "init falló: ${t.message}", t)
            onError(t.message ?: "Error desconocido en SherpaOnnx")
        }
    }

    // ---------------------------------------------------------------
    // speak / stop / shutdown
    // ---------------------------------------------------------------

    override fun speak(text: String, interruptible: Boolean) {
        if (shuttingDown) {
            notifyComplete()
            return
        }
        val engine = tts
        if (engine == null) {
            Log.w(TAG, "speak sin modelo cargado")
            notifyComplete()
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val audio = engine.generate(text, 0, currentSpeed)
                if (audio.samples.isNotEmpty()) {
                    playAudio(audio.samples, audio.sampleRate)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "speak cancelado")
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "speak falló: ${t.message}", t)
            } finally {
                notifyComplete()
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "stop()")
        currentJob?.cancel()
        currentJob = null
        try { currentTrack?.stop() } catch (_: Throwable) {}
        try { currentTrack?.release() } catch (_: Throwable) {}
        currentTrack = null
        // Desbloquear al orquestador si estaba esperando el callback.
        notifyComplete()
    }

    override fun setSpeed(rate: Float) {
        currentSpeed = rate.coerceIn(0.5f, 2.0f)
    }

    override fun shutdown() {
        Log.d(TAG, "shutdown()")
        shuttingDown = true
        stop()
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        scope.cancel()
    }

    // ---------------------------------------------------------------
    // Reproducción
    // ---------------------------------------------------------------

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        var track: AudioTrack? = null
        try {
            val bufferSize = samples.size * 4 // float = 4 bytes/muestra
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
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

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack no inicializado (state=${track.state})")
                return
            }

            currentTrack = track
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Log.d(TAG, "playAudio: ${samples.size} muestras a ${sampleRate}Hz")

            // Esperar a que el audio se consuma realmente antes de liberar el track.
            val frames = samples.size
            val expectedMs = frames * 1000L / sampleRate
            val deadline = System.currentTimeMillis() + expectedMs + 2000L
            while (System.currentTimeMillis() < deadline) {
                if (shuttingDown) break
                if (track.playbackHeadPosition >= frames) break
                delay(40)
            }
            // Margen para el HAL
            delay(100)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "playAudio falló: ${t.message}", t)
        } finally {
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            if (currentTrack === track) currentTrack = null
        }
    }

    // ---------------------------------------------------------------
    // Utilidad
    // ---------------------------------------------------------------

    private fun notifyComplete() {
        val cb = onComplete
        onComplete = null
        cb?.invoke()
    }
}
