package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import io.github.stringmanolo.vocalia.util.InternalDebug
import io.github.stringmanolo.vocalia.util.VocaliaLogger
import java.io.ByteArrayOutputStream
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

    // Buffer de acumulación para MODE_STATIC
    private val audioBuffer = ByteArrayOutputStream()

    // Para cancelar el postDelayed de liberación
    private var releaseRunnable: Runnable? = null

    // Foco de audio
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioFocusRequest: AudioFocusRequest? = null

    // ---------------------------------------------------------------
    // init()
    // ---------------------------------------------------------------

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
                        InternalDebug.mark("callback.onSynthDataReady size=${audioData.size} total=${audioBuffer.size() + audioData.size}")
                        synchronized(audioLock) {
                            audioBuffer.write(audioData)
                        }
                    }

                    override fun onSynthDataComplete() {
                        synchronized(audioLock) {
                            val allAudio = audioBuffer.toByteArray()
                            audioBuffer.reset()
                            InternalDebug.mark("callback.onSynthDataComplete totalBytes=${allAudio.size}")
                            if (allAudio.isNotEmpty()) {
                                playStatic(allAudio)
                            }
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

                pendingText?.let { text ->
                    pendingText = null
                    speak(text)
                }
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
    }

    private fun safeHasBaseResources(): Boolean = try {
        CheckVoiceData.hasBaseResources(context)
    } catch (t: Throwable) {
        InternalDebug.mark("hasBaseResources THREW: ${t.message}")
        false
    }

    // ---------------------------------------------------------------
    // Volumen y foco de audio
    // ---------------------------------------------------------------

    private fun ensureMediaVolumeIsUp() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            InternalDebug.mark("ensureMediaVolumeIsUp: current=$current max=$max")
            if (current == 0) {
                val target = maxOf(1, max / 2)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                InternalDebug.mark("ensureMediaVolumeIsUp: subido a $target")
            }
        } catch (t: Throwable) {
            InternalDebug.mark("ensureMediaVolumeIsUp THREW: ${t.message}")
        }
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    InternalDebug.mark("onAudioFocusChange: $change")
                }
                .build()
            audioFocusRequest = req
            val result = audioManager.requestAudioFocus(req)
            InternalDebug.mark("requestAudioFocus: result=$result (1=GRANTED)")
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (t: Throwable) {
            InternalDebug.mark("requestAudioFocus THREW: ${t.message}")
            false
        }
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                InternalDebug.mark("abandonAudioFocus")
            }
        } catch (t: Throwable) {
            InternalDebug.mark("abandonAudioFocus THREW: ${t.message}")
        } finally {
            audioFocusRequest = null
        }
    }

    // ---------------------------------------------------------------
    // Reproducción con MODE_STATIC
    // ---------------------------------------------------------------

    private fun playStatic(pcm: ByteArray) {
        val sampleRate = synth?.sampleRate ?: return
        if (sampleRate <= 0) {
            InternalDebug.mark("playStatic: ABORT sampleRate inválido")
            return
        }

        ensureMediaVolumeIsUp()
        requestAudioFocus()

        // Cancelar cualquier liberación pendiente
        releaseRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseRunnable = null

        // Liberar el AudioTrack anterior si existe
        synchronized(audioLock) {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, pcm.size)
        InternalDebug.mark("playStatic: sampleRate=$sampleRate, pcmBytes=${pcm.size}, bufSize=$bufSize")

        try {
            val track = AudioTrack.Builder()
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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            InternalDebug.mark("playStatic: state=${track.state} (1=INITIALIZED_OK)")
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                InternalDebug.mark("playStatic: ABORT state inválido")
                track.release()
                return
            }

            track.setVolume(1.0f)
            val written = track.write(pcm, 0, pcm.size)
            InternalDebug.mark("playStatic: write result=$written de ${pcm.size}")
            if (written <= 0) {
                InternalDebug.mark("playStatic: ABORT write falló")
                track.release()
                return
            }

            track.play()
            InternalDebug.mark("playStatic: play() invocado")

            synchronized(audioLock) {
                audioTrack = track
            }

            // Duración: bytes / (sampleRate * 2) segundos, en ms
            val durationMs = (pcm.size * 1000L) / (sampleRate.toLong() * 2L)
            InternalDebug.mark("playStatic: programando liberación en ${durationMs + 200}ms")

            val runnable = Runnable {
                synchronized(audioLock) {
                    if (audioTrack === track) {
                        try { track.stop() } catch (_: Exception) {}
                        try { track.release() } catch (_: Exception) {}
                        audioTrack = null
                        InternalDebug.mark("playStatic: AudioTrack liberado tras ${durationMs}ms")
                    }
                }
                abandonAudioFocus()
            }
            releaseRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs + 200L)

        } catch (t: Throwable) {
            InternalDebug.mark("playStatic: THREW ${t.javaClass.simpleName}: ${t.message}")
            VocaliaLogger.e("EspeakNgEngine", "Error en playStatic", t as? Exception)
        }
    }

    private fun stopAndReleaseNow() {
        releaseRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseRunnable = null
        synchronized(audioLock) {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
            audioBuffer.reset()
        }
        abandonAudioFocus()
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
            stopAndReleaseNow()
        }

        synthExecutor.execute {
            try {
                s.synthesize(text, false)
            } catch (t: Throwable) {
                InternalDebug.mark("speak: synthesize THREW ${t.javaClass.simpleName}: ${t.message}")
                VocaliaLogger.e("EspeakNgEngine", "Error synthesize: ${t.message}", t as? Exception)
            }
        }
    }

    override fun stop() {
        try { synth?.stop() } catch (_: Exception) {}
        stopAndReleaseNow()
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
