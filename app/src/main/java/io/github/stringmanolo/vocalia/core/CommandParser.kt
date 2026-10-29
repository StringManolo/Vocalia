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

class CommandParser(private val appLauncher: AppLauncher? = null) {

    private val esLocale = Locale("es", "ES")

    private val openPrefixes = listOf(
        "abre la app ", "abre el ", "abre la ", "abre ",
        "abrir la app ", "abrir el ", "abrir la ", "abrir ",
        "lanza la app ", "lanza el ", "lanza la ", "lanza ",
        "abreme la app ", "abreme la ", "abreme el ", "abreme ",
        "arranca la app ", "arranca el ", "arranca la ", "arranca ",
        "arrancame la app ", "arrancame la ", "arrancame el ", "arrancame "
    )

    fun parse(raw: String): CommandResult {
        val text = raw.lowercase(esLocale).trim()
        return when {
            text.isBlank() -> CommandResult.Response("no te he entendido")

            // 1. OpenApp PRIMERO (para evitar que "abre cámara callate" → Sleep)
            extractAppName(text) != null -> CommandResult.OpenApp(extractAppName(text)!!)
            appLauncher?.hasApp(text) == true -> CommandResult.OpenApp(text)

            // 2. Sleep
            matches(text, "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "para", "parar", "silencio", "callate") ->
                CommandResult.Sleep

            // 3. Cambio de voz
            matches(text, "inteligente", "piper", "neuronal", "natural") ->
                CommandResult.SetVoiceMode(intelligent = true)
            matches(text, "por defecto", "normal", "espeak", "robotica", "robótica", "robotizada") ->
                CommandResult.SetVoiceMode(intelligent = false)

            // 4. Info
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
                val raw = text.removePrefix(p).trim()
                // Limpiar palabras de cola que Vosk a veces añade
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
            if (kw.contains(" ")) {
                text.contains(kw)  // multi-palabra, substring
            } else {
                words.any { w -> w == kw || (w.startsWith(kw) && w.length - kw.length <= 3) }
            }
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
