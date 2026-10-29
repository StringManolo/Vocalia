package io.github.stringmanolo.vocalia.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import com.reecedunn.espeak.Voice
import com.reecedunn.espeak.VoiceVariant
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class EspeakNgEngine(context: Context) : TtsEngine {

    override val name = "EspeakNg"

    companion object {
        private const val TAG = "EspeakNgEngine"
        private const val TTS_LANGUAGE = "es"
        private const val TTS_VARIANT = "female"

        private val initLock = Any()
        @Volatile private var sharedSynth: SpeechSynthesis? = null
        @Volatile private var activeEngine: EspeakNgEngine? = null

        private val synthExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "espeak-synth").apply { isDaemon = true }
        }
    }

    private val ctx: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager by lazy {
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile private var initialized = false
    @Volatile private var shuttingDown = false
    @Volatile private var pendingText: String? = null

    private val generation = AtomicInteger(0)
    @Volatile private var synthGeneration = 0
    private var focusRequest: AudioFocusRequest? = null

    // Buffer donde onPcm acumula el PCM durante synthesize()
    @Volatile private var pcmBuffer: ByteArrayOutputStream? = null

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        Thread({ initWorker(onReady, onError) }, "espeak-init").start()
    }

    private fun initWorker(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            extractVoiceDataIfNeeded()
            val s = obtainSharedSynth()

            if (s.sampleRate <= 0) {
                mainHandler.post {
                    onError("SpeechSynthesis no se inicializó (sampleRate=0)")
                }
                return
            }

            selectVoice(s, TTS_LANGUAGE, TTS_VARIANT)
            try { s.Rate.setValue(175) } catch (_: Throwable) {}

            activeEngine = this
            initialized = true

            mainHandler.post { onReady() }

            pendingText?.let { text ->
                pendingText = null
                speak(text)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error init: ${t.message}", t)
            mainHandler.post { onError(t.message ?: "Error desconocido") }
        }
    }

    private fun selectVoice(s: SpeechSynthesis, language: String, variant: String) {
        try {
            val voices = s.availableVoices
            val target: Voice? = voices.firstOrNull { it.identifier == languageIdentifierFor(language) }
                ?: voices.firstOrNull { it.locale?.language == language }
            if (target != null) {
                VoiceVariant.parseVoiceVariant(variant)?.let { v ->
                    s.setVoice(target, v)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "selectVoice falló: ${t.message}")
        }
    }

    private fun languageIdentifierFor(lang: String): String = when (lang) {
        "es" -> "roa/es"
        "ca" -> "roa/ca"
        "en" -> "gmw/en"
        else -> lang
    }

    private fun obtainSharedSynth(): SpeechSynthesis {
        return synchronized(initLock) {
            sharedSynth ?: run {
                val callback = object : SpeechSynthesis.SynthReadyCallback {
                    override fun onSynthDataReady(audioData: ByteArray) {
                        activeEngine?.onPcm(audioData)
                    }
                    override fun onSynthDataComplete() {}
                    override fun onSynthWordBoundary(textPosition: Int, textLength: Int, markerInFrames: Int) {}
                }
                val created = SpeechSynthesis(ctx, callback)
                sharedSynth = created
                created
            }
        }
    }

    private fun extractVoiceDataIfNeeded() {
        if (safeHasBaseResources()) return

        val filesRoot = ctx.filesDir
        val dataDir = File(filesRoot, "espeak-ng-data")
        File(filesRoot, "espeak-data").listFiles()?.forEach { it.deleteRecursively() }
        if (dataDir.exists()) dataDir.deleteRecursively()

        val resId = ctx.resources.getIdentifier("espeakdata", "raw", ctx.packageName)
        if (resId == 0) throw IllegalStateException("res/raw/espeakdata.zip no encontrado")

        val tempZip = File(ctx.cacheDir, "espeakdata.zip")
        ctx.resources.openRawResource(resId).use { input ->
            tempZip.outputStream().use { input.copyTo(it) }
        }

        ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(filesRoot, entry.name)
                if (target.canonicalPath.startsWith(filesRoot.canonicalPath + File.separator) ||
                    target.canonicalPath == filesRoot.canonicalPath
                ) {
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        tempZip.delete()
    }

    private fun safeHasBaseResources(): Boolean = try {
        CheckVoiceData.hasBaseResources(ctx)
    } catch (_: Throwable) { false }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            audioManager.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Throwable) {}
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            try { audioManager.abandonAudioFocusRequest(it) } catch (_: Throwable) {}
            focusRequest = null
        }
    }

    /** Llamado por el callback nativo durante synthesize(). Solo acumula. */
    private fun onPcm(pcm: ByteArray) {
        if (shuttingDown) return
        if (synthGeneration != generation.get()) return
        pcmBuffer?.write(pcm)
    }

    /**
     * Reproduce el PCM acumulado. Se llama desde speak() DESPUÉS de synthesize().
     * Reproducir dentro del callback nativo hace que EMUI no conecte el AudioTrack.
     */
    private fun playCleanly(pcm: ByteArray, gen: Int) {
        var t: AudioTrack? = null
        try {
            requestAudioFocus()
            val rate = sharedSynth?.sampleRate ?: 22050
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, pcm.size)

            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (t.state != AudioTrack.STATE_INITIALIZED) return

            t.play()
            t.write(pcm, 0, pcm.size)

            val frames = pcm.size / 2
            val expectedMs = frames * 1000L / rate
            val deadline = SystemClock.elapsedRealtime() + expectedMs + 2000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (gen != generation.get()) break
                if (t.playbackHeadPosition >= frames) break
                Thread.sleep(40)
            }
            Thread.sleep(100) // margen para el HAL
        } catch (ex: Throwable) {
            Log.e(TAG, "playCleanly: ${ex.message}", ex)
        } finally {
            try { t?.stop() } catch (_: Throwable) {}
            try { t?.release() } catch (_: Throwable) {}
            abandonAudioFocus()
        }
    }

    override fun speak(text: String, interruptible: Boolean) {
        if (!initialized) { pendingText = text; return }
        val s = sharedSynth ?: return

        val gen: Int
        if (interruptible) {
            gen = generation.incrementAndGet()
            try { s.stop() } catch (_: Throwable) {}
            abandonAudioFocus()
        } else {
            gen = generation.get()
        }

        synthExecutor.execute {
            if (shuttingDown || generation.get() != gen) return@execute
            try {
                synthGeneration = gen
                val pcmOut = ByteArrayOutputStream()
                pcmBuffer = pcmOut

                s.synthesize(text, false)

                pcmBuffer = null
                val pcmBytes = pcmOut.toByteArray()
                if (pcmBytes.isNotEmpty() && generation.get() == gen) {
                    playCleanly(pcmBytes, gen)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "speak: ${t.message}", t)
            } finally {
                pcmBuffer = null
            }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        try { sharedSynth?.stop() } catch (_: Throwable) {}
        abandonAudioFocus()
    }

    override fun setSpeed(rate: Float) {
        try {
            sharedSynth?.Rate?.setValue((175 * rate).toInt().coerceIn(80, 450))
        } catch (_: Throwable) {}
    }

    override fun shutdown() {
        shuttingDown = true
        stop()
        if (activeEngine === this) activeEngine = null
        initialized = false
    }
}