package io.github.stringmanolo.vocalia

import java.text.Normalizer

/**
 * Utilidades de texto para que el TTS lea bien lo que sale de la pantalla
 * (URLs, símbolos, textos larguísimos) y para comparar textos sin acentos.
 */
object SpeechText {

    private val URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)[^\s,;]+""")

    /** "https://html.duckduckgo.com/html/" -> "html punto duckduckgo punto com barra html" */
    private fun speakUrl(url: String): String {
        var u = url.replace(Regex("(?i)^https?://"), "").trimEnd('/', '.', ')')
        if (u.length > 60) u = u.substring(0, 60)
        return u
            .replace(".", " punto ")
            .replace("/", " barra ")
            .replace("-", " guion ")
            .replace("_", " guion bajo ")
            .replace("?", " interrogación ")
            .replace("=", " igual ")
            .replace("&", " y ")
            .replace("@", " arroba ")
            .replace("#", " almohadilla ")
            .replace("%", " por ciento ")
            .replace(":", " dos puntos ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Texto apto para TTS: URLs deletreadas con "punto" y "barra" (así además no
     * contienen puntos que partan la frase), saltos de línea convertidos en comas
     * y longitud limitada.
     */
    fun speakable(raw: String, maxChars: Int = 140): String {
        var t = raw.replace(Regex("[\\r\\n]+"), ", ").trim()
        t = URL_REGEX.replace(t) { speakUrl(it.value) }
        t = t.replace(Regex("\\s+"), " ")
        if (t.length > maxChars) {
            val cut = t.substring(0, maxChars).let { s ->
                val sp = s.lastIndexOf(' ')
                if (sp > maxChars / 2) s.substring(0, sp) else s
            }
            t = "$cut, y más"
        }
        return t
    }

    /** Minúsculas, sin acentos ni signos: para comparar y buscar. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9ñ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
