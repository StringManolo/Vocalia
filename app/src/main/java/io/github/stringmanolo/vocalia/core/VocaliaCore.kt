package io.github.stringmanolo.vocalia.core

import android.os.Handler
import android.os.Looper
import io.github.stringmanolo.vocalia.VoskEngine
import io.github.stringmanolo.vocalia.tts.TtsManager
import org.json.JSONObject

class VocaliaCore(
    private val voskEngine: VoskEngine,
    private val ttsManager: TtsManager,
    private val commandParser: CommandParser,
    private val log: (String) -> Unit
) {
    private enum class State { IDLE, LISTENING, EXECUTING }

    private var state = State.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var safetyRunnable: Runnable? = null

    fun start() {
        log("start")
        voskEngine.init {
            voskEngine.onResult = { json -> handleResult(json) }
            voskEngine.onPartialResult = { /* ignorar */ }
            voskEngine.startListening()
            log("Vosk escuchando")
        }
    }

    fun stop() {
        log("stop")
        cancelTimeouts()
        voskEngine.stopListening()
        ttsManager.stop()
    }

    private fun enterIdle() {
        if (state == State.IDLE) return
        log("enterIdle")
        state = State.IDLE
        cancelTimeouts()
    }

    private fun enterListening() {
        log("enterListening")
        state = State.LISTENING
        cancelTimeouts()
        voskEngine.beep()
        scheduleListeningTimeout()
    }

    private fun scheduleListeningTimeout() {
        timeoutRunnable = Runnable {
            if (state == State.LISTENING) {
                log("timeout LISTENING")
                state = State.EXECUTING
                ttsManager.speak("tiempo agotado") { enterIdle() }
            }
        }.also { mainHandler.postDelayed(it, 5_000L) }
    }

    private fun handleResult(json: String) {
        val text = extractText(json)
        if (text.isBlank() || text == "[unk]") return
        log("result: \"$text\" (state=$state)")

        when (state) {
            State.IDLE -> {
                val (hasWake, remainder) = extractWake(text)
                if (hasWake) {
                    enterListening()
                    if (remainder.isNotBlank()) handleCommand(remainder)
                }
            }
            State.LISTENING -> {
                val (hasWake, remainder) = extractWake(text)
                if (hasWake) {
                    log("re-wake en LISTENING")
                    cancelTimeouts()
                    voskEngine.beep()
                    if (remainder.isNotBlank()) handleCommand(remainder)
                    else scheduleListeningTimeout()
                } else {
                    handleCommand(text)
                }
            }
            State.EXECUTING -> {
                log("ignorando result (EXECUTING)")
            }
        }
    }

    private fun handleCommand(text: String) {
        log("handleCommand: \"$text\"")
        cancelTimeouts()
        state = State.EXECUTING

        safetyRunnable = Runnable {
            if (state == State.EXECUTING) {
                log("safety timeout EXECUTING")
                enterIdle()
            }
        }.also { mainHandler.postDelayed(it, 60_000L) }

        when (val result = commandParser.parse(text)) {
            CommandResult.Sleep -> {
                ttsManager.speak("descanso") { enterIdle() }
            }
            is CommandResult.Response -> {
                ttsManager.speak(result.text) { enterIdle() }
            }
            is CommandResult.SetVoiceMode -> {
                if (result.intelligent) {
                    // Carga asíncrona: decir "cargando" con espeak y luego cambiar
                    ttsManager.speak("cargando voz inteligente") {
                        if (state != State.EXECUTING) return@speak
                        ttsManager.setEngineAsync(TtsManager.EngineMode.SHERPA) { ok, msg ->
                            mainHandler.post {
                                log("setEngineAsync(SHERPA): ok=$ok, msg=$msg")
                                if (state == State.EXECUTING) {
                                    ttsManager.speak(msg) { enterIdle() }
                                }
                            }
                        }
                    }
                } else {
                    // Espeak: cambio instantáneo
                    ttsManager.setEngineAsync(TtsManager.EngineMode.ESPEAK) { ok, msg ->
                        mainHandler.post {
                            log("setEngineAsync(ESPEAK): ok=$ok, msg=$msg")
                            if (state == State.EXECUTING) {
                                ttsManager.speak(msg) { enterIdle() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractWake(text: String): Pair<Boolean, String> {
        val words = text.split(" ").filter { it.isNotBlank() }
        val wakeIdx = words.indexOfFirst { it == "asistente" || it == "asistentes" }
        if (wakeIdx < 0) return false to ""
        val remainder = if (wakeIdx == words.lastIndex) "" else words.drop(wakeIdx + 1).joinToString(" ")
        return true to remainder
    }

    private fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "").trim()
    } catch (_: Exception) { "" }

    private fun cancelTimeouts() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyRunnable = null
    }
}
