package io.github.stringmanolo.vocalia.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CommandResult {
    object Sleep : CommandResult()
    data class Response(val text: String) : CommandResult()
}

class CommandParser {

    private val esLocale = Locale("es", "ES")

    fun parse(raw: String): CommandResult {
        val text = raw.lowercase(esLocale).trim()
        return when {
            text.isBlank() -> CommandResult.Response("no te he entendido")
            contains(text, "descansa", "duerme", "para", "silencio") -> CommandResult.Sleep
            contains(text, "hora") -> CommandResult.Response(currentTime())
            contains(text, "fecha") || contains(text, "día") -> CommandResult.Response(currentDate())
            contains(text, "hola") -> CommandResult.Response("hola")
            contains(text, "estás") || contains(text, "tal") -> CommandResult.Response("bien, gracias")
            else -> CommandResult.Response("no entiendo")
        }
    }

    private fun contains(text: String, vararg words: String): Boolean =
        words.any { text.contains(it) }

    private fun currentTime(): String {
        val fmt = SimpleDateFormat("H 'y' mm", esLocale)
        return "son las ${fmt.format(Date())}"
    }

    private fun currentDate(): String {
        val fmt = SimpleDateFormat("EEEE d 'de' MMMM", esLocale)
        return "hoy es ${fmt.format(Date())}"
    }
}
