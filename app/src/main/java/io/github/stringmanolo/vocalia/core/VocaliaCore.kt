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
        val WAKE_WORDS = listOf(
            "boca lía", "boca lia", "bocalia", "vocalía", "vocalia"
        )

        const val GRAMMAR_TTS_SPEAKING = """[
            "boca lia", "boca lía", "bocalia", "vocalia", "vocalía",
            "para", "pausa", "parar", "pausar", "stop", "silencio", "callate", "callá",
            "detente", "basta", "alto", "quieto",
            "boca lia para", "boca lia pausa", "boca lia stop",
            "boca lia detente", "boca lia silencio",
            "vocalia para", "vocalia pausa", "vocalia stop",
            "vocalia detente", "vocalia silencio",
            "[unk]"
        ]"""

        private const val HELP_TEXT =
            "Puedo hacer estas cosas. " +
            "Abrir una app: di vocalia abre, seguido del nombre. " +
            "Cerrar una app: di vocalia cierra, seguido del nombre. " +
            "Traer una app al frente: di vocalia, cierra esta app. " +
            "Saber la hora: di vocalia, qué hora es. " +
            "Saber la fecha: di vocalia, qué día es. " +
            "Cambiar a voz natural: di vocalia, usa voz inteligente. " +
            "Volver a voz robótica: di vocalia, usa voz por defecto. " +
            "Activar el modo escucha continua: di vocalia, escucha continua. " +
            "Desactivarlo: di vocalia, descansa. " +
            "Listar tus apps: di vocalia, lista de apps. " +
            "Que te lea todas las apps: di vocalia, lístame las apps. " +
            "Reanudar una lectura pausada: di vocalia, continúa. " +
            "Saludarme: di vocalia, hola. " +
            "Y repetir esto: di vocalia, ayuda."
    }

    private var state = State.IDLE
    private var returnStateAfterExecution = State.IDLE
    private var fullGrammar: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var safetyRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    /**
     * Si es true, Vosk se detiene por completo (deja de escuchar) hasta que el TTS termine.
     * Es exclusivo para "Ayuda": mientras dura, no se procesa ningún resultado de voz.
     */
    @Volatile private var isBlockingHelp = false

    private val resumeDelayMs = 10L
    private val listeningTimeoutMs = 5_000L
    private val safetyTimeoutMs = 600_000L

    private val allPauseWords = setOf(
        "para", "pausa", "parar", "pausar", "stop",
        "silencio", "callate", "callá", "detente", "basta", "alto", "quieto"
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
        isBlockingHelp = false
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
        isBlockingHelp = false
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
        log("Vosk escuchando (TTS_SPEAKING)")
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
            if (state != State.EXECUTING && state != State.TTS_SPEAKING &&
                state != State.TTS_PAUSED && !isBlockingHelp) {
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

    /**
     * Reproduce el TTS bloqueando completamente Vosk mientras dura. Se usa para "Ayuda".
     * Mientras `isBlockingHelp` sea true, Vosk no escucha y no procesa nada.
     */
    private fun speakBlocking(text: String, after: () -> Unit) {
        isBlockingHelp = true
        cancelTimeouts()
        voskEngine.stopListening()
        log("Vosk detenido por TTS bloqueante (Ayuda)")

        state = State.TTS_SPEAKING
        ttsManager.speak(
            text,
            onStart = {
                // Estado ya marcado; Vosk permanece detenido
                log("TTS bloqueante en curso")
            },
            onComplete = {
                isBlockingHelp = false
                state = State.EXECUTING
                after()
            }
        )
    }

    private fun handleResult(json: String) {
        val text = extractText(json)
        if (text.isBlank() || text == "[unk]") return

        // Si estamos reproduciendo la Ayuda, ignoramos cualquier resultado de voz.
        if (isBlockingHelp) {
            log("result: \"$text\" IGNORADO (isBlockingHelp)")
            return
        }

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
                val (hasWake, _) = extractWake(text)
                val textLower = text.lowercase()
                val words = textLower.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val userPauseWord = words.firstOrNull { it in allPauseWords }

                when {
                    userPauseWord == null ->
                        log("TTS_SPEAKING: no pause word, ignorando")
                    hasWake -> {
                        log("TTS_SPEAKING: pausa por '$userPauseWord' (con wake word)")
                        ttsManager.pause()
                        enterTtsPaused()
                    }
                    else -> {
                        // Sin wake word: rechazar para evitar eco del propio TTS
                        log("TTS_SPEAKING: '$userPauseWord' sin wake word, ignorando (anti-eco)")
                    }
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
                        if (ttsManager.resumePaused(
                                onStart = { enterTtsSpeaking() },
                                onComplete = { if (state == State.TTS_SPEAKING) resumeListeningWithDelay() }
                            )) {
                            // resumePaused ya lanzó el hilo; el estado pasa a TTS_SPEAKING vía onStart
                        } else {
                            log("TTS_PAUSED: no había nada que reanudar")
                            enterIdle()
                        }
                    }
                    CommandResult.Sleep, is CommandResult.ExitContinuous -> {
                        log("TTS_PAUSED: cancelar")
                        ttsManager.stop()
                        speakAndReturn("descanso") { enterIdle() }
                    }
                    else -> {
                        log("TTS_PAUSED: comando nuevo, se abandona la lectura pausada")
                        ttsManager.clearPausedSpeech()
                        ttsManager.stop()
                        handleCommand(command, State.IDLE)
                    }
                }
            }
            State.EXECUTING -> log("ignorando result (EXECUTING)")
        }
    }

    private fun handleCommand(text: String, returnTo: State) {
        log("handleCommand: \"$text\" returnTo=$returnTo")
        if (ttsManager.isSpeaking()) {
            log("handleCommand: cancelando TTS en curso")
            ttsManager.stop()
        }
        val parsed = commandParser.parse(text)
        val forceIdle = parsed is CommandResult.Sleep || parsed is CommandResult.ExitContinuous
        enterExecuting(if (forceIdle) State.IDLE else returnTo)

        when (parsed) {
            CommandResult.Sleep -> speakAndReturn("descanso") { enterIdle() }
            CommandResult.ExitContinuous -> speakAndReturn("escucha continua desactivada") { enterIdle() }
            CommandResult.EnterContinuous -> speakAndReturn("escucha continua activada") { enterContinuous() }
            CommandResult.PauseTts -> { log("PauseTts fuera de TTS_SPEAKING, ignorado"); afterExecution() }
            CommandResult.ResumeTts -> {
                // Si llegamos aquí sin estar en TTS_PAUSED pero hay lectura pendiente, reanudamos.
                if (ttsManager.hasPausedSpeech()) {
                    log("ResumeTts con buffer pendiente: reanudando")
                    if (ttsManager.resumePaused(
                            onStart = { enterTtsSpeaking() },
                            onComplete = { if (state == State.TTS_SPEAKING) resumeListeningWithDelay() }
                        )) {
                        // onStart ya cambió el estado
                    } else {
                        log("ResumeTts: resumePaused falló, ignorado")
                        afterExecution()
                    }
                } else {
                    log("ResumeTts fuera de TTS_PAUSED, ignorado")
                    afterExecution()
                }
            }
            CommandResult.Help -> {
                log("Help solicitado (bloqueante, Vosk detenido)")
                speakBlocking(HELP_TEXT) { afterExecution() }
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
                    is AppLauncher.Result.Ok -> "abriendo ${r.appName}"
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
                            is AppLauncher.Result.Ok -> "cerrando ${r.appName}"
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

    /** Matches wake word only at START of text. Returns (hasWake, remainder). */
    private fun extractWake(text: String): Pair<Boolean, String> {
        val lower = text.lowercase().trim()
        for (w in WAKE_WORDS) {
            if (lower.startsWith(w)) {
                val remainder = lower.substring(w.length).trim()
                return true to remainder
            }
        }
        return false to ""
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
