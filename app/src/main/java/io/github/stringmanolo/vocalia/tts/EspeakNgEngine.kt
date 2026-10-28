package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import io.github.stringmanolo.vocalia.util.InternalDebug
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

    // ============================================================
    // init() DESHABILITADO TEMPORALMENTE PARA DIAGNÓSTICO
    //
    // Para restaurar la funcionalidad completa:
    //   1. Cambia el cuerpo de init() por el bloque marcado
    //      como "CUERPO ORIGINAL" al final de esta clase.
    //   2. Recompila e instala.
    // ============================================================
    /*override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        VocaliaLogger.w("EspeakNgEngine", "init DESHABILITADO para diagnóstico")
        onError("init deshabilitado para diagnóstico")
    }*/
    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
    InternalDebug.init(context)
    InternalDebug.mark("EspeakNgEngine.init START")
    Thread {
        try {
            InternalDebug.mark("init.thread START")
            extractVoiceDataIfNeeded()
            InternalDebug.mark("init: extractVoiceDataIfNeeded returned")

            InternalDebug.mark("init: creating SynthReadyCallback (triggers SpeechSynthesis.<clinit>)")
            val callback = object : SpeechSynthesis.SynthReadyCallback {
                override fun onSynthDataReady(audioData: ByteArray) {
                    InternalDebug.mark("callback.onSynthDataReady size=${audioData.size}")
                    playChunk(audioData)
                }
                override fun onSynthDataComplete() {
                    InternalDebug.mark("callback.onSynthDataComplete")
                    synchronized(audioLock) {
                        try { audioTrack?.stop() } catch (_: Exception) {}
                        try { audioTrack?.release() } catch (_: Exception) {}
                        audioTrack = null
                    }
                }
                override fun onSynthWordBoundary(
                    textPosition: Int, textLength: Int, markerInFrames: Int
                ) {}
            }
            InternalDebug.mark("init: callback created (static block OK si llegamos aquí)")

            InternalDebug.mark("init: SpeechSynthesis.getVersion() probe")
            val version = try {
                SpeechSynthesis.getVersion()
            } catch (t: Throwable) {
                InternalDebug.mark("init: getVersion THREW ${t.javaClass.simpleName}: ${t.message}")
                throw t
            }
            InternalDebug.mark("init: getVersion OK -> $version")

            InternalDebug.mark("init: calling SpeechSynthesis(context, callback)")
            val s = SpeechSynthesis(context, callback)
            InternalDebug.mark("init: SpeechSynthesis constructor RETURNED")

            val sampleRate = s.sampleRate
            InternalDebug.mark("init: sampleRate=$sampleRate")
            if (sampleRate <= 0) {
                InternalDebug.mark("init: sampleRate<=0, abortando")
                mainHandler.post {
                    onError(
                        "SpeechSynthesis no se inicializó (sampleRate=0). " +
                        "hasBaseResources=${safeHasBaseResources()}"
                    )
                }
                return@Thread
            }

            InternalDebug.mark("init: set Rate 175")
            try { s.Rate.setValue(175) } catch (_: Exception) {}

            InternalDebug.mark("init: getAvailableVoices")
            try {
                val voices = s.availableVoices
                InternalDebug.mark("init: ${voices.size} voices disponibles")
                voices.take(8).forEach { v ->
                    InternalDebug.mark("init:   voice ${v.identifier}")
                }
            } catch (t: Throwable) {
                InternalDebug.mark("init: voices THREW ${t.javaClass.simpleName}: ${t.message}")
            }

            synth = s
            initialized = true
            InternalDebug.mark("init: READY, invocando onReady")
            mainHandler.post { onReady() }

            pendingText?.let { text -> pendingText = null; speak(text) }
        } catch (t: Throwable) {
            InternalDebug.mark("init: CAUGHT ${t.javaClass.simpleName}: ${t.message}")
            VocaliaLogger.e("EspeakNgEngine", "Error init: ${t.message}", t as? Exception)
            mainHandler.post { onError(t.message ?: "Error desconocido") }
        }
    }.start()
}

    // ---------------------------------------------------------------
    // Extracción de datos de voz
    // ---------------------------------------------------------------

    private fun extractVoiceDataIfNeeded() {
    val filesRoot = context.filesDir
    val dataDir = File(filesRoot, "espeak-ng-data")

    InternalDebug.mark("extract START filesRoot=$filesRoot dataDir=$dataDir")

    if (safeHasBaseResources()) {
        InternalDebug.mark("extract: hasBaseResources=true, nada que hacer")
        return
    }

    InternalDebug.mark("extract: limpiando restos previos")
    File(filesRoot, "espeak-data").listFiles()?.forEach { it.deleteRecursively() }
    if (dataDir.exists()) dataDir.deleteRecursively()

    InternalDebug.mark("extract: localizando res/raw/espeakdata")
    val resId = context.resources.getIdentifier("espeakdata", "raw", context.packageName)
    InternalDebug.mark("extract: resId=$resId")
    if (resId == 0) throw IllegalStateException("res/raw/espeakdata.zip no encontrado")

    InternalDebug.mark("extract: copiando ZIP a cache")
    val tempZip = File(context.cacheDir, "espeakdata.zip")
    context.resources.openRawResource(resId).use { input ->
        tempZip.outputStream().use { input.copyTo(it) }
    }
    InternalDebug.mark("extract: ZIP copiado, size=${tempZip.length()}")

    InternalDebug.mark("extract: descomprimiendo")
    var count = 0
    ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            val target = File(filesRoot, name)
            if (!target.canonicalPath.startsWith(filesRoot.canonicalPath + File.separator) &&
                target.canonicalPath != filesRoot.canonicalPath
            ) {
                InternalDebug.mark("extract: SKIP $name")
            } else if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { zis.copyTo(it) }
                count++
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    tempZip.delete()
    InternalDebug.mark("extract: descomprimidos $count archivos")

    val ok = safeHasBaseResources()
    InternalDebug.mark("extract END hasBaseResources=$ok")
    if (!ok) {
        dataDir.listFiles()?.take(20)?.forEach { f ->
            InternalDebug.mark("extract:   $dataDir/${f.name} (dir=${f.isDirectory})")
        }
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
                val sampleRate = synth?.sampleRate
                InternalDebug.mark("playChunk: creating AudioTrack rate=$sampleRate size=${audioData.size}")
                if (sampleRate == null || sampleRate <= 0) {
                    InternalDebug.mark("playChunk: ABORT sampleRate inválido=$sampleRate")
                    return
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                InternalDebug.mark("playChunk: minBuf=$minBuf")
                if (minBuf <= 0) {
                    InternalDebug.mark("playChunk: ABORT minBuf inválido")
                    return
                }
                val bufSize = maxOf(minBuf, audioData.size * 4)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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
                InternalDebug.mark("playChunk: state=${audioTrack?.state} (3=INITIALIZED_OK)")
                audioTrack?.setVolume(1.0f)
                val playResult = audioTrack?.play()
                InternalDebug.mark("playChunk: play()=$playResult (0=SUCCESS)")
                VocaliaLogger.i("EspeakNgEngine", "AudioTrack abierto (rate=$sampleRate, buf=$bufSize)")
            }
            val written = audioTrack?.write(audioData, 0, audioData.size)
            InternalDebug.mark("playChunk: write size=${audioData.size} result=$written")
        } catch (t: Throwable) {
            InternalDebug.mark("playChunk: THREW ${t.javaClass.simpleName}: ${t.message}")
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
