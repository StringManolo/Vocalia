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
    private val appLauncher: AppLauncher,
    private val log: (String) -> Unit
) {
    private enum class State { IDLE, LISTENING, EXECUTING, CONTINUOUS, TTS_SPEAKING, TTS_PAUSED }

    companion object {
        const val GRAMMAR_TTS_SPEAKING = """[
            "asistente", "asistentes",
            "para", "pausa", "parar", "pausar", "stop", "silencio", "callate",
            "asistente para", "asistente pausa", "asistente parar", "asistente pausar",
            "asistente stop", "asistente silencio", "asistente callate",
            "asistente para el tts", "asistente pausa el tts",
            "asistente para el texto", "asistente pausa el texto",
            "asistente para la lectura", "asistente pausa la lectura",
            "asistente pausa lectura", "asistente pausa voz",
            "[unk]"
        ]"""

        private const val HELP_TEXT =
            "Puedo hacer muchas cosas. Te las resumo por categorías. " +
            "Para abrir una app: di, asistente, abre, seguido del nombre, o simplemente asistente y el nombre de la app. " +
            "Para cerrar una app: asistente, cierra, seguido del nombre. O asistente, cierra esta app. " +
            "Para saber la hora: asistente, qué hora es. " +
            "Para saber la fecha: asistente, qué día es. " +
            "Para cambiar a voz natural: asistente, usa voz inteligente. " +
            "Para volver a voz robótica: asistente, usa voz por defecto. " +
            "Para activar el modo escucha continua: asistente, escucha continua. " +
            "Para desactivarlo: asistente, descansa. " +
            "Para listar tus apps: asistente, lista de apps. " +
            "Para que te lea todas las apps: asistente, lístame las apps. " +
            "Para pausar una lectura: asistente, para, o asistente, pausa. " +
            "Para reanudar una lectura: asistente, continúa. " +
            "Para saludarme: asistente, hola, o asistente, cómo estás. " +
            "Y para escuchar esto otra vez: asistente, ayuda."
    }

    private var state = State.IDLE
    private var returnStateAfterExecution = State.IDLE
    private var fullGrammar: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var safetyRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    private val resumeDelayMs = 10L
    private val listeningTimeoutMs = 5_000L
    private val safetyTimeoutMs = 600_000L

    private val pauseWords = setOf(
        "para", "pausa", "parar", "pausar", "stop", "silencio", "callate", "callá"
    )

    fun start(grammar: String) {
        log("start")
        fullGrammar = grammar
        voskEngine.init(grammar) {
            voskEngine.onResult = { json -> handleResult(json) }
            voskEngine.onPartialResult = { }
            state = State.IDLE
            voskEngine.startListening()
            log("Vosk escuchando (IDLE)")
        }
    }

    fun stop() {
        log("stop")
        cancelTimeouts()
        voskEngine.stopListening()
        ttsManager.stop()
    }

    private fun enterIdle(speak: String? = null) {
        log("enterIdle")
        state = State.IDLE
        cancelTimeouts()
        voskEngine.stopListening()
        voskEngine.setGrammar(fullGrammar)
        if (speak != null) {
            ttsManager.speak(speak,
                onStart = { enterTtsSpeaking() },
                onComplete = { if (state == State.IDLE) resumeListeningWithDelay() })
        } else {
            resumeListeningWithDelay()
        }
    }

    private fun enterContinuous(speak: String? = null) {
        log("enterContinuous")
        state = State.CONTINUOUS
        cancelTimeouts()
        voskEngine.stopListening()
        voskEngine.setGrammar(fullGrammar)
        if (speak != null) {
            ttsManager.speak(speak,
                onStart = { enterTtsSpeaking() },
                onComplete = { if (state == State.CONTINUOUS) resumeListeningWithDelay() })
        } else {
            resumeListeningWithDelay()
        }
    }

    private fun enterListening() {
        log("enterListening")
        state = State.LISTENING
        cancelTimeouts()
        voskEngine.beep()
        timeoutRunnable = Runnable {
            if (state == State.LISTENING) {
                log("timeout LISTENING")
                enterExecuting(State.IDLE)
                speakAndReturn("tiempo agotado") { enterIdle() }
            }
        }.also { mainHandler.postDelayed(it, listeningTimeoutMs) }
    }

    private fun enterExecuting(returnTo: State) {
        log("enterExecuting returnTo=$returnTo")
        returnStateAfterExecution = returnTo
        state = State.EXECUTING
        cancelTimeouts()
        voskEngine.stopListening()
        log("Vosk pausado durante EXECUTING")
        safetyRunnable = Runnable {
            if (state == State.EXECUTING) {
                log("safety timeout EXECUTING")
                afterExecution()
            }
        }.also { mainHandler.postDelayed(it, safetyTimeoutMs) }
    }

    private fun afterExecution() {
        when (returnStateAfterExecution) {
            State.CONTINUOUS -> enterContinuous()
            State.TTS_PAUSED -> enterTtsPaused()
            else -> enterIdle()
        }
    }

    private fun enterTtsSpeaking() {
        if (state == State.TTS_SPEAKING) return
        log("enterTtsSpeaking")
        state = State.TTS_SPEAKING
        cancelTimeouts()
        voskEngine.stopListening()
        voskEngine.setGrammar(GRAMMAR_TTS_SPEAKING)
        voskEngine.startListening()
        log("Vosk escuchando (TTS_SPEAKING, gramática restringida)")
    }

    private fun enterTtsPaused() {
        log("enterTtsPaused")
        state = State.TTS_PAUSED
        cancelTimeouts()
        voskEngine.stopListening()
        voskEngine.setGrammar(fullGrammar)
        voskEngine.startListening()
        log("Vosk escuchando (TTS_PAUSED, gramática completa)")
    }

    private fun resumeListeningWithDelay() {
        resumeRunnable = Runnable {
            if (state != State.EXECUTING && state != State.TTS_SPEAKING && state != State.TTS_PAUSED) {
                voskEngine.startListening()
                log("Vosk reanudado tras ${resumeDelayMs}ms (state=$state)")
            }
        }.also { mainHandler.postDelayed(it, resumeDelayMs) }
    }

    private fun speakAndReturn(text: String, after: () -> Unit) {
        ttsManager.speak(text,
            onStart = { enterTtsSpeaking() },
            onComplete = {
                if (state == State.TTS_SPEAKING) {
                    state = State.EXECUTING
                    after()
                }
            })
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
                    if (remainder.isNotBlank()) handleCommand(remainder, State.IDLE)
                }
            }
            State.LISTENING -> {
                val (hasWake, remainder) = extractWake(text)
                if (hasWake && remainder.isNotBlank()) {
                    cancelTimeouts(); voskEngine.beep(); handleCommand(remainder, State.IDLE)
                } else if (hasWake) {
                    cancelTimeouts(); voskEngine.beep(); enterListening()
                } else {
                    handleCommand(text, State.IDLE)
                }
            }
            State.CONTINUOUS -> {
                val (hasWake, remainder) = extractWake(text)
                val command = if (hasWake && remainder.isNotBlank()) remainder else text
                handleCommand(command, State.CONTINUOUS)
            }
            State.TTS_SPEAKING -> {
                val textLower = text.lowercase()
                val hasWake = textLower.contains("asistente") || textLower.contains("asistentes")
                val cleanText = textLower
                    .replace("asistentes", "")
                    .replace("asistente", "")
                    .trim()
                val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val isPause = hasWake && words.any { it in pauseWords }
                if (isPause) {
                    log("TTS_SPEAKING: pausa detectada en \"$text\"")
                    ttsManager.pause()
                    enterTtsPaused()
                } else {
                    log("TTS_SPEAKING: comando no permitido, ignorando (hasWake=$hasWake)")
                }
            }
            State.TTS_PAUSED -> {
                val (hasWake, remainder) = extractWake(text)
                val command = when {
                    hasWake && remainder.isNotBlank() -> remainder
                    hasWake -> ""
                    else -> text
                }
                if (command.isBlank()) return
                val parsed = commandParser.parse(command)
                when (parsed) {
                    is CommandResult.ResumeTts -> {
                        log("TTS_PAUSED: reanudar")
                        ttsManager.resume()
                        enterTtsSpeaking()
                    }
                    CommandResult.Sleep, is CommandResult.ExitContinuous -> {
                        log("TTS_PAUSED: cancelar")
                        ttsManager.stop()
                        speakAndReturn("descanso") { enterIdle() }
                    }
                    else -> {
                        log("TTS_PAUSED: comando normal, se ejecuta y vuelve a pausa")
                        handleCommand(command, State.TTS_PAUSED)
                    }
                }
            }
            State.EXECUTING -> log("ignorando result (EXECUTING)")
        }
    }

    private fun handleCommand(text: String, returnTo: State) {
        log("handleCommand: \"$text\" returnTo=$returnTo")
        val parsed = commandParser.parse(text)
        val forceIdle = parsed is CommandResult.Sleep || parsed is CommandResult.ExitContinuous
        enterExecuting(if (forceIdle) State.IDLE else returnTo)

        when (parsed) {
            CommandResult.Sleep -> speakAndReturn("descanso") { enterIdle() }
            CommandResult.ExitContinuous -> speakAndReturn("escucha continua desactivada") { enterIdle() }
            CommandResult.EnterContinuous -> speakAndReturn("escucha continua activada") { enterContinuous() }
            CommandResult.PauseTts -> { log("PauseTts fuera de TTS_SPEAKING, ignorado"); afterExecution() }
            CommandResult.ResumeTts -> { log("ResumeTts fuera de TTS_PAUSED, ignorado"); afterExecution() }
            CommandResult.Help -> {
                log("Help solicitado")
                speakAndReturn(HELP_TEXT) { afterExecution() }
            }
            CommandResult.ListApps -> {
                val all = appLauncher.getAllAppsForTTS()
                log("ListApps: ${all.size} apps")
                val msg = if (all.isEmpty()) "no tengo apps instaladas"
                else "tengo ${all.size} apps instaladas. Son: ${all.joinToString(", ")}"
                speakAndReturn(msg) { afterExecution() }
            }
            CommandResult.ListAppsVerbose -> {
                val all = appLauncher.getAllAppsForTTS()
                log("ListAppsVerbose: ${all.size} apps")
                all.forEach { app ->
                    val aliases = appLauncher.getAliasesForApp(app)
                    if (aliases.isEmpty()) log("ListAppsVerbose:   - $app")
                    else log("ListAppsVerbose:   - $app (di: ${aliases.joinToString(" / ")})")
                }
                val msg = if (all.isEmpty()) "no tengo apps instaladas"
                else "tengo ${all.size} apps instaladas. Son: ${all.joinToString(", ")}"
                speakAndReturn(msg) { afterExecution() }
            }
            is CommandResult.Response -> speakAndReturn(parsed.text) { afterExecution() }
            is CommandResult.SetVoiceMode -> {
                if (parsed.intelligent) {
                    ttsManager.speak("cargando voz inteligente",
                        onStart = { enterTtsSpeaking() },
                        onComplete = {
                            ttsManager.setEngineAsync(TtsManager.EngineMode.SHERPA) { ok, msg ->
                                mainHandler.post {
                                    log("setEngine(SHERPA): ok=$ok, msg=$msg")
                                    speakAndReturn(msg) { afterExecution() }
                                }
                            }
                        })
                } else {
                    ttsManager.setEngineAsync(TtsManager.EngineMode.ESPEAK) { ok, msg ->
                        mainHandler.post {
                            log("setEngine(ESPEAK): ok=$ok, msg=$msg")
                            speakAndReturn(msg) { afterExecution() }
                        }
                    }
                }
            }
            is CommandResult.OpenApp -> {
                val r = appLauncher.launch(parsed.appName)
                val message = when (r) {
                    is AppLauncher.Result.Ok -> "abriendo ${parsed.appName}"
                    is AppLauncher.Result.NotFound -> "no encuentro la app ${parsed.appName}"
                    is AppLauncher.Result.Error -> "no pude abrir ${parsed.appName}"
                }
                log("OpenApp(\"${parsed.appName}\") -> $message")
                speakAndReturn(message) { afterExecution() }
            }
            is CommandResult.CloseApp -> {
                val pkg = appLauncher.findPackage(parsed.appName)
                if (pkg == null) {
                    speakAndReturn("no encuentro la app ${parsed.appName}") { afterExecution() }
                } else {
                    log("CloseApp(\"${parsed.appName}\") pkg=$pkg")
                    appLauncher.bringVocaliaToFront()
                    mainHandler.postDelayed({
                        val r = appLauncher.closeApp(parsed.appName)
                        val message = when (r) {
                            is AppLauncher.Result.Ok -> "cerrando ${parsed.appName}"
                            is AppLauncher.Result.NotFound -> "no encuentro la app ${parsed.appName}"
                            is AppLauncher.Result.Error -> "no pude cerrar ${parsed.appName}"
                        }
                        log("CloseApp(\"${parsed.appName}\") -> $message")
                        speakAndReturn(message) { afterExecution() }
                    }, 600)
                }
            }
            CommandResult.CloseForegroundApp -> {
                log("CloseForegroundApp: trayendo Vocalia al frente")
                appLauncher.bringVocaliaToFront()
                speakAndReturn("cerrando app actual") { afterExecution() }
            }
        }
    }

    private fun extractWake(text: String): Pair<Boolean, String> {
        val words = text.split(" ").filter { it.isNotBlank() }
        val wakeIdx = words.indexOfFirst { it == "asistente" || it == "asistentes" }
        if (wakeIdx < 0) return false to ""
        return true to (if (wakeIdx == words.lastIndex) "" else words.drop(wakeIdx + 1).joinToString(" "))
    }

    private fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "").trim()
    } catch (_: Exception) { "" }

    private fun cancelTimeouts() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }; timeoutRunnable = null
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }; safetyRunnable = null
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }; resumeRunnable = null
    }
}
