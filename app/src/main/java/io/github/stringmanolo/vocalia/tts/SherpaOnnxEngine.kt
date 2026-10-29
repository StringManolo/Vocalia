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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            Log.i(TAG, "init: construyendo OfflineTts")
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
            val instance = OfflineTts(context.assets, config)
            tts = instance
            Log.i(TAG, "init: OfflineTts creado")

            // Prueba de humo: generar un texto corto para verificar que el modelo funciona.
            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    val test = instance.generate("prueba", 0, 1.0f)
                    val dt = System.currentTimeMillis() - t0
                    Log.i(TAG, "init smoke test: samples=${test.samples.size} rate=${test.sampleRate} took=${dt}ms")
                    if (test.samples.isNotEmpty()) {
                        var peak = 0f
                        for (s in test.samples) if (abs(s) > peak) peak = abs(s)
                        Log.i(TAG, "init smoke test: peak=$peak")
                    } else {
                        Log.e(TAG, "init smoke test: generate() devuelve 0 muestras. El modelo NO funciona.")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "init smoke test THREW", t)
                }
            }, "sherpa-smoke").start()

            onReady()
        } catch (t: Throwable) {
            Log.e(TAG, "init falló: ${t.message}", t)
            onError(t.message ?: "Error desconocido en SherpaOnnx")
        }
    }

    override fun speak(text: String, interruptible: Boolean) {
        if (shuttingDown) {
            notifyComplete()
            return
        }
        val engine = tts
        if (engine == null) {
            Log.w(TAG, "speak sin modelo")
            notifyComplete()
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                Log.i(TAG, "speak(\"$text\") generating...")
                val t0 = System.currentTimeMillis()
                val audio = engine.generate(text, 0, currentSpeed)
                val dt = System.currentTimeMillis() - t0

                var peak = 0f
                for (s in audio.samples) if (abs(s) > peak) peak = abs(s)

                Log.i(TAG, "generate: samples=${audio.samples.size} rate=${audio.sampleRate} peak=$peak took=${dt}ms")

                if (audio.samples.isNotEmpty() && peak > 0f) {
                    playAudio(audio.samples, audio.sampleRate)
                } else {
                    Log.e(TAG, "generate devolvió audio vacío o silencio (samples=${audio.samples.size}, peak=$peak)")
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

    /**
     * Reproduce el audio de Sherpa con el patrón que funciona en el P40:
     * float→int16, MODE_STREAM, CONTENT_TYPE_MUSIC, bufSize=pcm.size.
     */
    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
        // Convertir float [-1.0, 1.0] a PCM 16-bit little endian
        val pcmBytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val s = (clamped * 32767f).toInt()
            pcmBytes[2 * i] = (s and 0xFF).toByte()
            pcmBytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }

        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, pcmBytes.size)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.i(TAG, "playAudio: state=${track.state} pcm=${pcmBytes.size}B rate=$sampleRate bufSize=$bufSize")
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "playAudio: AudioTrack no inicializado")
                return
            }

            currentTrack = track
            track.play()
            val written = track.write(pcmBytes, 0, pcmBytes.size)
            Log.i(TAG, "playAudio: write=$written/${pcmBytes.size}")

            val frames = pcmBytes.size / 2
            val expectedMs = frames * 1000L / sampleRate
            val deadline = System.currentTimeMillis() + expectedMs + 2000L
            var lastHead = 0
            while (System.currentTimeMillis() < deadline) {
                if (shuttingDown) break
                lastHead = track.playbackHeadPosition
                if (lastHead >= frames) break
                delay(40)
            }
            Log.i(TAG, "playAudio: head=$lastHead/$frames")
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

    private fun notifyComplete() {
        val cb = onComplete
        onComplete = null
        cb?.invoke()
    }
}
