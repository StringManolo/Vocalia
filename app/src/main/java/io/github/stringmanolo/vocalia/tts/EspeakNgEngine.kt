package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import io.github.stringmanolo.vocalia.util.VocaliaLogger
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class EspeakNgEngine(private val context: Context) : TtsEngine {

    override val name = "EspeakNg"

    private var synth: SpeechSynthesis? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var initialized = false
    @Volatile private var pendingText: String? = null
    private val audioLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val synthExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "espeak-synth").apply { isDaemon = true }
    }

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                extractVoiceDataIfNeeded()

                val callback = object : SpeechSynthesis.SynthReadyCallback {
                    override fun onSynthDataReady(audioData: ByteArray) {
                        playChunk(audioData)
                    }

                    override fun onSynthDataComplete() {
                        synchronized(audioLock) {
                            try { audioTrack?.stop() } catch (_: Exception) {}
                            try { audioTrack?.release() } catch (_: Exception) {}
                            audioTrack = null
                        }
                    }

                    override fun onSynthWordBoundary(
                        textPosition: Int,
                        textLength: Int,
                        markerInFrames: Int
                    ) {
                        // Futuro: resaltado de palabra para navegación de lectura
                    }
                }

                val s = SpeechSynthesis(context, callback)
                val sampleRate = s.sampleRate
                if (sampleRate <= 0) {
                    mainHandler.post {
                        onError(
                            "SpeechSynthesis no se inicializó (sampleRate=0). " +
                            "Revisa: hasBaseResources=${safeHasBaseResources()}, " +
                            "datos en ${safeDataPath()?.absolutePath}"
                        )
                    }
                    return@Thread
                }

                // Velocidad inicial: 175 WPM (ritmo conversacional típico)
                try { s.Rate.setValue(175) } catch (_: Exception) {}

                // Log de voces para diagnosticar (útil para luego fijar voz española)
                try {
                    val voices = s.availableVoices
                    VocaliaLogger.i("EspeakNgEngine", "Voces disponibles: ${voices.size}")
                    voices.take(8).forEach { v ->
                        VocaliaLogger.i("EspeakNgEngine", "  - ${v.name} / ${v.identifier}")
                    }
                } catch (t: Throwable) {
                    VocaliaLogger.w("EspeakNgEngine", "No se pudo listar voces: ${t.message}")
                }

                synth = s
                initialized = true
                VocaliaLogger.i("EspeakNgEngine", "espeak-ng listo (sampleRate=$sampleRate)")
                mainHandler.post { onReady() }

                pendingText?.let { text ->
                    pendingText = null
                    speak(text)
                }
            } catch (t: Throwable) {
                VocaliaLogger.e("EspeakNgEngine", "Error init: ${t.message}", t as? Exception)
                mainHandler.post { onError(t.message ?: "Error desconocido") }
            }
        }.start()
    }

    // ---------------------------------------------------------------
    // Extracción de datos de voz
    // ---------------------------------------------------------------

    private fun extractVoiceDataIfNeeded() {
        if (safeHasBaseResources()) {
            VocaliaLogger.i("EspeakNgEngine", "Datos de voz ya presentes")
            return
        }

        VocaliaLogger.i("EspeakNgEngine", "Extrayendo espeakdata.zip...")
        val dataPath = safeDataPath()
            ?: File(context.filesDir, "espeak-data").also { it.mkdirs() }

        val resId = context.resources.getIdentifier("espeakdata", "raw", context.packageName)
        if (resId == 0) {
            throw IllegalStateException("res/raw/espeakdata.zip no encontrado en la APK")
        }

        val tempZip = File(context.cacheDir, "espeakdata.zip")
        context.resources.openRawResource(resId).use { input ->
            tempZip.outputStream().use { input.copyTo(it) }
        }

        ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dataPath, entry.name)
                // Mitigar zip-slip
                if (!target.canonicalPath.startsWith(dataPath.canonicalPath + File.separator) &&
                    target.canonicalPath != dataPath.canonicalPath
                ) {
                    VocaliaLogger.w("EspeakNgEngine", "Ignorando entrada sospechosa: ${entry.name}")
                } else if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        tempZip.delete()

        val ok = safeHasBaseResources()
        VocaliaLogger.i("EspeakNgEngine", "Extracción completada. hasBaseResources=$ok")
        if (!ok) {
            // Diagnóstico: listar el primer nivel de dataPath
            try {
                dataPath.listFiles()?.take(10)?.forEach { f ->
                    VocaliaLogger.i("EspeakNgEngine", "  dataPath/${f.name} (dir=${f.isDirectory})")
                }
            } catch (_: Exception) {}
        }
    }

    private fun safeHasBaseResources(): Boolean = try {
        CheckVoiceData.hasBaseResources(context)
    } catch (t: Throwable) {
        VocaliaLogger.w("EspeakNgEngine", "hasBaseResources falló: ${t.message}")
        false
    }

    private fun safeDataPath(): File? = try {
        CheckVoiceData.getDataPath(context)
    } catch (t: Throwable) {
        VocaliaLogger.w("EspeakNgEngine", "getDataPath falló: ${t.message}")
        null
    }

    // ---------------------------------------------------------------
    // Reproducción de audio
    // ---------------------------------------------------------------

    private fun playChunk(audioData: ByteArray) {
        synchronized(audioLock) {
            try {
                if (audioTrack == null) {
                    val sampleRate = synth?.sampleRate ?: return
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufSize = maxOf(minBuf, audioData.size * 4)

                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                    audioTrack?.play()
                    VocaliaLogger.i("EspeakNgEngine", "AudioTrack abierto (rate=$sampleRate, buf=$bufSize)")
                }
                audioTrack?.write(audioData, 0, audioData.size)
            } catch (t: Throwable) {
                VocaliaLogger.e("EspeakNgEngine", "Error en playChunk: ${t.message}", t as? Exception)
            }
        }
    }

    // ---------------------------------------------------------------
    // TtsEngine
    // ---------------------------------------------------------------

    override fun speak(text: String, interruptible: Boolean) {
        if (!initialized) {
            pendingText = text
            return
        }
        val s = synth ?: return

        if (interruptible) {
            try { s.stop() } catch (_: Exception) {}
            synchronized(audioLock) {
                try { audioTrack?.stop() } catch (_: Exception) {}
                try { audioTrack?.release() } catch (_: Exception) {}
                audioTrack = null
            }
        }

        synthExecutor.execute {
            try {
                s.synthesize(text, false)
            } catch (t: Throwable) {
                VocaliaLogger.e("EspeakNgEngine", "Error synthesize: ${t.message}", t as? Exception)
            }
        }
    }

    override fun stop() {
        try { synth?.stop() } catch (_: Exception) {}
        synchronized(audioLock) {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }
    }

    override fun setSpeed(rate: Float) {
        try {
            // rate=1.0 → 175 WPM; clamp a [80, 450]
            val wpm = (175 * rate).toInt().coerceIn(80, 450)
            synth?.Rate?.setValue(wpm)
        } catch (t: Throwable) {
            VocaliaLogger.w("EspeakNgEngine", "Error setSpeed: ${t.message}")
        }
    }

    override fun shutdown() {
        stop()
        try { synthExecutor.shutdownNow() } catch (_: Exception) {}
        synth = null
        initialized = false
    }
}
