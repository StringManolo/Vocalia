package io.github.stringmanolo.vocalia.tts

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.reecedunn.espeak.CheckVoiceData
import com.reecedunn.espeak.SpeechSynthesis
import io.github.stringmanolo.vocalia.util.InternalDebug
import io.github.stringmanolo.vocalia.util.VocaliaLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class EspeakNgEngine(context: Context) : TtsEngine {

    override val name = "EspeakNg"

    companion object {
        /** true = corre el test suite de audio al arrancar (15 configuraciones). */
        const val RUN_AUDIO_TEST_SUITE = true

        /** true = tonos de prueba al arrancar + volcado WAV + MediaPlayer tras cada locución. */
        const val DIAG_ENABLED = false

        private const val TAG = "EspeakNgEngine"

        private val initLock = Any()

        @Volatile private var sharedSynth: SpeechSynthesis? = null
        @Volatile private var activeEngine: EspeakNgEngine? = null

        private val synthExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "espeak-synth").apply { isDaemon = true }
        }
    }

    private class TestConfig(
        val id: Int,
        val description: String,
        val usage: Int,
        val contentType: Int,
        val transferMode: Int,
        val bufSizeStrategy: String, // "minBuf" | "minBuf*2" | "minBuf*4" | "size" | "64k"
        val setVolume: Boolean,
        val requestFocus: Boolean,
        val writeMode: String // "oneshot" | "incremental"
    )

    private class TestMode(
        val config: TestConfig,
        val buffer: ByteArrayOutputStream
    )

    private class DrainJob(
        val id: Int,
        val track: AudioTrack,
        val bytes: Long,
        val chunks: Int,
        val peak: Int,
        val rms: Int,
        val zeroPct: Int,
        val wav: ByteArray,
        val rate: Int
    ) {
        @Volatile var cancelled = false
    }

    private val ctx: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager by lazy {
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile private var initialized = false
    @Volatile private var shuttingDown = false
    @Volatile private var pendingText: String? = null
    @Volatile private var lastSpeakAt = 0L

    private val generation = AtomicInteger(0)
    @Volatile private var synthGeneration = 0

    private val audioLock = Any()
    private var track: AudioTrack? = null
    private val liveJobs = mutableListOf<DrainJob>()
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var drainThread: Thread? = null

    private var uttId = 0
    private var uttBytes = 0L
    private var uttChunks = 0
    private var uttPeak = 0
    private var uttSumSq = 0.0
    private var uttZeroSamples = 0L
    private var uttSamples = 0L
    private var uttWav = ByteArrayOutputStream()

    // Modo test: cuando no es null, onPcm acumula en buffer en vez de reproducir.
    @Volatile private var testMode: TestMode? = null

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] $msg")
    }

    private fun dbgErr(msg: String, t: Throwable) {
        val st = Log.getStackTraceString(t).lines().take(6).joinToString(" | ")
        InternalDebug.mark("[${Thread.currentThread().name}] $msg -> ${t.javaClass.simpleName}: ${t.message} :: $st")
    }

    override fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        InternalDebug.init(ctx)
        dbg("init START engine=${System.identityHashCode(this)} diag=$DIAG_ENABLED testSuite=$RUN_AUDIO_TEST_SUITE pid=${android.os.Process.myPid()}")
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

            try { s.Rate.setValue(175) } catch (t: Throwable) { dbgErr("init: Rate.setValue THREW", t) }

            activeEngine = this

            diagAudioState("init")

            if (RUN_AUDIO_TEST_SUITE) {
                dbg("init: ===== INICIANDO AUDIO TEST SUITE (15 configs) =====")
                runAudioTestSuite(s)
                dbg("init: ===== AUDIO TEST SUITE COMPLETADO =====")
            }

            if (DIAG_ENABLED) runStartupAudioTests()

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
                        activeEngine?.onPcmComplete(true)
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

    // ===============================================================
    // AUDIO TEST SUITE
    // ===============================================================

    private fun buildTestConfigs(): List<TestConfig> = listOf(
        // Grupo A: STREAM, write de una vez, contentType MUSIC
        TestConfig(1, "STREAM oneshot MUSIC buf=size vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),
        TestConfig(2, "STREAM oneshot MUSIC buf=size vol=no focus=sí",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, true, "oneshot"),
        TestConfig(3, "STREAM oneshot MUSIC buf=size vol=sí focus=sí",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", true, true, "oneshot"),
        TestConfig(4, "STREAM oneshot MUSIC buf=minBuf vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "minBuf", false, false, "oneshot"),
        TestConfig(5, "STREAM oneshot MUSIC buf=64k vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "64k", false, false, "oneshot"),

        // Grupo B: STATIC, write de una vez
        TestConfig(6, "STATIC oneshot MUSIC buf=size vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STATIC, "size", false, false, "oneshot"),
        TestConfig(7, "STATIC oneshot MUSIC buf=size vol=sí focus=sí",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STATIC, "size", true, true, "oneshot"),
        TestConfig(8, "STATIC oneshot MUSIC buf=size vol=no focus=sí",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STATIC, "size", false, true, "oneshot"),

        // Grupo C: contentType SPEECH
        TestConfig(9, "STREAM oneshot SPEECH buf=size vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),
        TestConfig(10, "STATIC oneshot SPEECH buf=size vol=no focus=no",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioTrack.MODE_STATIC, "size", false, false, "oneshot"),

        // Grupo D: otros usages
        TestConfig(11, "STREAM oneshot ASSIST_ACCESSIBILITY MUSIC buf=size",
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),
        TestConfig(12, "STREAM oneshot ASSIST_SONIFICATION MUSIC buf=size",
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),
        TestConfig(13, "STREAM oneshot VOICE_COMMUNICATION MUSIC buf=size",
            AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),
        TestConfig(14, "STREAM oneshot GAME MUSIC buf=size",
            AudioAttributes.USAGE_GAME, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "size", false, false, "oneshot"),

        // Grupo E: incremental (control — reproduce el bug)
        TestConfig(15, "STREAM incremental MUSIC buf=64k vol=sí focus=sí",
            AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC,
            AudioTrack.MODE_STREAM, "64k", true, true, "incremental"),
    )

    private fun runAudioTestSuite(s: SpeechSynthesis) {
        val configs = buildTestConfigs()
        for (config in configs) {
            try {
                dbg("=== TEST ${config.id}: ${config.description} ===")

                // 1. Sintetizar "N. Vocalia activa" y acumular el PCM en testMode.buffer
                val buffer = ByteArrayOutputStream()
                testMode = TestMode(config, buffer)
                s.synthesize("${config.id}. Vocalia activa", false)
                testMode = null

                val pcm = buffer.toByteArray()
                if (pcm.isEmpty()) {
                    dbg("TEST ${config.id}: PCM vacío, saltando")
                    continue
                }

                // 2. Reproducir el PCM con la configuración de este test
                playWithConfig(pcm, config, s.sampleRate)

                // 3. Pausa para que el usuario distinga cada prueba
                Thread.sleep(1500)
            } catch (t: Throwable) {
                dbgErr("TEST ${config.id} THREW", t)
                testMode = null
            }
        }
    }

    private fun playWithConfig(pcm: ByteArray, config: TestConfig, rate: Int) {
        var t: AudioTrack? = null
        try {
            if (config.setVolume) ensureMediaVolumeIsUp()
            if (config.requestFocus) requestAudioFocusLocked()

            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = when (config.bufSizeStrategy) {
                "minBuf" -> minBuf
                "minBuf*2" -> minBuf * 2
                "minBuf*4" -> minBuf * 4
                "64k" -> 64 * 1024
                "size" -> pcm.size
                else -> pcm.size
            }

            t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(config.usage)
                        .setContentType(config.contentType)
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
                .setTransferMode(config.transferMode)
                .build()

            dbg("TEST ${config.id}: track state=${t.state} bufSize=$bufSize pcmSize=${pcm.size} minBuf=$minBuf")

            // MODE_STATIC empieza en STATE_NO_STATIC_DATA (2) hasta el primer write
            val stateOk = if (config.transferMode == AudioTrack.MODE_STATIC) {
                t.state == AudioTrack.STATE_NO_STATIC_DATA || t.state == AudioTrack.STATE_INITIALIZED
            } else {
                t.state == AudioTrack.STATE_INITIALIZED
            }
            if (!stateOk) {
                dbg("TEST ${config.id}: ABORT state inválido=${t.state}")
                try { t.release() } catch (_: Throwable) {}
                return
            }

            if (config.setVolume) t.setVolume(1.0f)

            t.play()
            dbg("TEST ${config.id}: play() playState=${safePlayState(t)}")

            when (config.writeMode) {
                "oneshot" -> {
                    val written = t.write(pcm, 0, pcm.size)
                    dbg("TEST ${config.id}: write oneshot=$written de ${pcm.size}")
                }
                "incremental" -> {
                    var offset = 0
                    while (offset < pcm.size) {
                        val chunk = minOf(4096, pcm.size - offset)
                        val written = t.write(pcm, offset, chunk)
                        if (written <= 0) {
                            dbg("TEST ${config.id}: write incremental FALLÓ en offset=$offset")
                            break
                        }
                        offset += written
                        Thread.sleep(20)
                    }
                    dbg("TEST ${config.id}: write incremental completado offset=$offset")
                }
            }

            // Monitorizar head durante 2s
            val t0 = SystemClock.elapsedRealtime()
            var maxHead = 0
            while (SystemClock.elapsedRealtime() - t0 < 2000) {
                val head = safeHead(t)
                if (head > maxHead) maxHead = head
                Thread.sleep(100)
            }
            val frames = pcm.size / 2
            dbg("TEST ${config.id}: head final=$maxHead/$frames playState=${safePlayState(t)} underruns=${try { t.underrunCount } catch (_: Throwable) { -1 }}")
            if (maxHead > 0) {
                dbg("TEST ${config.id}: ✅ EL SISTEMA CONSUMIÓ EL AUDIO")
            } else {
                dbg("TEST ${config.id}: ❌ head=0, el sistema NO consumió el audio")
            }

            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
            t = null

            if (config.requestFocus) abandonAudioFocusLocked()
        } catch (ex: Throwable) {
            dbgErr("TEST ${config.id} playWithConfig THREW", ex)
            try { t?.stop() } catch (_: Throwable) {}
            try { t?.release() } catch (_: Throwable) {}
            if (config.requestFocus) abandonAudioFocusLocked()
        }
    }

    // ===============================================================
    // Resto del código: idéntico al de Claude
    // ===============================================================

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
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

    private fun runStartupAudioTests() {
        try {
            ensureMediaVolumeIsUp()
            diagTone()
            Thread.sleep(300)
            diagToneGenerator()
        } catch (t: Throwable) {
            dbgErr("runStartupAudioTests THREW", t)
        }
    }

    private fun diagTone() {
        var tr: AudioTrack? = null
        try {
            val rate = 22050
            val n = rate
            val tone = ShortArray(n) { i ->
                (sin(2.0 * Math.PI * 440.0 * i / rate) * 12000).toInt().toShort()
            }
            val t = AudioTrack.Builder()
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
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            tr = t
            t.play()
            t.write(tone, 0, n)
            Thread.sleep(1200)
        } catch (t: Throwable) {
            dbgErr("diagTone THREW", t)
        } finally {
            try { tr?.stop() } catch (_: Throwable) {}
            try { tr?.release() } catch (_: Throwable) {}
        }
    }

    private fun diagToneGenerator() {
        var tg: ToneGenerator? = null
        try {
            tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tg.startTone(ToneGenerator.TONE_DTMF_5, 600)
            Thread.sleep(800)
        } catch (t: Throwable) {
            dbgErr("diagToneGenerator THREW", t)
        } finally {
            try { tg?.release() } catch (_: Throwable) {}
        }
    }

    private fun safeHead(t: AudioTrack): Int = try { t.playbackHeadPosition } catch (e: Throwable) { -1 }
    private fun safePlayState(t: AudioTrack): Int = try { t.playState } catch (e: Throwable) { -1 }

    private fun resetUtteranceLocked() {
        uttBytes = 0L
        uttChunks = 0
        uttPeak = 0
        uttSumSq = 0.0
        uttZeroSamples = 0L
        uttSamples = 0L
        uttWav.reset()
    }

    private fun openTrackLocked(rate: Int): Boolean {
        if (rate <= 0) {
            dbg("openTrack: ABORT sampleRate inválido=$rate")
            return false
        }
        ensureMediaVolumeIsUp()

        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            dbg("openTrack: ABORT minBuf inválido=$minBuf")
            return false
        }
        val bufSize = maxOf(minBuf * 4, 64 * 1024)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
        } catch (e: Throwable) {
            dbgErr("openTrack: Builder THREW", e)
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            dbg("openTrack: ABORT el track no se inicializó")
            try { t.release() } catch (_: Throwable) {}
            return false
        }

        try { t.setVolume(1.0f) } catch (e: Throwable) { dbgErr("openTrack: setVolume THREW", e) }
        requestAudioFocusLocked()
        t.play()
        dbg("openTrack: play() playState=${safePlayState(t)} routed=${describeDevice(t.routedDevice)}")

        track = t
        uttId++
        resetUtteranceLocked()
        return true
    }

    private fun onPcm(pcm: ByteArray) {
        // Modo test: acumular en el buffer de la config actual
        val tm = testMode
        if (tm != null) {
            synchronized(tm.buffer) { tm.buffer.write(pcm) }
            return
        }

        if (shuttingDown) return
        if (synthGeneration != generation.get()) return
        val rate = sharedSynth?.sampleRate ?: 0

        if (track == null) {
            val prev = drainThread
            if (prev != null && prev.isAlive) {
                try { prev.join(15000) } catch (_: InterruptedException) {}
            }
        }

        val t: AudioTrack = synchronized(audioLock) {
            if (track == null && !openTrackLocked(rate)) return
            track
        } ?: return

        var peak = 0
        var sumSq = 0.0
        var zeros = 0L
        val n = pcm.size / 2
        for (i in 0 until n) {
            val s = (pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)
            val a = abs(s)
            if (a > peak) peak = a
            if (s == 0) zeros++
            sumSq += s.toDouble() * s.toDouble()
        }

        val written = try {
            t.write(pcm, 0, pcm.size)
        } catch (e: Throwable) {
            -99
        }

        synchronized(audioLock) {
            if (written > 0) {
                uttBytes += written
                uttWav.write(pcm, 0, written)
            }
            uttChunks++
            if (peak > uttPeak) uttPeak = peak
            uttSumSq += sumSq
            uttZeroSamples += zeros
            uttSamples += n
        }
    }

    private fun onPcmComplete(fromCallback: Boolean) {
        // Modo test: no hacer nada, el test suite gestiona la reproducción
        if (testMode != null) return

        if (shuttingDown) return

        val job: DrainJob? = synchronized(audioLock) {
            val t = track
            if (t == null) {
                null
            } else {
                val samples = uttSamples
                val rms = if (samples > 0) sqrt(uttSumSq / samples).toInt() else 0
                val zeroPct = if (samples > 0) (uttZeroSamples * 100 / samples).toInt() else 100
                val j = DrainJob(
                    uttId, t, uttBytes, uttChunks, uttPeak, rms, zeroPct,
                    uttWav.toByteArray(), sharedSynth?.sampleRate ?: 22050
                )
                liveJobs.add(j)
                track = null
                j
            }
        }

        if (job == null) return

        val th = Thread({ drainWorker(job) }, "espeak-drain-${job.id}")
        th.isDaemon = true
        drainThread = th
        th.start()
    }

    private fun drainWorker(job: DrainJob) {
        val frames = (job.bytes / 2).toInt()
        val expectedMs = if (job.rate > 0) frames * 1000L / job.rate else 0L
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + expectedMs + 3000L
        var head = 0
        var finished = false

        while (!job.cancelled && SystemClock.elapsedRealtime() < deadline) {
            head = safeHead(job.track)
            if (head >= frames) {
                finished = true
                break
            }
            try { Thread.sleep(40) } catch (_: InterruptedException) { break }
        }

        if (job.cancelled) return

        if (finished) {
            dbg("drain#${job.id}: OK ($head/$frames)")
        } else {
            dbg("drain#${job.id}: TIMEOUT head=$head/$frames")
        }

        try { Thread.sleep(250) } catch (_: InterruptedException) {}

        synchronized(audioLock) {
            if (liveJobs.remove(job)) releaseTrack(job.track)
            if (track == null && liveJobs.isEmpty()) abandonAudioFocusLocked()
        }

        if (DIAG_ENABLED && !job.cancelled) diagWavAndMediaPlayer(job)
    }

    private fun releaseTrack(t: AudioTrack) {
        try { t.pause() } catch (_: Throwable) {}
        try { t.flush() } catch (_: Throwable) {}
        try { t.stop() } catch (_: Throwable) {}
        try { t.release() } catch (_: Throwable) {}
    }

    private fun releaseEverything(reason: String) {
        synchronized(audioLock) {
            val t = track
            track = null
            if (t != null) releaseTrack(t)
            liveJobs.forEach {
                it.cancelled = true
                releaseTrack(it.track)
            }
            liveJobs.clear()
            resetUtteranceLocked()
            abandonAudioFocusLocked()
        }
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        file.outputStream().use {
            it.write(header.array())
            it.write(pcm)
        }
    }

    private fun diagWavAndMediaPlayer(job: DrainJob) {
        if (job.wav.isEmpty()) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "espeak_last.wav")
        try {
            writeWav(f, job.wav, job.rate)
        } catch (t: Throwable) {
            dbgErr("DIAG WAV THREW", t)
            return
        }

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            val dur = mp.duration
            val t0 = SystemClock.elapsedRealtime()
            while (mp.isPlaying && SystemClock.elapsedRealtime() - t0 < dur + 1500L) {
                Thread.sleep(50)
            }
        } catch (t: Throwable) {
            dbgErr("DIAG MediaPlayer THREW", t)
        } finally {
            try { mp.release() } catch (_: Throwable) {}
        }
    }

    override fun speak(text: String, interruptible: Boolean) {
        if (!initialized) {
            pendingText = text
            return
        }
        val s = sharedSynth ?: return
        lastSpeakAt = SystemClock.elapsedRealtime()

        val gen: Int
        if (interruptible) {
            gen = generation.incrementAndGet()
            try { s.stop() } catch (_: Throwable) {}
            releaseEverything("speak(interruptible)")
        } else {
            gen = generation.get()
        }

        synthExecutor.execute {
            if (shuttingDown) return@execute
            if (generation.get() != gen) return@execute
            try {
                synthGeneration = gen
                s.synthesize(text, false)
                onPcmComplete(false)
            } catch (t: Throwable) {
                VocaliaLogger.e(TAG, "Error synthesize: ${t.message}", t as? Exception)
            }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        try { sharedSynth?.stop() } catch (_: Throwable) {}
        releaseEverything("stop()")
    }

    override fun setSpeed(rate: Float) {
        try {
            val wpm = (175 * rate).toInt().coerceIn(80, 450)
            sharedSynth?.Rate?.setValue(wpm)
        } catch (_: Throwable) {}
    }

    override fun shutdown() {
        shuttingDown = true
        stop()
        if (activeEngine === this) activeEngine = null
        initialized = false
    }
}