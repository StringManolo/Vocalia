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
            enterIdle()
        }
    }

    fun stop() {
        log("stop")
        cancelTimeouts()
        voskEngine.stopListening()
        ttsManager.stop()
    }

    // --- Estados ---

    private fun enterIdle() {
        log("enterIdle")
        state = State.IDLE
        cancelTimeouts()
        voskEngine.setMode(VoskEngine.Mode.WAKE)
        voskEngine.onResult = { json ->
            if (state == State.IDLE) {
                val (hasWake, remainder) = extractWake(json)
                if (hasWake) {
                    if (remainder.isNotBlank()) {
                        handleCommand(remainder)
                    } else {
                        enterListening()
                    }
                }
            }
        }
    }

    private fun enterListening() {
        log("enterListening")
        state = State.LISTENING
        voskEngine.pause()  // no escuchar mientras habla el TTS

        ttsManager.speak("te escucho") {
            if (state == State.LISTENING) {
                startCommandListening()
            }
        }
    }

    private fun startCommandListening() {
        log("startCommandListening")
        voskEngine.setMode(VoskEngine.Mode.COMMAND)
        voskEngine.onResult = { json ->
            if (state == State.LISTENING) {
                val text = extractText(json)
                if (text.isNotBlank()) {
                    handleCommand(text)
                }
            }
        }
        timeoutRunnable = Runnable {
            if (state == State.LISTENING) {
                log("timeout LISTENING")
                ttsManager.speak("tiempo agotado") { enterIdle() }
            }
        }.also { mainHandler.postDelayed(it, 5_000L) }
    }

    private fun handleCommand(text: String) {
        log("handleCommand: \"$text\"")
        cancelTimeouts()
        state = State.EXECUTING
        voskEngine.pause()

        // Red de seguridad: si el TTS nunca llama a onComplete, salimos igual.
        safetyRunnable = Runnable {
            if (state == State.EXECUTING) {
                log("safety timeout EXECUTING")
                enterIdle()
            }
        }.also { mainHandler.postDelayed(it, 30_000L) }

        when (val result = commandParser.parse(text)) {
            CommandResult.Sleep -> {
                ttsManager.speak("descanso") { enterIdle() }
            }
            is CommandResult.Response -> {
                ttsManager.speak(result.text) { enterIdle() }
            }
        }
    }

    // --- Utilidades ---

    /** Devuelve (hasWake, resto). "vocalia" → (true, ""); "vocalia que hora es" → (true, "que hora es"). */
    private fun extractWake(json: String): Pair<Boolean, String> {
        val text = extractText(json)
        if (text.isBlank()) return false to ""
        val words = text.split(" ").filter { it.isNotBlank() }
        val wakeIdx = words.indexOfFirst { it == "vocalia" || it == "vocalía" }
        if (wakeIdx < 0) return false to ""
        val remainder = if (wakeIdx == words.lastIndex) "" else words.drop(wakeIdx + 1).joinToString(" ")
        return true to remainder
    }

    private fun extractText(json: String): String {
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (_: Exception) { "" }
    }

    private fun cancelTimeouts() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyRunnable = null
    }
}
