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
            matches(text, "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "para", "parar", "silencio", "callate") ->
                CommandResult.Sleep
            matches(text, "hora") -> CommandResult.Response(currentTime())
            matches(text, "fecha", "dia", "día") -> CommandResult.Response(currentDate())
            matches(text, "hola") -> CommandResult.Response("hola")
            matches(text, "estas", "estás", "tal") -> CommandResult.Response("bien, gracias")
            else -> CommandResult.Response("no entiendo")
        }
    }

    /**
     * Match flexible:
     *  - contiene la palabra clave ("descanso" contiene "descans"? no, pero "descansar" sí)
     *  - la palabra clave contiene al texto ("cansa" está en "descansa")
     *  - comparten un prefijo de al menos 4 caracteres ("descans" de "descanso"/"descansa")
     */
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
