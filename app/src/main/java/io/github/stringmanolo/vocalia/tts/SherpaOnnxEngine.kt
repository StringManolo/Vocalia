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
import java.io.File
import kotlin.math.abs

class SherpaOnnxEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val ASSET_DIR = "tts/vits-piper-es_ES-miro-high"
        private const val ONNX_FILE = "es_ES-miro-high.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DATA = "espeak-ng-data"
        private const val WRITE_CHUNK = 4096
    }

    override val name = "SherpaOnnx"
    @Volatile override var onComplete: (() -> Unit)? = null
    @Volatile private var paused = false

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
        Thread({ initWorker(onReady, onError) }, "sherpa-init").start()
    }

    private fun initWorker(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            dbg("init.worker START")

            val base = ensureModelOnDisk()
            val basePath = base.absolutePath
            val dataDir = File(base, ESPEAK_DATA)
            dbg("init: base=$basePath")
            dbg("init: dataDir=$dataDir (phontab=${File(dataDir, "phontab").exists()}, phondata=${File(dataDir, "phondata").exists()})")

            if (!File(dataDir, "phontab").exists() || !File(dataDir, "phondata").exists()) {
                dbg("init: espeak-ng-data incompleto")
                onError("datos de voz incompletos en $dataDir")
                return
            }

            dbg("init: construyendo OfflineTts")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(base, ONNX_FILE).absolutePath,
                        tokens = File(base, TOKENS_FILE).absolutePath,
                        dataDir = dataDir.absolutePath
                    ),
                    numThreads = 2
                )
            )

            val t0 = System.currentTimeMillis()
            val instance = OfflineTts(null, config)
            val dt = System.currentTimeMillis() - t0
            tts = instance
            dbg("init: OfflineTts creado en ${dt}ms")

            try {
                val s0 = System.currentTimeMillis()
                val test = instance.generate("prueba", 0, 1.0f)
                val sdt = System.currentTimeMillis() - s0
                var peak = 0f
                for (s in test.samples) if (abs(s) > peak) peak = abs(s)
                dbg("smoke: samples=${test.samples.size} rate=${test.sampleRate} peak=$peak took=${sdt}ms")
            } catch (t: Throwable) {
                dbg("smoke THREW ${t.javaClass.simpleName}: ${t.message}")
            }

            onReady()
            dbg("init.worker END")
        } catch (t: Throwable) {
            dbg("init.worker THREW ${t.javaClass.simpleName}: ${t.message}")
            onError(t.message ?: "Error desconocido")
        }
    }

    private fun ensureModelOnDisk(): File {
        val outDir = File(context.filesDir, ASSET_DIR)
        val marker = File(outDir, ".ok")
        if (marker.exists()) {
            dbg("ensureModelOnDisk: ya copiado en ${outDir.path}")
            return outDir
        }
        dbg("ensureModelOnDisk: copiando ${ASSET_DIR} a ${outDir.path}")
        val t0 = System.currentTimeMillis()
        if (outDir.exists()) outDir.deleteRecursively()
        copyAsset(ASSET_DIR, outDir)
        marker.writeText("1")
        dbg("ensureModelOnDisk: copiado en ${System.currentTimeMillis() - t0}ms")
        return outDir
    }

    private fun copyAsset(path: String, dest: File) {
        val children = context.assets.list(path)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            try {
                context.assets.open(path).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: java.io.FileNotFoundException) {
                dest.mkdirs()
            }
        } else {
            dest.mkdirs()
            children.forEach { child ->
                copyAsset("$path/$child", File(dest, child))
            }
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
        paused = false
        notifyComplete()
    }

    override fun pause() {
        dbg("pause()")
        paused = true
    }

    override fun resume() {
        dbg("resume()")
        paused = false
    }

    override fun isPaused(): Boolean = paused

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

            // Escritura por chunks para permitir pausa en caliente
            var offset = 0
            while (offset < pcmBytes.size) {
                if (shuttingDown) break
                if (paused) {
                    try { track.pause() } catch (_: Throwable) {}
                    dbg("playAudio: pausado en offset=$offset/${pcmBytes.size}")
                    while (paused && !shuttingDown) delay(50)
                    if (shuttingDown) break
                    try { track.play() } catch (_: Throwable) {}
                    dbg("playAudio: reanudado en offset=$offset")
                }
                val toWrite = minOf(WRITE_CHUNK, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, toWrite)
                if (written <= 0) {
                    dbg("playAudio: write=$written en offset=$offset, abortando")
                    break
                }
                offset += written
            }
            dbg("playAudio: escrito $offset/${pcmBytes.size}")

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
