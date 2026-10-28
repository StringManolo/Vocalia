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

/**
 * Motor TTS basado en espeak-ng (reecedunn SpeechSynthesis) + AudioTrack en streaming.
 *
 * Cambios respecto a la versión anterior:
 *  - SpeechSynthesis es UNO por proceso (singleton). Antes se creaba uno nuevo en cada
 *    arranque del servicio y la 2.ª locución salía recortada (0,28 s en vez de 1,05 s).
 *  - Nada depende del hilo principal para liberar el audio: un hilo "drain" espera a que
 *    playbackHeadPosition llegue al final (eso mide si el sistema CONSUME el audio).
 *  - Con DIAG_ENABLED = true: tonos de prueba, estadísticas del PCM, volcado a WAV y
 *    reproducción del mismo WAV con MediaPlayer. Todo se escribe en INTERNAL_DEBUG.
 */
class EspeakNgEngine(context: Context) : TtsEngine {

    override val name = "EspeakNg"

    companion object {
        /** true = tonos de prueba al arrancar + volcado WAV + MediaPlayer tras cada locución. */
        const val DIAG_ENABLED = true

        private const val TAG = "EspeakNgEngine"

        private val initLock = Any()

        @Volatile private var sharedSynth: SpeechSynthesis? = null
        @Volatile private var activeEngine: EspeakNgEngine? = null

        // Un solo hilo de síntesis para todo el proceso (el motor nativo no es reentrante).
        private val synthExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "espeak-synth").apply { isDaemon = true }
        }
    }

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

    // Para descartar PCM de locuciones interrumpidas.
    private val generation = AtomicInteger(0)
    @Volatile private var synthGeneration = 0

    // Estado de audio (todo bajo audioLock)
    private val audioLock = Any()
    private var track: AudioTrack? = null
    private val liveJobs = mutableListOf<DrainJob>()
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var drainThread: Thread? = null

    // Estadísticas de la locución en curso (bajo audioLock)
    private var uttId = 0
    private var uttBytes = 0L
    private var uttChunks = 0
    private var uttPeak = 0
    private var uttSumSq = 0.0
    private var uttZeroSamples = 0L
    private var uttSamples = 0L
    private var uttWav = ByteArrayOutputStream()

    // ---------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] $msg")
    }

    private fun dbgErr(msg: String, t: Throwable) {
        val st = Log.getStackTraceString(t).lines().take(6).joinToString(" | ")
        InternalDebug.mark("[${Thread.currentThread().name}] $msg -> ${t.javaClass.simpleName}: ${t.message} :: $st")
    }

    // ---------------------------------------------------------------
    // init()
    // ---------------------------------------------------------------

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

            try { s.Rate.setValue(175) } catch (t: Throwable) { dbgErr("init: Rate.setValue THREW", t) }

            try {
                val voices = s.availableVoices
                dbg("init: ${voices.size} voices disponibles")
                voices.take(8).forEach { v -> dbg("init:   voice ${v.identifier}") }
            } catch (t: Throwable) {
                dbgErr("init: availableVoices THREW", t)
            }

            activeEngine = this

            diagAudioState("init")
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

    /** Un único SpeechSynthesis por proceso; el callback delega en el motor activo. */
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
                        // Futuro: resaltado de palabra
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

    // ---------------------------------------------------------------
    // Extracción de datos de voz
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // Volumen, foco de audio y estado del sistema
    // ---------------------------------------------------------------

    private fun ensureMediaVolumeIsUp() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            dbg("volumen MUSIC=$current/$max")
            if (current == 0) {
                val target = maxOf(1, max / 2)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                dbg("volumen MUSIC estaba a 0 -> subido a $target")
            }
        } catch (t: Throwable) {
            dbgErr("ensureMediaVolumeIsUp THREW", t)
        }
    }

    /** Llamar con audioLock tomado. */
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
            dbg("requestAudioFocus: result=$result (1=GRANTED, 0=FAILED, 2=DELAYED)")
        } catch (t: Throwable) {
            dbgErr("requestAudioFocus THREW", t)
        }
    }

    /** Llamar con audioLock tomado. */
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
        // type: 1=EARPIECE 2=SPEAKER 3=WIRED_HEADSET 4=WIRED_HEADPHONES 7=BT_SCO 8=BT_A2DP 18=TELEPHONY
        if (d == null) "null" else "type=${d.type} id=${d.id} name=${d.productName} sink=${d.isSink}"

    private fun diagAudioState(tag: String) {
        try {
            val am = audioManager
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            dbg("DIAG[$tag] dispositivo=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} display=${Build.DISPLAY}")
            dbg(
                "DIAG[$tag] audioMode=${am.mode} (0=NORMAL, 3=IN_COMMUNICATION) ringer=${am.ringerMode} (2=NORMAL) " +
                    "dndFilter=${nm.currentInterruptionFilter} (1=ALL, 2=PRIORITY, 3=NONE/total silencio, 4=ALARMS)"
            )
            val streams = listOf(
                "MUSIC" to AudioManager.STREAM_MUSIC,
                "ALARM" to AudioManager.STREAM_ALARM,
                "NOTIF" to AudioManager.STREAM_NOTIFICATION,
                "SYSTEM" to AudioManager.STREAM_SYSTEM,
                "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL
            )
            val vols = streams.joinToString(" ") { (n, id) ->
                "$n=${am.getStreamVolume(id)}/${am.getStreamMaxVolume(id)}${if (am.isStreamMute(id)) "(MUTED)" else ""}"
            }
            dbg("DIAG[$tag] volúmenes: $vols")
            dbg(
                "DIAG[$tag] speakerphone=${am.isSpeakerphoneOn} a2dp=${am.isBluetoothA2dpOn} " +
                    "sco=${am.isBluetoothScoOn} wired=${am.isWiredHeadsetOn} musicActive=${am.isMusicActive}"
            )
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
                dbg("DIAG[$tag] salida disponible: ${describeDevice(it)}")
            }
            dbg(
                "DIAG[$tag] hw outputSampleRate=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} " +
                    "framesPerBuffer=${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}"
            )
        } catch (t: Throwable) {
            dbgErr("diagAudioState THREW", t)
        }
    }

    // ---------------------------------------------------------------
    // Tests de arranque (solo si DIAG_ENABLED)
    // ---------------------------------------------------------------

    private fun runStartupAudioTests() {
        try {
            ensureMediaVolumeIsUp()
            dbg("DIAG: ===== TEST 1/2: tono 440 Hz con AudioTrack (1 s) =====")
            diagTone()
            Thread.sleep(300)
            dbg("DIAG: ===== TEST 2/2: ToneGenerator (0,6 s) =====")
            diagToneGenerator()
            Thread.sleep(300)
            dbg("DIAG: ===== tests de arranque terminados; ahora hablará espeak =====")
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
            dbg("DIAG tono: state=${t.state} (1=OK) session=${t.audioSessionId}")
            t.play()
            val w = t.write(tone, 0, n)
            dbg("DIAG tono: write=$w de $n muestras")
            var head = 0
            for (i in 0 until 25) {
                Thread.sleep(100)
                head = safeHead(t)
                dbg("DIAG tono: head=$head/$n playState=${safePlayState(t)} (3=PLAYING) routed=${describeDevice(t.routedDevice)}")
                if (head >= n) break
            }
            dbg(
                "DIAG tono: RESULTADO head=$head/$n -> " +
                    if (head >= n) "el sistema CONSUMIÓ el tono (¿lo oíste?)" else "el sistema NO consumió el tono"
            )
            Thread.sleep(250)
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
            val ok = tg.startTone(ToneGenerator.TONE_DTMF_5, 600)
            dbg("DIAG ToneGenerator: startTone devolvió $ok")
            Thread.sleep(800)
        } catch (t: Throwable) {
            dbgErr("diagToneGenerator THREW", t)
        } finally {
            try { tg?.release() } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------
    // Reproducción (streaming)
    // ---------------------------------------------------------------

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

    /** Llamar con audioLock tomado. Devuelve false si no se pudo crear el track. */
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

        dbg(
            "openTrack: state=${t.state} (1=OK) rate=$rate minBuf=$minBuf bufSize=$bufSize " +
                "bufferFrames=${t.bufferSizeInFrames} session=${t.audioSessionId} " +
                "desdeSpeak=${SystemClock.elapsedRealtime() - lastSpeakAt}ms"
        )
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            dbg("openTrack: ABORT el track no se inicializó")
            try { t.release() } catch (_: Throwable) {}
            return false
        }

        try { t.setVolume(1.0f) } catch (e: Throwable) { dbgErr("openTrack: setVolume THREW", e) }
        requestAudioFocusLocked()
        t.play()
        dbg("openTrack: play() playState=${safePlayState(t)} (3=PLAYING) routed=${describeDevice(t.routedDevice)}")

        track = t
        uttId++
        resetUtteranceLocked()
        return true
    }

    /** Llamado por el callback nativo, en el hilo de síntesis. */
    private fun onPcm(pcm: ByteArray) {
        if (shuttingDown) return
        if (synthGeneration != generation.get()) {
            dbg("onPcm: DESCARTADO size=${pcm.size} (synthGen=$synthGeneration != gen=${generation.get()})")
            return
        }
        val rate = sharedSynth?.sampleRate ?: 0

        // Si no hay track abierto, espera a que termine el audio de la locución anterior.
        if (track == null) {
            val prev = drainThread
            if (prev != null && prev.isAlive) {
                dbg("onPcm: esperando a que acabe la locución anterior")
                try { prev.join(15000) } catch (_: InterruptedException) {}
            }
        }

        val t: AudioTrack = synchronized(audioLock) {
            if (track == null && !openTrackLocked(rate)) return
            track
        } ?: return

        // Estadísticas del PCM (16 bit little endian)
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
            dbgErr("onPcm: write THREW", e)
            -99
        }

        var chunkNo = 0
        synchronized(audioLock) {
            if (written > 0) {
                uttBytes += written
                uttWav.write(pcm, 0, written)
            }
            uttChunks++
            chunkNo = uttChunks
            if (peak > uttPeak) uttPeak = peak
            uttSumSq += sumSq
            uttZeroSamples += zeros
            uttSamples += n
        }

        val rms = if (n > 0) sqrt(sumSq / n).toInt() else 0
        val zeroPct = if (n > 0) (zeros * 100 / n).toInt() else 100
        dbg(
            "onPcm #$chunkNo: size=${pcm.size} written=$written (negativo=error: -3 INVALID_OP, -2 BAD_VALUE, -6 DEAD) " +
                "peak=$peak/32767 rms=$rms ceros=$zeroPct% head=${safeHead(t)} playState=${safePlayState(t)}"
        )
    }

    /** Llamado al terminar la síntesis (callback nativo o fallback tras synthesize()). */
    private fun onPcmComplete(fromCallback: Boolean) {
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

        if (job == null) {
            if (fromCallback) dbg("onPcmComplete: sin track activo (locución vacía o descartada)")
            return
        }

        dbg("onPcmComplete(fromCallback=$fromCallback): locución #${job.id} completa, lanzo drain")
        val th = Thread({ drainWorker(job) }, "espeak-drain-${job.id}")
        th.isDaemon = true
        drainThread = th
        th.start()
    }

    /** Espera (sin tocar el hilo principal) a que el sistema consuma todo el audio escrito. */
    private fun drainWorker(job: DrainJob) {
        val frames = (job.bytes / 2).toInt()
        val expectedMs = if (job.rate > 0) frames * 1000L / job.rate else 0L
        dbg(
            "drain#${job.id} START bytes=${job.bytes} frames=$frames duración=${expectedMs}ms chunks=${job.chunks} " +
                "peak=${job.peak}/32767 rms=${job.rms} ceros=${job.zeroPct}%"
        )

        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + expectedMs + 3000L
        var head = 0
        var lastLog = 0L
        var firstMoveAt = -1L
        var warnedStuck = false
        var finished = false

        while (!job.cancelled && SystemClock.elapsedRealtime() < deadline) {
            head = safeHead(job.track)
            val now = SystemClock.elapsedRealtime()
            if (head > 0 && firstMoveAt < 0) {
                firstMoveAt = now - t0
                dbg("drain#${job.id}: head empezó a avanzar a los ${firstMoveAt}ms")
            }
            if (!warnedStuck && head <= 0 && now - t0 > 1500) {
                warnedStuck = true
                dbg("drain#${job.id}: head sigue en $head tras 1,5 s -> el sistema NO está consumiendo el audio")
            }
            if (now - lastLog >= 250) {
                lastLog = now
                dbg("drain#${job.id}: t=${now - t0}ms head=$head/$frames playState=${safePlayState(job.track)}")
            }
            if (head >= frames) {
                finished = true
                break
            }
            try { Thread.sleep(40) } catch (_: InterruptedException) { break }
        }

        if (job.cancelled) {
            dbg("drain#${job.id}: cancelado por stop/interrupción (head=$head/$frames)")
            return
        }

        val underruns = try { job.track.underrunCount } catch (_: Throwable) { -1 }
        val routed = try { describeDevice(job.track.routedDevice) } catch (_: Throwable) { "?" }
        if (finished) {
            dbg("drain#${job.id}: OK el sistema consumió todo ($head/$frames) en ${SystemClock.elapsedRealtime() - t0}ms underruns=$underruns routed=$routed")
        } else {
            dbg("drain#${job.id}: TIMEOUT head=$head/$frames underruns=$underruns playState=${safePlayState(job.track)} routed=$routed")
        }

        // Margen para que el HAL termine de reproducir la cola.
        try { Thread.sleep(250) } catch (_: InterruptedException) {}

        synchronized(audioLock) {
            if (liveJobs.remove(job)) releaseTrack(job.track)
            if (track == null && liveJobs.isEmpty()) abandonAudioFocusLocked()
        }
        dbg("drain#${job.id}: track liberado")

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
            if (t != null) {
                dbg("release[$reason]: liberando track actual")
                releaseTrack(t)
            }
            if (liveJobs.isNotEmpty()) dbg("release[$reason]: cancelando ${liveJobs.size} drain(s)")
            liveJobs.forEach {
                it.cancelled = true
                releaseTrack(it.track)
            }
            liveJobs.clear()
            resetUtteranceLocked()
            abandonAudioFocusLocked()
        }
    }

    // ---------------------------------------------------------------
    // Diagnóstico: WAV + MediaPlayer (mismo audio por otro camino)
    // ---------------------------------------------------------------

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())            // PCM
        header.putShort(1.toShort())            // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)           // byte rate
        header.putShort(2.toShort())            // block align
        header.putShort(16.toShort())           // bits por muestra
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        file.outputStream().use {
            it.write(header.array())
            it.write(pcm)
        }
    }

    private fun diagWavAndMediaPlayer(job: DrainJob) {
        if (job.wav.isEmpty()) {
            dbg("DIAG WAV: no hay PCM que volcar")
            return
        }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "espeak_last.wav")
        try {
            writeWav(f, job.wav, job.rate)
            dbg("DIAG WAV escrito: ${f.absolutePath} size=${f.length()} (cópialo al PC y reprodúcelo)")
        } catch (t: Throwable) {
            dbgErr("DIAG WAV THREW", t)
            return
        }

        dbg("DIAG: ===== TEST 4: el mismo WAV reproducido con MediaPlayer =====")
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
            val dur = mp.duration
            dbg("DIAG MediaPlayer: prepared duration=${dur}ms; start()")
            mp.start()
            val t0 = SystemClock.elapsedRealtime()
            while (mp.isPlaying && SystemClock.elapsedRealtime() - t0 < dur + 1500L) {
                Thread.sleep(50)
            }
            dbg("DIAG MediaPlayer: terminado pos=${mp.currentPosition}/${dur}ms")
        } catch (t: Throwable) {
            dbgErr("DIAG MediaPlayer THREW", t)
        } finally {
            try { mp.release() } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------
    // TtsEngine
    // ---------------------------------------------------------------

    override fun speak(text: String, interruptible: Boolean) {
        dbg("speak(\"$text\", interruptible=$interruptible) initialized=$initialized")
        if (!initialized) {
            pendingText = text
            dbg("speak: motor no listo, texto en cola")
            return
        }
        val s = sharedSynth
        if (s == null) {
            dbg("speak: sharedSynth es null, no puedo hablar")
            return
        }

        lastSpeakAt = SystemClock.elapsedRealtime()

        val gen: Int
        if (interruptible) {
            gen = generation.incrementAndGet()
            try { s.stop() } catch (t: Throwable) { dbgErr("speak: synth.stop THREW", t) }
            releaseEverything("speak(interruptible)")
        } else {
            gen = generation.get()
        }

        synthExecutor.execute {
            if (shuttingDown) {
                dbg("synth: motor cerrado, omito")
                return@execute
            }
            if (generation.get() != gen) {
                dbg("synth: locución obsoleta (gen=$gen != ${generation.get()}), omito")
                return@execute
            }
            try {
                synthGeneration = gen
                val t0 = SystemClock.elapsedRealtime()
                dbg("synth: synthesize START \"$text\" gen=$gen")
                s.synthesize(text, false)
                dbg("synth: synthesize RETURNED tras ${SystemClock.elapsedRealtime() - t0}ms")
                // Por si el callback de "completo" no llegó: vacía lo pendiente.
                onPcmComplete(false)
            } catch (t: Throwable) {
                dbgErr("synth: synthesize THREW", t)
                VocaliaLogger.e(TAG, "Error synthesize: ${t.message}", t as? Exception)
            }
        }
    }

    override fun stop() {
        dbg("stop()")
        generation.incrementAndGet()
        try { sharedSynth?.stop() } catch (t: Throwable) { dbgErr("stop: synth.stop THREW", t) }
        releaseEverything("stop()")
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
        dbg("shutdown() engine=${System.identityHashCode(this)}")
        shuttingDown = true
        stop()
        if (activeEngine === this) activeEngine = null
        initialized = false
        // OJO: no se cierra synthExecutor ni sharedSynth; son compartidos por todo el proceso.
    }
}