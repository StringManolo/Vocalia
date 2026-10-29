package io.github.stringmanolo.vocalia.tts

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import com.reecedunn.espeak.Voice
import com.reecedunn.espeak.VoiceVariant
import io.github.stringmanolo.vocalia.util.InternalDebug
import io.github.stringmanolo.vocalia.util.VocaliaLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class EspeakNgEngine(context: Context) : TtsEngine {

    override val name = "EspeakNg"

    companion object {
        const val DIAG_ENABLED = false
        const val TTS_LANGUAGE = "es"
        const val TTS_VARIANT = "female"

        private const val TAG = "EspeakNgEngine"
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

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] $msg")
    }

    private fun dbgErr(msg: String, t: Throwable) {
        val st = Log.getStackTraceString(t).lines().take(6).joinToString(" | ")
        InternalDebug.mark("[${Thread.currentThread().name}] $msg -> ${t.javaClass.simpleName}: ${t.message} :: $st")
    }

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        InternalDebug.init(ctx)
        dbg("init START engine=${System.identityHashCode(this)} diag=$DIAG_ENABLED pid=${android.os.Process.myPid()}")
        Thread({ initWorker(onReady, onError) }, "espeak-init").start()
    }

    private fun initWorker(onReady: () -> Unit, onError: (String) -> Unit) {
        try {
            dbg("init.worker START")
            extractVoiceDataIfNeeded()
            dbg("init: extractVoiceDataIfNeeded returned")

            val s = obtainSharedSynth()

            val sampleRate = s.sampleRate
            dbg("init: sampleRate=$sampleRate")
            if (sampleRate <= 0) {
                dbg("init: sampleRate<=0, abortando")
                mainHandler.post {
                    onError("SpeechSynthesis no se inicializó (sampleRate=0). hasBaseResources=${safeHasBaseResources()}")
                }
                return
            }

            selectVoice(s, TTS_LANGUAGE, TTS_VARIANT)

            try { s.Rate.setValue(175) } catch (t: Throwable) { dbgErr("init: Rate.setValue THREW", t) }

            try {
                val voices = s.availableVoices
                dbg("init: ${voices.size} voices disponibles")
            } catch (t: Throwable) {
                dbgErr("init: availableVoices THREW", t)
            }

            activeEngine = this
            diagAudioState("init")

            initialized = true
            dbg("init: READY, publicando onReady en el hilo principal")
            mainHandler.post {
                dbg("init: onReady ejecutándose en main")
                onReady()
            }

            pendingText?.let { text ->
                pendingText = null
                dbg("init: hay texto pendiente, lo digo")
                speak(text)
            }
        } catch (t: Throwable) {
            dbgErr("init: CAUGHT", t)
            VocaliaLogger.e(TAG, "Error init: ${t.message}", t as? Exception)
            mainHandler.post { onError(t.message ?: "Error desconocido") }
        }
    }

    private fun selectVoice(s: SpeechSynthesis, language: String, variant: String) {
        try {
            val voices = s.availableVoices
            dbg("selectVoice: buscando language=$language variant=$variant entre ${voices.size} voces")

            val target: Voice? = voices.firstOrNull { it.identifier == languageIdentifierFor(language) }
                ?: voices.firstOrNull { it.locale?.language == language }
                ?: voices.firstOrNull { it.identifier.endsWith("/$language") }

            if (target == null) {
                dbg("selectVoice: NO se encontró voz para language=$language")
                return
            }

            val v = VoiceVariant.parseVoiceVariant(variant)
            if (v == null) {
                dbg("selectVoice: parseVoiceVariant(\"$variant\") devolvió null")
                return
            }

            s.setVoice(target, v)
            dbg("selectVoice: OK identifier=${target.identifier} name=${target.name} locale=${target.locale} variant=$v")
        } catch (t: Throwable) {
            dbgErr("selectVoice THREW", t)
        }
    }

    private fun languageIdentifierFor(lang: String): String = when (lang) {
        "es" -> "roa/es"
        "ca" -> "roa/ca"
        "en" -> "gmw/en"
        "fr" -> "roa/fr"
        "it" -> "roa/it"
        "pt" -> "roa/pt"
        "de" -> "gmw/de"
        "ru" -> "zle/ru"
        else -> lang
    }

    private fun obtainSharedSynth(): SpeechSynthesis {
        return synchronized(initLock) {
            val existing = sharedSynth
            if (existing != null) {
                dbg("synth: reutilizando SpeechSynthesis del proceso id=${System.identityHashCode(existing)}")
                existing
            } else {
                dbg("synth: creando callback (dispara SpeechSynthesis.<clinit> y carga libttsespeak.so)")
                val callback = object : SpeechSynthesis.SynthReadyCallback {
                    override fun onSynthDataReady(audioData: ByteArray) {
                        activeEngine?.onPcm(audioData)
                    }

                    override fun onSynthDataComplete() {
                        // No hacer nada: la reproducción se orquesta desde speak()
                        // después de que synthesize() retorne.
                    }

                    override fun onSynthWordBoundary(
                        textPosition: Int, textLength: Int, markerInFrames: Int
                    ) {
                    }
                }
                dbg("synth: callback creado (la librería nativa cargó bien)")

                val version = try {
                    SpeechSynthesis.getVersion()
                } catch (t: Throwable) {
                    dbgErr("synth: getVersion THREW", t)
                    throw t
                }
                dbg("synth: getVersion OK -> $version")

                dbg("synth: llamando a SpeechSynthesis(ctx, callback)")
                val created = SpeechSynthesis(ctx, callback)
                dbg("synth: constructor devolvió id=${System.identityHashCode(created)}")
                sharedSynth = created
                created
            }
        }
    }

    private fun extractVoiceDataIfNeeded() {
        val filesRoot = ctx.filesDir
        val dataDir = File(filesRoot, "espeak-ng-data")

        dbg("extract START filesRoot=$filesRoot dataDir=$dataDir")

        if (safeHasBaseResources()) {
            dbg("extract: hasBaseResources=true, nada que hacer")
            return
        }

        dbg("extract: limpiando restos previos")
        File(filesRoot, "espeak-data").listFiles()?.forEach { it.deleteRecursively() }
        if (dataDir.exists()) dataDir.deleteRecursively()

        val resId = ctx.resources.getIdentifier("espeakdata", "raw", ctx.packageName)
        dbg("extract: resId=$resId")
        if (resId == 0) throw IllegalStateException("res/raw/espeakdata.zip no encontrado")

        val tempZip = File(ctx.cacheDir, "espeakdata.zip")
        ctx.resources.openRawResource(resId).use { input ->
            tempZip.outputStream().use { input.copyTo(it) }
        }
        dbg("extract: ZIP copiado, size=${tempZip.length()}")

        var count = 0
        ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val target = File(filesRoot, entryName)
                if (!target.canonicalPath.startsWith(filesRoot.canonicalPath + File.separator) &&
                    target.canonicalPath != filesRoot.canonicalPath
                ) {
                    dbg("extract: SKIP $entryName")
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
        dbg("extract: descomprimidos $count archivos")
        dbg("extract END hasBaseResources=${safeHasBaseResources()}")
    }

    private fun safeHasBaseResources(): Boolean = try {
        CheckVoiceData.hasBaseResources(ctx)
    } catch (t: Throwable) {
        dbgErr("hasBaseResources THREW", t)
        false
    }

    private fun ensureMediaVolumeIsUp() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == 0) {
                val target = maxOf(1, max / 2)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                dbg("volumen MUSIC estaba a 0 -> subido a $target")
            }
        } catch (t: Throwable) {
            dbgErr("ensureMediaVolumeIsUp THREW", t)
        }
    }

    private fun requestAudioFocusLocked() {
        if (focusRequest != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change -> dbg("onAudioFocusChange: $change") }
                .build()
            val result = audioManager.requestAudioFocus(req)
            focusRequest = req
            dbg("requestAudioFocus: result=$result")
        } catch (t: Throwable) {
            dbgErr("requestAudioFocus THREW", t)
        }
    }

    private fun abandonAudioFocusLocked() {
        val req = focusRequest ?: return
        try {
            audioManager.abandonAudioFocusRequest(req)
            dbg("abandonAudioFocus")
        } catch (t: Throwable) {
            dbgErr("abandonAudioFocus THREW", t)
        } finally {
            focusRequest = null
        }
    }

    private fun describeDevice(d: AudioDeviceInfo?): String =
        if (d == null) "null" else "type=${d.type} id=${d.id} name=${d.productName}"

    private fun diagAudioState(tag: String) {
        try {
            val am = audioManager
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            dbg("DIAG[$tag] device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            dbg("DIAG[$tag] mode=${am.mode} ringer=${am.ringerMode} dnd=${nm.currentInterruptionFilter}")
            dbg("DIAG[$tag] MUSIC=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            dbg("DIAG[$tag] speakerphone=${am.isSpeakerphoneOn} a2dp=${am.isBluetoothA2dpOn} wired=${am.isWiredHeadsetOn}")
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
                dbg("DIAG[$tag] out: ${describeDevice(it)}")
            }
        } catch (t: Throwable) {
            dbgErr("diagAudioState THREW", t)
        }
    }

    private fun safeHead(t: AudioTrack): Int = try { t.playbackHeadPosition } catch (_: Throwable) { -1 }
    private fun safePlayState(t: AudioTrack): Int = try { t.playState } catch (_: Throwable) { -1 }

    /** Llamado por el callback nativo durante synthesize(). Solo acumula. */
    private fun onPcm(pcm: ByteArray) {
        if (shuttingDown) return
        if (synthGeneration != generation.get()) return
        val buf = pcmBuffer
        if (buf != null) buf.write(pcm)
    }

    /**
     * Reproduce el PCM acumulado. IMPORTANTE: se llama desde speak() DESPUÉS de
     * que synthesize() haya retornado. Reproducir dentro del callback nativo
     * (durante synthesize) hace que EMUI nunca conecte el AudioTrack al mezclador
     * y head se queda en 0 para siempre.
     */
    private fun playCleanly(pcm: ByteArray, gen: Int) {
        var t: AudioTrack? = null
        try {
            ensureMediaVolumeIsUp()
            requestAudioFocusLocked()

            val rate = sharedSynth?.sampleRate ?: 22050
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, pcm.size)
            dbg("playCleanly: pcmSize=${pcm.size} bufSize=$bufSize rate=$rate")

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

            dbg("playCleanly: state=${t.state} (1=OK)")
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                dbg("playCleanly: ABORT state inválido")
                return
            }

            t.play()
            val written = t.write(pcm, 0, pcm.size)
            dbg("playCleanly: play() playState=${safePlayState(t)} write=$written/${pcm.size}")

            val frames = pcm.size / 2
            val expectedMs = frames * 1000L / rate
            val t0 = SystemClock.elapsedRealtime()
            val deadline = t0 + expectedMs + 3000L
            var lastHead = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                if (gen != generation.get()) {
                    dbg("playCleanly: CANCELADO head=$lastHead/$frames (gen cambió)")
                    break
                }
                lastHead = safeHead(t)
                if (lastHead >= frames) {
                    dbg("playCleanly: OK head=$lastHead/$frames en ${SystemClock.elapsedRealtime() - t0}ms")
                    break
                }
                Thread.sleep(40)
            }
            if (lastHead < frames && gen == generation.get()) {
                dbg("playCleanly: TIMEOUT head=$lastHead/$frames")
            }

            // Margen para que el HAL termine
            Thread.sleep(150)
        } catch (ex: Throwable) {
            dbgErr("playCleanly THREW", ex)
        } finally {
            try { t?.pause() } catch (_: Throwable) {}
            try { t?.flush() } catch (_: Throwable) {}
            try { t?.stop() } catch (_: Throwable) {}
            try { t?.release() } catch (_: Throwable) {}
            abandonAudioFocusLocked()
        }
    }

    override fun speak(text: String, interruptible: Boolean) {
        if (!initialized) {
            pendingText = text
            dbg("speak: motor no listo, texto en cola")
            return
        }
        val s = sharedSynth
        if (s == null) {
            dbg("speak: sharedSynth es null")
            return
        }

        dbg("speak(\"$text\", interruptible=$interruptible)")

        val gen: Int
        if (interruptible) {
            gen = generation.incrementAndGet()
            try { s.stop() } catch (t: Throwable) { dbgErr("speak: synth.stop THREW", t) }
            abandonAudioFocusLocked()
        } else {
            gen = generation.get()
        }

        synthExecutor.execute {
            if (shuttingDown) {
                dbg("synth: motor cerrado, omito")
                return@execute
            }
            if (generation.get() != gen) {
                dbg("synth: locución obsoleta (gen=$gen != ${generation.get()})")
                return@execute
            }
            try {
                synthGeneration = gen
                val pcmOut = ByteArrayOutputStream()
                pcmBuffer = pcmOut

                val t0 = SystemClock.elapsedRealtime()
                dbg("synth: synthesize START \"$text\" gen=$gen")
                s.synthesize(text, false)
                dbg("synth: synthesize RETURNED tras ${SystemClock.elapsedRealtime() - t0}ms")

                pcmBuffer = null
                val pcmBytes = pcmOut.toByteArray()

                if (pcmBytes.isEmpty()) {
                    dbg("synth: PCM vacío, no reproduzco")
                    return@execute
                }
                if (generation.get() != gen) {
                    dbg("synth: gen cambió antes de playCleanly, abandonando")
                    return@execute
                }

                // AHORA sí: fuera de synthesize(), reproducimos.
                playCleanly(pcmBytes, gen)
            } catch (t: Throwable) {
                dbgErr("synth: THREW", t)
                VocaliaLogger.e(TAG, "Error synthesize: ${t.message}", t as? Exception)
            } finally {
                pcmBuffer = null
            }
        }
    }

    override fun stop() {
        dbg("stop()")
        generation.incrementAndGet()
        try { sharedSynth?.stop() } catch (t: Throwable) { dbgErr("stop: synth.stop THREW", t) }
        abandonAudioFocusLocked()
    }

    override fun setSpeed(rate: Float) {
        try {
            val wpm = (175 * rate).toInt().coerceIn(80, 450)
            sharedSynth?.Rate?.setValue(wpm)
            dbg("setSpeed($rate) -> $wpm wpm")
        } catch (t: Throwable) {
            VocaliaLogger.w(TAG, "Error setSpeed: ${t.message}")
            dbgErr("setSpeed THREW", t)
        }
    }

    override fun shutdown() {
        dbg("shutdown()")
        shuttingDown = true
        stop()
        if (activeEngine === this) activeEngine = null
        initialized = false
    }
}