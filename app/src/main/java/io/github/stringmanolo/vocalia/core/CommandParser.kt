package io.github.stringmanolo.vocalia.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandResult {
    object Sleep : CommandResult()
    data class Response(val text: String) : CommandResult()
    data class SetVoiceMode(val intelligent: Boolean) : CommandResult()
    data class OpenApp(val appName: String) : CommandResult()
}

class CommandParser {

    private val esLocale = Locale("es", "ES")

    private val openPrefixes = listOf(
        "abre la app ", "abre el ", "abre la ", "abre ",
        "abrir la app ", "abrir el ", "abrir la ", "abrir ",
        "lanza la app ", "lanza el ", "lanza la ", "lanza ",
        "abreme ", "abreme la ", "abreme el ", "arranca", "arráncame"
    )

    fun parse(raw: String): CommandResult {
        val text = raw.lowercase(esLocale).trim()
        return when {
            text.isBlank() -> CommandResult.Response("no te he entendido")

            matches(text, "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "para", "parar", "silencio", "callate") ->
                CommandResult.Sleep

            matches(text, "inteligente", "piper", "neuronal", "natural") ->
                CommandResult.SetVoiceMode(intelligent = true)

            matches(text, "por defecto", "normal", "espeak", "robotica", "robótica", "robotizada") ->
                CommandResult.SetVoiceMode(intelligent = false)

            extractAppName(text) != null -> CommandResult.OpenApp(extractAppName(text)!!)

            matches(text, "hora") -> CommandResult.Response(currentTime())
            matches(text, "fecha", "dia", "día") -> CommandResult.Response(currentDate())
            matches(text, "hola") -> CommandResult.Response("hola")
            matches(text, "estas", "estás", "tal") -> CommandResult.Response("bien, gracias")

            else -> CommandResult.Response("no entiendo")
        }
    }

    private fun extractAppName(text: String): String? {
        for (p in openPrefixes) {
            if (text.startsWith(p)) {
                val app = text.removePrefix(p).trim()
                return if (app.isNotBlank()) app else null
            }
        }
        return null
    }

    private fun matches(text: String, vararg keywords: String): Boolean =
        keywords.any { kw -> text.contains(kw) || kw.contains(text) || commonPrefix(text, kw) >= 4 }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
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
