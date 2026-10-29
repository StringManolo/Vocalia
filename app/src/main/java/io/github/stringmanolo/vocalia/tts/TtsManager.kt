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

    private fun dbg(msg: String) {
        InternalDebug.mark("[${Thread.currentThread().name}] TtsManager: $msg")
    }

    fun init() {
        val espeak = EspeakNgEngine(context)
        espeakEngine = espeak
        espeak.init(
            onReady = {
                dbg("EspeakNg listo (default)")
                currentEngine = espeak
                currentMode = EngineMode.ESPEAK
                onEngineReady?.invoke(espeak)
                // Sherpa NO se carga aquí. Se carga la primera vez que se pide.
            },
            onError = { error ->
                dbg("EspeakNg falló al inicializar: $error")
            }
        )
    }

    /**
     * Carga Sherpa bajo demanda si no está cargado.
     * @return true si está listo para usarse.
     */
    private fun ensureSherpaLoaded(): Boolean {
        if (sherpaReady) return true
        if (sherpaLoading) {
            // Otra llamada ya está cargando. Esperar hasta 15 s.
            val t0 = System.currentTimeMillis()
            while (sherpaLoading && System.currentTimeMillis() - t0 < 15_000L) {
                Thread.sleep(100)
            }
            return sherpaReady
        }

        sherpaLoading = true
        try {
            dbg("Sherpa: carga bajo demanda START")
            val sherpa = SherpaOnnxEngine(context)
            sherpaEngine = sherpa

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)

            sherpa.init(
                onReady = {
                    ok.set(true)
                    sherpaReady = true
                    latch.countDown()
                },
                onError = { err ->
                    dbg("Sherpa: no disponible -> $err")
                    sherpaEngine = null
                    sherpaReady = false
                    latch.countDown()
                }
            )

            val done = latch.await(15, TimeUnit.SECONDS)
            if (!done) {
                dbg("Sherpa: timeout esperando init (>15s)")
            }
            return ok.get()
        } catch (t: Throwable) {
            dbg("Sherpa: ensureSherpaLoaded THREW ${t.javaClass.simpleName}: ${t.message}")
            sherpaEngine = null
            sherpaReady = false
            return false
        } finally {
            sherpaLoading = false
        }
    }

    fun setEngine(mode: EngineMode): Pair<Boolean, String> {
        return when (mode) {
            EngineMode.ESPEAK -> {
                val e = espeakEngine
                if (e != null) {
                    currentEngine = e
                    currentMode = EngineMode.ESPEAK
                    dbg("Motor cambiado a EspeakNg")
                    true to "voz por defecto activada"
                } else {
                    dbg("setEngine(ESPEAK) sin motor")
                    false to "espeak no disponible"
                }
            }
            EngineMode.SHERPA -> {
                if (!ensureSherpaLoaded()) {
                    dbg("setEngine(SHERPA): no se pudo cargar")
                    return false to "voz inteligente no disponible"
                }
                val s = sherpaEngine
                if (s != null) {
                    currentEngine = s
                    currentMode = EngineMode.SHERPA
                    dbg("Motor cambiado a SherpaOnnx")
                    true to "voz inteligente activada"
                } else {
                    false to "voz inteligente no disponible"
                }
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val engine = currentEngine
        if (engine == null) {
            dbg("speak sin motor")
            onComplete?.invoke()
            return
        }
        dbg("speak(\"$text\") con ${engine.name}")
        engine.onComplete = onComplete
        engine.speak(text)
    }

    fun stop() {
        currentEngine?.onComplete = null
        currentEngine?.stop()
    }

    fun shutdown() {
        dbg("shutdown")
        try { espeakEngine?.shutdown() } catch (_: Throwable) {}
        try { sherpaEngine?.shutdown() } catch (_: Throwable) {}
        currentEngine = null
        espeakEngine = null
        sherpaEngine = null
        sherpaReady = false
    }
}
