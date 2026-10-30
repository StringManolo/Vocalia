package io.github.stringmanolo.vocalia.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandResult {
    object Sleep : CommandResult()
    object ListApps : CommandResult()
    object ListAppsVerbose : CommandResult()
    object EnterContinuous : CommandResult()
    object ExitContinuous : CommandResult()
    object PauseTts : CommandResult()
    object ResumeTts : CommandResult()
    object CloseForegroundApp : CommandResult()
    object Help : CommandResult()
    data class Response(val text: String) : CommandResult()
    data class SetVoiceMode(val intelligent: Boolean) : CommandResult()
    data class OpenApp(val appName: String) : CommandResult()
    data class CloseApp(val appName: String) : CommandResult()
}

class CommandParser(private val appLauncher: AppLauncher? = null) {

    private val esLocale = Locale("es", "ES")

    private val openPrefixes = listOf(
        "abre la app ", "abre el ", "abre la ", "abre ",
        "abrir la app ", "abrir el ", "abrir la ", "abrir "
    )

    private val closePrefixes = listOf(
        "cierra la app ", "cierra el ", "cierra la ", "cierra ",
        "cerrar la app ", "cerrar el ", "cerrar la ", "cerrar ",
        "mata la app ", "mata el ", "mata la ", "mata ",
        "matar la app ", "matar el ", "matar la ", "matar "
    )

    fun parse(raw: String): CommandResult {
        val text = raw.lowercase(esLocale).trim()
        return when {
            text.isBlank() -> CommandResult.Response("no te he entendido")

            matches(text, "ayuda", "qué puedes hacer", "que puedes hacer",
                "qué sabes hacer", "que sabes hacer", "dime todo lo que puedes hacer",
                "dime lo que puedes hacer", "guía", "guia", "comandos",
                "lista de comandos", "qué comandos tienes", "que comandos tienes") ->
                CommandResult.Help

            matches(text, "lístame las apps", "listame las apps", "lista de apps disponibles",
                "lista completa de apps", "qué apps disponibles", "que apps disponibles",
                "todas las apps", "todas las aplicaciones") ->
                CommandResult.ListAppsVerbose

            matches(text, "lista de apps", "lista apps", "qué apps tengo", "que apps tengo",
                "lista de aplicaciones") ->
                CommandResult.ListApps

            matches(text, "pausa el tts", "pausa el texto", "pausa la lectura", "pausa lectura", "pausa voz",
                "para el tts", "para el texto", "para la lectura",
                "pausa", "para", "parar", "pausar", "silencio", "callate", "stop") ->
                CommandResult.PauseTts
            matches(text, "continua el tts", "continua el texto", "continua la lectura", "continua lectura",
                "continua voz", "reanuda el tts", "reanuda la lectura", "sigue leyendo",
                "continua", "continúa", "reanuda", "sigue") ->
                CommandResult.ResumeTts

            matches(text, "sal de escucha continua", "desactiva escucha continua", "desactiva modo continuo") ->
                CommandResult.ExitContinuous
            matches(text, "escucha continua", "modo continuo", "escucha constante") ->
                CommandResult.EnterContinuous

            extractCloseAppName(text) != null -> CommandResult.CloseApp(extractCloseAppName(text)!!)

            matches(text, "cierra esta app", "cierra la app actual", "cierra esto",
                "cierra la app que esté abierta", "cierra lo que hay delante") ->
                CommandResult.CloseForegroundApp

            extractAppName(text) != null -> CommandResult.OpenApp(extractAppName(text)!!)
            appLauncher?.hasApp(text) == true -> CommandResult.OpenApp(text)

            matches(text, "descansa", "descanso", "descansar", "cansa", "duerme", "dormir") ->
                CommandResult.Sleep

            matches(text, "inteligente", "piper", "neuronal", "natural") ->
                CommandResult.SetVoiceMode(intelligent = true)
            matches(text, "por defecto", "espeak", "robotica", "robotizada") ->
                CommandResult.SetVoiceMode(intelligent = false)

            matches(text, "hora") -> CommandResult.Response(currentTime())
            matches(text, "fecha", "dia") -> CommandResult.Response(currentDate())
            matches(text, "hola") -> CommandResult.Response("hola")
            matches(text, "estas", "tal") -> CommandResult.Response("bien, gracias")

            else -> CommandResult.Response("no entiendo")
        }
    }

    private fun extractAppName(text: String): String? {
        for (p in openPrefixes) {
            if (text.startsWith(p)) {
                val raw = text.removePrefix(p).trim()
                val stripped = raw
                    .removeSuffix(" callate").removeSuffix(" calla")
                    .removeSuffix(" por favor").removeSuffix(" ahora").removeSuffix(" ya")
                    .trim()
                return if (stripped.isNotBlank()) stripped else null
            }
        }
        return null
    }

    private fun extractCloseAppName(text: String): String? {
        for (p in closePrefixes) {
            if (text.startsWith(p)) {
                val raw = text.removePrefix(p).trim()
                val stripped = raw
                    .removeSuffix(" callate").removeSuffix(" calla")
                    .removeSuffix(" por favor").removeSuffix(" ahora").removeSuffix(" ya")
                    .trim()
                return if (stripped.isNotBlank()) stripped else null
            }
        }
        return null
    }

    private fun matches(text: String, vararg keywords: String): Boolean {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return keywords.any { kw ->
            if (kw.contains(" ")) text.contains(kw)
            else words.any { w -> w == kw || (w.startsWith(kw) && w.length - kw.length <= 3) }
        }
    }

    private fun currentTime(): String {
        val fmt = SimpleDateFormat("H 'y' mm", esLocale)
        return "son las ${fmt.format(Date())}"
    }

    private fun currentDate(): String {
        val fmt = SimpleDateFormat("EEEE d 'de' MMMM", esLocale)
        return "hoy es ${fmt.format(Date())}"
    }
}
