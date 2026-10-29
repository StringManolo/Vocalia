package io.github.stringmanolo.vocalia.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandResult {
    object Sleep : CommandResult()
    data class Response(val text: String) : CommandResult()
    /** true = voz inteligente (Sherpa), false = voz por defecto (espeak). */
    data class SetVoiceMode(val intelligent: Boolean) : CommandResult()
}

class CommandParser {

    private val esLocale = Locale("es", "ES")

    fun parse(raw: String): CommandResult {
        val text = raw.lowercase(esLocale).trim()
        return when {
            text.isBlank() -> CommandResult.Response("no te he entendido")

            // Cambio de motor (antes de "hora" para evitar ambigüedades)
            matches(text, "inteligente", "piper", "neuronal", "natural") ->
                CommandResult.SetVoiceMode(intelligent = true)
            matches(text, "por defecto", "normal", "espeak", "robotica", "robótica", "robotizada") ->
                CommandResult.SetVoiceMode(intelligent = false)

            matches(text, "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "para", "parar", "silencio", "callate") ->
                CommandResult.Sleep

            matches(text, "hora") -> CommandResult.Response(currentTime())
            matches(text, "fecha", "dia", "día") -> CommandResult.Response(currentDate())
            matches(text, "hola") -> CommandResult.Response("hola")
            matches(text, "estas", "estás", "tal") -> CommandResult.Response("bien, gracias")

            else -> CommandResult.Response("no entiendo")
        }
    }

    private fun matches(text: String, vararg keywords: String): Boolean =
        keywords.any { kw ->
            text.contains(kw) || kw.contains(text) || commonPrefix(text, kw) >= 4
        }

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
