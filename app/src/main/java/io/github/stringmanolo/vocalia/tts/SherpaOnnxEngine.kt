package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.stringmanolo.vocalia.util.InternalDebug
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

    override val name = "SherpaOnnx"
    @Volatile override var onComplete: (() -> Unit)? = null

    private var tts: OfflineTts? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var shuttingDown = false
    @Volatile private var currentSpeed = 1.0f

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] Sherpa: $msg")
    }

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            dbg("init: construyendo OfflineTts")
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
            dbg("init: OfflineTts creado")

            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    val test = instance.generate("prueba", 0, 1.0f)
                    val dt = System.currentTimeMillis() - t0
                    var peak = 0f
                    for (s in test.samples) if (abs(s) > peak) peak = abs(s)
                    dbg("smoke: samples=${test.samples.size} rate=${test.sampleRate} peak=$peak took=${dt}ms")
                    if (test.samples.isEmpty()) {
                        dbg("smoke: generate() devuelve 0 muestras — el modelo NO funciona")
                    }
                } catch (t: Throwable) {
                    dbg("smoke THREW ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "sherpa-smoke").start()

            onReady()
        } catch (t: Throwable) {
            dbg("init falló ${t.javaClass.simpleName}: ${t.message}")
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
            dbg("speak sin modelo")
            notifyComplete()
            return
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                dbg("speak(\"$text\") generating...")
                val t0 = System.currentTimeMillis()
                val audio = engine.generate(text, 0, currentSpeed)
                val dt = System.currentTimeMillis() - t0

                var peak = 0f
                for (s in audio.samples) if (abs(s) > peak) peak = abs(s)

                dbg("generate: samples=${audio.samples.size} rate=${audio.sampleRate} peak=$peak took=${dt}ms")

                if (audio.samples.isNotEmpty() && peak > 0f) {
                    playAudio(audio.samples, audio.sampleRate)
                } else {
                    dbg("generate vacío/silencio (samples=${audio.samples.size}, peak=$peak)")
                }
            } catch (e: CancellationException) {
                dbg("speak cancelado")
                throw e
            } catch (t: Throwable) {
                dbg("speak THREW ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                notifyComplete()
            }
        }
    }

    override fun stop() {
        dbg("stop()")
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
        dbg("shutdown()")
        shuttingDown = true
        stop()
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        scope.cancel()
    }

    private suspend fun playAudio(samples: FloatArray, sampleRate: Int) {
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

            dbg("playAudio: state=${track.state} pcm=${pcmBytes.size}B rate=$sampleRate bufSize=$bufSize")
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                dbg("playAudio: AudioTrack NO inicializado")
                return
            }

            currentTrack = track
            track.play()
            val written = track.write(pcmBytes, 0, pcmBytes.size)
            dbg("playAudio: write=$written/${pcmBytes.size}")

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
            dbg("playAudio: head=$lastHead/$frames")
            delay(100)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            dbg("playAudio THREW ${t.javaClass.simpleName}: ${t.message}")
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
