package io.github.stringmanolo.vocalia.core

import android.os.Handler
import android.os.Looper
import io.github.stringmanolo.vocalia.VocaliaAccessibilityService
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
    private enum class State {
        IDLE, LISTENING, EXECUTING, CONTINUOUS,
        TTS_SPEAKING, TTS_PAUSED, UI_NAVIGATION
    }

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

        const val GRAMMAR_UI_NAVIGATION = """[
            "vocalia", "vocalía", "boca lia", "boca lía", "bocalia",
            "siguiente", "avanza", "siguiente elemento", "el siguiente",
            "anterior", "retrocede", "anterior elemento", "el anterior",
            "lee", "leer", "lee elemento", "leer elemento", "léelo", "leelo",
            "detalle", "detalles", "describe", "describelo", "descríbelo",
            "más detalle", "mas detalle", "más información", "mas informacion",
            "más información del elemento", "mas informacion del elemento",
            "pulsa", "pulsar", "toca", "tocar", "activar elemento", "activa elemento",
            "click", "clic",
            "atrás", "atras", "back", "regresa", "volver atrás", "vuelve atrás",
            "arrastra arriba", "arrastra abajo", "arrastra izquierda", "arrastra derecha",
            "arrastra elemento arriba", "arrastra elemento abajo",
            "arrastra elemento izquierda", "arrastra elemento derecha",
            "scroll arriba", "scroll abajo", "desplaza arriba", "desplaza abajo",
            "sube", "baja",
            "pega", "pegar", "portapapeles pegar", "pega el portapapeles",
            "sal del modo usar", "salir del modo usar", "salir del modo",
            "desactiva el modo usar", "termina el modo usar", "fin del modo usar",
            "descansa", "descanso", "descansar", "duerme", "dormir",
            "para", "pausa", "silencio", "callate", "stop",
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
            "Y repetir esto: di vocalia, ayuda. " +
            "Para navegar por la pantalla: di vocalia, modo usar. " +
            "Y dentro de él: siguiente, anterior, lee elemento, detalle, pulsa, atrás, " +
            "arrastra arriba, arrastra abajo, arrastra izquierda, arrastra derecha, " +
            "portapapeles pegar. Sal con vocalia, sal del modo usar."
    }

    private var state = State.IDLE
    private var returnStateAfterExecution = State.IDLE
    private var fullGrammar: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var safetyRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null
    private var uiTimeoutRunnable: Runnable? = null

    @Volatile private var isBlockingHelp = false

    /** true mientras la escucha continua esté activada (también dentro del modo usar). */
    private var continuousEnabled = false

    /** Instante hasta el que se acepta un comando sin wake word tras decir solo "vocalia" en modo usar. */
    private var uiWakeArmedUntil = 0L

    private val resumeDelayMs = 10L
    private val listeningTimeoutMs = 5_000L
    private val safetyTimeoutMs = 600_000L
    private val uiNavigationTimeoutMs = 90_000L

    private val allPauseWords = setOf(
        "para", "pausa", "parar", "pausar", "stop",
        "silencio", "callate", "callá", "detente", "basta", "alto", "quieto"
    )

    // ----------------------------------------------------------------
    // Ciclo de vida
    // ----------------------------------------------------------------

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

    // ----------------------------------------------------------------
    // Transiciones de estado
    // ----------------------------------------------------------------

    private fun enterIdle(speak: String? = null) {
        log("enterIdle")
        state = State.IDLE
        continuousEnabled = false
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
        continuousEnabled = true
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

    /**
     * Sale del modo usar: vuelve a escucha continua si estaba activada, o a IDLE.
     */
    private fun leaveUiMode(speak: String? = null) {
        if (continuousEnabled) enterContinuous(speak) else enterIdle(speak)
    }

    /**
     * Modo usar: escucha con gramática reducida y expira por inactividad a los
     * [uiNavigationTimeoutMs] ms. Requiere wake word salvo en escucha continua.
     *
     * @param refreshList true solo al ENTRAR al modo o cuando la pantalla ha podido
     *                    cambiar (pulsar, atrás, scroll). En el resto de casos NO se
     *                    refresca, para conservar la posición del elemento actual.
     */
    private fun enterUiNavigation(refreshList: Boolean = false) {
        log("enterUiNavigation refreshList=$refreshList")
        state = State.UI_NAVIGATION
        isBlockingHelp = false
        uiWakeArmedUntil = 0L
        cancelTimeouts()
        voskEngine.stopListening()
        voskEngine.setGrammar(GRAMMAR_UI_NAVIGATION)
        voskEngine.startListening()

        if (refreshList) {
            mainHandler.post {
                try {
                    VocaliaAccessibilityService.instance?.refreshNodeList()
                    log("UI_NAVIGATION: lista de nodos refrescada")
                } catch (t: Throwable) {
                    log("UI_NAVIGATION: refresh THREW ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        uiTimeoutRunnable = Runnable {
            if (state == State.UI_NAVIGATION) {
                log("UI_NAVIGATION timeout por inactividad")
                speakAndReturn("saliendo del modo usar por inactividad") { leaveUiMode() }
            }
        }.also { mainHandler.postDelayed(it, uiNavigationTimeoutMs) }
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
            State.UI_NAVIGATION -> enterUiNavigation()
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

    // ----------------------------------------------------------------
    // Helpers de TTS + accesibilidad
    // ----------------------------------------------------------------

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

    private fun speakBlocking(text: String, after: () -> Unit) {
        isBlockingHelp = true
        cancelTimeouts()
        voskEngine.stopListening()
        log("Vosk detenido por TTS bloqueante (Ayuda)")
        state = State.TTS_SPEAKING
        ttsManager.speak(
            text,
            onStart = { log("TTS bloqueante en curso") },
            onComplete = {
                isBlockingHelp = false
                state = State.EXECUTING
                after()
            }
        )
    }

    /**
     * Ejecuta una acción de accesibilidad (que debe correr en el hilo principal)
     * y devuelve el texto que produce al hilo del TTS.
     * Si el servicio no está disponible, sale del modo usar.
     */
    private fun a11yCommand(
        refreshAfter: Boolean = false,
        action: (VocaliaAccessibilityService) -> String
    ) {
        mainHandler.post {
            val svc = VocaliaAccessibilityService.instance
            if (svc == null) {
                log("a11y: servicio no disponible")
                speakAndReturn("servicio de accesibilidad no disponible, actívalo en ajustes") {
                    leaveUiMode()
                }
                return@post
            }
            val msg = try {
                action(svc)
            } catch (t: Throwable) {
                log("a11y THREW ${t.javaClass.simpleName}: ${t.message}")
                "error de accesibilidad"
            }
            log("a11y result: \"$msg\"")
            speakAndReturn(msg) {
                if (state == State.EXECUTING) enterUiNavigation(refreshList = refreshAfter)
                else enterIdle()
            }
        }
    }

    // ----------------------------------------------------------------
    // Procesamiento de resultados Vosk
    // ----------------------------------------------------------------

    private fun handleResult(json: String) {
        val text = extractText(json)
        if (text.isBlank() || text == "[unk]") return

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
            State.UI_NAVIGATION -> {
                val (hasWake, remainder) = extractWake(text)
                val now = System.currentTimeMillis()
                val command = when {
                    hasWake && remainder.isNotBlank() -> remainder
                    hasWake -> {
                        // Solo "vocalia": se arma una ventana corta para el comando siguiente
                        uiWakeArmedUntil = now + listeningTimeoutMs
                        voskEngine.beep()
                        log("UI_NAVIGATION: wake word sola, esperando comando")
                        return
                    }
                    continuousEnabled -> text
                    now < uiWakeArmedUntil -> text
                    else -> {
                        log("UI_NAVIGATION: \"$text\" ignorado (falta wake word)")
                        return
                    }
                }
                uiWakeArmedUntil = 0L
                if (command.isBlank()) return
                handleCommand(command, State.UI_NAVIGATION)
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
                    else ->
                        log("TTS_SPEAKING: '$userPauseWord' sin wake word, ignorando (anti-eco)")
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
                        if (!ttsManager.resumePaused(
                                onStart = { enterTtsSpeaking() },
                                onComplete = {
                                    if (state == State.TTS_SPEAKING) resumeListeningWithDelay()
                                })) {
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

    // ----------------------------------------------------------------
    // Ejecución de comandos
    // ----------------------------------------------------------------

    private fun handleCommand(text: String, returnTo: State) {
        log("handleCommand: \"$text\" returnTo=$returnTo")
        if (ttsManager.isSpeaking()) {
            log("handleCommand: cancelando TTS en curso")
            ttsManager.stop()
        }
        val parsed = commandParser.parse(text)
        val forceIdle = parsed is CommandResult.Sleep
            || parsed is CommandResult.ExitContinuous
            || parsed is CommandResult.ExitUiMode
        enterExecuting(if (forceIdle) State.IDLE else returnTo)

        when (parsed) {
            CommandResult.Sleep ->
                speakAndReturn("descanso") { enterIdle() }
            CommandResult.ExitContinuous ->
                speakAndReturn("escucha continua desactivada") { enterIdle() }
            CommandResult.EnterContinuous ->
                speakAndReturn("escucha continua activada") { enterContinuous() }
            CommandResult.PauseTts -> {
                log("PauseTts fuera de TTS_SPEAKING, ignorado")
                afterExecution()
            }
            CommandResult.ResumeTts -> {
                if (ttsManager.hasPausedSpeech()) {
                    log("ResumeTts con buffer pendiente: reanudando")
                    if (!ttsManager.resumePaused(
                            onStart = { enterTtsSpeaking() },
                            onComplete = { if (state == State.TTS_SPEAKING) resumeListeningWithDelay() })) {
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
            is CommandResult.Response ->
                speakAndReturn(parsed.text) { afterExecution() }
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

            // ============================================================
            // Modo usar
            // ============================================================
            CommandResult.EnterUiMode -> {
                val svc = VocaliaAccessibilityService.instance
                if (svc == null) {
                    log("EnterUiMode: accesibilidad no disponible")
                    speakAndReturn("servicio de accesibilidad no disponible, actívalo en ajustes") {
                        afterExecution()
                    }
                } else {
                    log("EnterUiMode: activando modo usar")
                    speakAndReturn("modo usar activado") { enterUiNavigation(refreshList = true) }
                }
            }
            CommandResult.ExitUiMode -> {
                log("ExitUiMode")
                speakAndReturn("modo usar desactivado") { leaveUiMode() }
            }
            CommandResult.UiNext     -> a11yCommand { it.nextNode() }
            CommandResult.UiPrevious -> a11yCommand { it.previousNode() }
            CommandResult.UiRead     -> a11yCommand { it.readCurrentNode() }
            CommandResult.UiDetail   -> a11yCommand { it.describeCurrentNodeDetailed() }
            CommandResult.UiClick    -> a11yCommand(refreshAfter = true) { it.clickCurrentNode() }
            CommandResult.UiBack     -> a11yCommand(refreshAfter = true) { it.performBack() }
            CommandResult.UiPaste    -> a11yCommand { it.pasteClipboard() }
            is CommandResult.UiScroll -> {
                val dir = when (parsed.direction) {
                    CommandResult.UiDirection.UP    -> VocaliaAccessibilityService.ScrollDirection.UP
                    CommandResult.UiDirection.DOWN  -> VocaliaAccessibilityService.ScrollDirection.DOWN
                    CommandResult.UiDirection.LEFT  -> VocaliaAccessibilityService.ScrollDirection.LEFT
                    CommandResult.UiDirection.RIGHT -> VocaliaAccessibilityService.ScrollDirection.RIGHT
                }
                a11yCommand(refreshAfter = true) { it.scroll(dir) }
            }
        }
    }

    // ----------------------------------------------------------------
    // Utilidades
    // ----------------------------------------------------------------

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
        uiTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }; uiTimeoutRunnable = null
    }
}