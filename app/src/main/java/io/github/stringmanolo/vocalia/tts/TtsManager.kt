package io.github.stringmanolo.vocalia.tts

import android.content.Context
import io.github.stringmanolo.vocalia.util.InternalDebug
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private var speakThread: Thread? = null

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
        val chunks = splitIntoChunks(text)
        dbg("speak: ${chunks.size} chunks, ${text.length} chars")

        speakThread = Thread({
            var started = false
            try {
                for ((idx, chunk) in chunks.withIndex()) {
                    if (stopped) { dbg("speak: stop en chunk $idx"); break }
                    if (paused) {
                        engine.pause()
                        dbg("speak: pausado en chunk $idx")
                        while (paused && !stopped) Thread.sleep(100)
                        if (stopped) break
                        engine.resume()
                        dbg("speak: reanudado en chunk $idx")
                    }

                    if (!started) { started = true; onStart?.invoke() }

                    val latch = CountDownLatch(1)
                    engine.onComplete = { latch.countDown() }
                    engine.speak(chunk)
                    latch.await(120, TimeUnit.SECONDS)
                }
            } catch (t: Throwable) {
                dbg("speak: THREW ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                speaking = false
                paused = false
                stopped = false
                onComplete?.invoke()
            }
        }, "tts-chunk").start()
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

    fun stop() {
        dbg("stop")
        stopped = true
        paused = false
        currentEngine?.stop()
    }

    fun shutdown() {
        dbg("shutdown")
        stopped = true
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        espeakEngine = null
        sherpaEngine = null
        sherpaReady = false
        sherpaLoading = false
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= 200) return listOf(text)
        val sentences = text.split(Regex("(?<=[.,;:!?])\\s+")).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        var buf = StringBuilder()
        for (s in sentences) {
            if (buf.length + s.length > 200 && buf.isNotEmpty()) {
                chunks.add(buf.toString().trim())
                buf = StringBuilder()
            }
            buf.append(s).append(' ')
        }
        if (buf.isNotEmpty()) chunks.add(buf.toString().trim())
        return chunks.ifEmpty { listOf(text) }
    }
}
