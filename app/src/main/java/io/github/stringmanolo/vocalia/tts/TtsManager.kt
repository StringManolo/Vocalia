package io.github.stringmanolo.vocalia.tts

import android.content.Context
import io.github.stringmanolo.vocalia.util.InternalDebug
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gestor de TTS con chunking por frase.
 *
 * Cada chunk es una unidad reanudable: al pausar se guarda desde el chunk actual en adelante
 * en `pendingChunks`. Al reanudar, se re-lee el chunk actual desde su inicio (la frase completa).
 * Esto permite en el futuro: repetir última frase, saltar N frases, etc.
 */
class TtsManager(private val context: Context) {

    enum class EngineMode { ESPEAK, SHERPA }

    var onEngineReady: ((TtsEngine) -> Unit)? = null

    @Volatile private var currentMode: EngineMode = EngineMode.ESPEAK
    @Volatile private var currentEngine: TtsEngine? = null

    private var espeakEngine: EspeakNgEngine? = null
    private var sherpaEngine: SherpaOnnxEngine? = null
    @Volatile private var sherpaReady = false
    @Volatile private var sherpaLoading = false

    @Volatile private var speaking = false
    @Volatile private var paused = false
    @Volatile private var stopped = false
    @Volatile private var speakThread: Thread? = null
    @Volatile private var currentChunkText: String? = null

    /** Lista completa de chunks de la lectura actual (para navegación futura). */
    @Volatile private var chunks: List<String> = emptyList()
    /** Índice del chunk que se está reproduciendo (o se iba a reproducir). */
    @Volatile private var currentChunkIndex: Int = -1
    /** Chunks pendientes cuando se pausó (incluye el chunk en curso). */
    @Volatile private var pendingChunks: List<String> = emptyList()
    /** Índice del primer chunk pendiente dentro de `chunks`. */
    @Volatile private var pendingStartIndex: Int = -1

    private fun dbg(msg: String) { InternalDebug.mark("[${Thread.currentThread().name}] TtsManager: $msg") }

    fun init() {
        val espeak = EspeakNgEngine(context)
        espeakEngine = espeak
        espeak.init(
            onReady = {
                dbg("EspeakNg listo")
                currentEngine = espeak
                currentMode = EngineMode.ESPEAK
                onEngineReady?.invoke(espeak)
            },
            onError = { dbg("EspeakNg falló: $it") }
        )
    }

    fun setEngineAsync(mode: EngineMode, callback: (Boolean, String) -> Unit) {
        when (mode) {
            EngineMode.ESPEAK -> {
                val e = espeakEngine
                if (e != null) {
                    currentEngine = e
                    currentMode = EngineMode.ESPEAK
                    callback(true, "voz por defecto activada")
                } else callback(false, "espeak no disponible")
            }
            EngineMode.SHERPA -> {
                if (sherpaReady) {
                    currentEngine = sherpaEngine
                    currentMode = EngineMode.SHERPA
                    callback(true, "voz inteligente activada")
                    return
                }
                if (sherpaLoading) { callback(false, "voz inteligente cargándose"); return }
                sherpaLoading = true
                Thread({
                    try {
                        val sherpa = SherpaOnnxEngine(context)
                        sherpa.init(
                            onReady = {
                                sherpaEngine = sherpa
                                sherpaReady = true
                                sherpaLoading = false
                                currentEngine = sherpa
                                currentMode = EngineMode.SHERPA
                                callback(true, "voz inteligente activada")
                            },
                            onError = {
                                sherpaEngine = null
                                sherpaReady = false
                                sherpaLoading = false
                                callback(false, "voz inteligente no disponible")
                            }
                        )
                    } catch (t: Throwable) {
                        sherpaEngine = null
                        sherpaReady = false
                        sherpaLoading = false
                        callback(false, "voz inteligente no disponible")
                    }
                }, "sherpa-load").start()
            }
        }
    }

    /**
     * Habla [text] troceado por frases. Cada frase es un chunk reanudable.
     */
    fun speak(text: String, onStart: (() -> Unit)? = null, onComplete: (() -> Unit)? = null) {
        if (speaking) {
            dbg("speak: ya hablando, ignorando")
            onComplete?.invoke()
            return
        }
        val engine = currentEngine ?: run { onComplete?.invoke(); return }

        speaking = true
        paused = false
        stopped = false
        pendingChunks = emptyList()
        pendingStartIndex = -1

        chunks = splitIntoChunks(text)
        currentChunkIndex = 0
        dbg("speak: ${chunks.size} chunks, ${text.length} chars")

        launchSpeakThread(engine, startIndex = 0, onStart, onComplete)
    }

    /**
     * Reanuda la lectura pausada desde el chunk pendiente.
     * Devuelve true si había algo que reanudar.
     */
    fun resumePaused(onStart: (() -> Unit)? = null, onComplete: (() -> Unit)? = null): Boolean {
        val pending = pendingChunks
        if (pending.isEmpty() || pendingStartIndex < 0) {
            dbg("resumePaused: no hay chunks pendientes")
            return false
        }
        if (speaking) {
            dbg("resumePaused: ya hablando, ignorando")
            return false
        }
        val engine = currentEngine ?: return false

        dbg("resumePaused: desde chunk $pendingStartIndex, ${pending.size} pendientes")
        val start = pendingStartIndex
        pendingChunks = emptyList()
        pendingStartIndex = -1
        speaking = true
        paused = false
        stopped = false
        currentChunkIndex = start
        launchSpeakThread(engine, startIndex = start, onStart, onComplete)
        return true
    }

    private fun launchSpeakThread(
        engine: TtsEngine,
        startIndex: Int,
        onStart: (() -> Unit)?,
        onComplete: (() -> Unit)?
    ) {
        val localChunks = chunks
        val th = Thread({
            var started = false
            try {
                var idx = startIndex
                while (idx < localChunks.size) {
                    if (stopped) { dbg("speak: stop en chunk $idx"); break }

                    if (paused) {
                        engine.pause()
                        currentChunkIndex = idx
                        pendingChunks = localChunks.subList(idx, localChunks.size)
                        pendingStartIndex = idx
                        dbg("speak: pausado antes de chunk $idx (pendientes=${pendingChunks.size})")
                        while (paused && !stopped) Thread.sleep(100)
                        if (stopped) break
                        engine.resume()
                        pendingChunks = emptyList()
                        pendingStartIndex = -1
                        dbg("speak: reanudado en chunk $idx")
                    }

                    currentChunkIndex = idx
                    val chunk = localChunks[idx]
                    currentChunkText = chunk
                    if (!started) { started = true; onStart?.invoke() }

                    val latch = CountDownLatch(1)
                    engine.onComplete = { latch.countDown() }
                    engine.speak(chunk)
                    latch.await(180, TimeUnit.SECONDS)

                    // ¿Se pausó durante este chunk? Guardar desde aquí (incluido él) y esperar.
                    if (paused && !stopped) {
                        pendingChunks = localChunks.subList(idx, localChunks.size)
                        pendingStartIndex = idx
                        dbg("speak: pausado durante chunk $idx (pendientes=${pendingChunks.size})")
                        while (paused && !stopped) Thread.sleep(100)
                        if (stopped) break
                        engine.resume()
                        pendingChunks = emptyList()
                        pendingStartIndex = -1
                        dbg("speak: reanudado tras chunk $idx (re-lee)")
                        // Re-lee este mismo chunk
                        continue
                    }

                    idx++
                }
            } catch (t: Throwable) {
                dbg("speak: THREW ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                currentChunkText = null
                speaking = false
                paused = false
                stopped = false
                pendingChunks = emptyList()
                pendingStartIndex = -1
                onComplete?.invoke()
            }
        }, "tts-chunk")
        speakThread = th
        th.start()
    }

    fun pause() {
        dbg("pause")
        paused = true
        currentEngine?.pause()
    }

    fun resume() {
        dbg("resume")
        paused = false
        currentEngine?.resume()
    }

    fun isSpeaking(): Boolean = speaking
    fun isPaused(): Boolean = paused
    fun getCurrentChunkText(): String? = currentChunkText

    /** ¿Hay chunks pendientes de reanudar? */
    fun hasPausedSpeech(): Boolean = pendingChunks.isNotEmpty()

    /** Descarta los chunks pendientes (al abandonar la lectura pausada). */
    fun clearPausedSpeech() {
        pendingChunks = emptyList()
        pendingStartIndex = -1
    }

    // --- API preparada para el futuro (navegación por frases) ---

    fun getChunks(): List<String> = chunks
    fun getCurrentChunkIndex(): Int = currentChunkIndex
    fun getCurrentChunk(): String? = currentChunkText

    fun stop() {
        dbg("stop")
        stopped = true
        paused = false
        pendingChunks = emptyList()
        pendingStartIndex = -1
        chunks = emptyList()
        currentChunkIndex = -1
        currentEngine?.stop()
        try { speakThread?.join(2000) } catch (_: InterruptedException) {}
        currentChunkText = null
    }

    fun shutdown() {
        dbg("shutdown")
        stopped = true
        pendingChunks = emptyList()
        pendingStartIndex = -1
        try { speakThread?.join(2000) } catch (_: InterruptedException) {}
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        espeakEngine = null
        sherpaEngine = null
        sherpaReady = false
        sherpaLoading = false
    }

    /**
     * Trocea el texto en frases. Una frase puede contener varias sub-frases si el bloque
     * es muy corto (para no generar pausas antinaturales), y se corta en el último delimitador
     * fuerte si excede [MAX_CHUNK_CHARS].
     */
    private fun splitIntoChunks(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()

        // 1) Frases terminadas en . ! ? (se comen los espacios)
        val sentences = Regex("[^.!?]+[.!?]+").findAll(clean)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val base: List<String> = if (sentences.isEmpty()) listOf(clean) else sentences

        // 2) Une frases muy cortas y parte frases muy largas
        val out = mutableListOf<String>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(buf.toString().trim())
                buf.clear()
            }
        }

        for (s in base) {
            if (s.length > MAX_CHUNK_CHARS) {
                flush()
                // Parte por , ; : o por espacio, en trozos <= MAX_CHUNK_CHARS
                var rest = s
                while (rest.length > MAX_CHUNK_CHARS) {
                    val cut = lastSplit(rest, MAX_CHUNK_CHARS)
                    out.add(rest.substring(0, cut).trim())
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) out.add(rest)
                continue
            }
            if (buf.length + s.length + 1 <= MAX_CHUNK_CHARS) {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            } else {
                flush()
                buf.append(s)
            }
        }
        flush()
        return out.ifEmpty { listOf(clean) }
    }

    private fun lastSplit(s: String, limit: Int): Int {
        val window = s.substring(0, limit)
        val cut = listOf(',', ';', ':', ' ').map { window.lastIndexOf(it) }.maxOrNull() ?: -1
        return if (cut > 0) cut + 1 else limit
    }

    companion object {
        /** Tamaño máximo de un chunk. Si se supera, se parte por , ; : o espacio. */
        private const val MAX_CHUNK_CHARS = 240
    }
}
