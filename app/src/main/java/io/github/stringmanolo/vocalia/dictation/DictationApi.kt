package io.github.stringmanolo.vocalia.dictation

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.stringmanolo.vocalia.VocaliaAccessibilityService
import org.json.JSONObject
import java.io.File

/*
 * ====================================================================
 *  INTERFACES PARA LA SIGUIENTE FASE: MODO DICTADO ("vim por voz")
 * ====================================================================
 *  Esto NO es el modo dictado. Es el contrato sobre el que se construirá, para que
 *  la fase siguiente no tenga que tocar el resto de Vocalia:
 *
 *   - InputKind:        QUÉ se va a escribir (contraseña, correo, código, conversación…).
 *   - GrammarProfile:   vocabulario cerrado para Vosk según ese tipo. Con un modelo pequeño
 *                       y solo en español, hay que decirle antes qué palabras esperar.
 *   - TextTarget:       DÓNDE se escribe (campo de otra app, o un búfer propio).
 *   - EditorMode:       en qué modo está la edición (normal, insertar, visual, órdenes).
 *   - DictationSession: una edición en curso: recibe frases, devuelve eventos.
 *   - DictationEngine:  la fábrica de sesiones. Por ahora NoDictationEngine.
 *
 *  Flujo previsto: el usuario dice "vocalia, escribir contraseña" -> se crea un
 *  DictationRequest(kind = PASSWORD, ...) -> el motor pide los perfiles de gramática
 *  adecuados -> se carga la gramática en Vosk -> la sesión interpreta lo dicho.
 */

/** Qué se va a escribir. Decide la gramática, si se repite lo escrito y cómo se guarda. */
enum class InputKind {
    FREE_TEXT,      // texto libre
    CONVERSATION,   // mensajes a otras personas
    CODE,           // programación (lenguaje en DictationRequest.language)
    USERNAME,
    PERSON_NAME,
    DATE,
    AGE,
    NUMBER,
    EMAIL,
    PASSWORD,       // nunca se vuelve a leer en voz alta ni se escribe en el log
    CAPTCHA,
    URL,
    SEARCH_QUERY
}

/** Modos de edición, como en vim. */
enum class EditorMode {
    NORMAL,   // moverse y dar órdenes: "línea siguiente", "borra palabra"
    INSERT,   // lo que se dice se escribe
    VISUAL,   // seleccionar
    COMMAND   // guardar, descartar, salir
}

/** Perfil de gramática: qué palabras y símbolos debe esperar Vosk. */
data class GrammarProfile(
    val id: String,
    val kind: InputKind,
    /** Lenguaje de programación ("javascript", "python", "c", "cpp"…) o idioma, si aplica. */
    val language: String?,
    /** Temática para jerga especializada ("it", "ciberseguridad", "medicina"…). */
    val topic: String?,
    val description: String,
    /** Palabras sueltas que se añaden a la gramática (palabras clave, siglas, jerga). */
    val words: List<String>,
    /** Frases completas que se añaden a la gramática. */
    val phrases: List<String>,
    /** Palabra dicha -> texto que se escribe ("arroba" -> "@", "guion bajo" -> "_"). */
    val symbols: Map<String, String>,
    /** true = se dicta letra a letra (contraseñas, usuarios, códigos). */
    val spelled: Boolean,
    /** Modificadores dichos antes de un carácter ("mayúscula", "minúscula"). */
    val modifiers: List<String>
)

/** De dónde salen los perfiles. La implementación por defecto lee ficheros JSON editables. */
interface GrammarProfileProvider {
    fun get(id: String): GrammarProfile?
    /** Perfiles aplicables a este tipo de entrada, idioma/lenguaje y temática (los más específicos primero). */
    fun forKind(kind: InputKind, language: String? = null, topic: String? = null): List<GrammarProfile>
    fun reload()
}

/** Sitio donde se escribe. Lo implementa un campo de otra app o un búfer interno. */
interface TextTarget {
    fun isAvailable(): Boolean
    fun isPassword(): Boolean
    fun currentText(): String
    /** Posición del cursor (inicio de la selección). */
    fun cursor(): Int
    fun setText(text: String): Boolean
    fun insertAtCursor(text: String): Boolean
    fun setSelection(start: Int, end: Int = start): Boolean
}

/** Qué se va a hacer: lo pide quien necesita escribir algo (una web, un formulario, un chat…). */
data class DictationRequest(
    val kind: InputKind,
    val target: TextTarget,
    val language: String? = null,
    val topic: String? = null,
    /** Texto inicial (para editar lo que ya había). */
    val initialText: String = "",
    /** Lo escrito no se lee de vuelta (contraseñas). Por defecto, según el tipo. */
    val silentFeedback: Boolean = kind == InputKind.PASSWORD
)

/** Lo que devuelve una sesión tras cada frase. */
sealed class DictationEvent {
    /** Se escribió [text]. */
    data class Inserted(val text: String) : DictationEvent()
    /** Cambio de modo. */
    data class ModeChanged(val mode: EditorMode) : DictationEvent()
    /** Algo que decir al usuario (confirmaciones, lecturas). */
    data class Say(val message: String) : DictationEvent()
    /** La frase no se entendió o no vale en este modo. */
    data class NotUnderstood(val heard: String) : DictationEvent()
    /** La sesión terminó; [saved] indica si se guardaron los cambios. */
    data class Finished(val saved: Boolean) : DictationEvent()
}

/** Una edición en curso. */
interface DictationSession {
    val request: DictationRequest
    val mode: EditorMode
    /** Gramática (JSON de Vosk) que debe estar cargada en este momento. */
    fun currentGrammar(): String
    /** Procesa una frase reconocida. */
    fun handle(utterance: String): DictationEvent
    /** Lee lo que hay escrito (o "campo de contraseña, N caracteres"). */
    fun readBack(): String
    fun save(): Boolean
    fun discard()
}

/** Fábrica de sesiones. */
interface DictationEngine {
    val available: Boolean
    fun begin(request: DictationRequest): DictationSession?
}

/** Marcador de posición hasta que exista el modo dictado. */
object NoDictationEngine : DictationEngine {
    override val available: Boolean = false
    override fun begin(request: DictationRequest): DictationSession? = null
}

// ====================================================================
//  Implementaciones listas para usar
// ====================================================================

/** Escribe en el campo de texto con el foco de otra app, mediante accesibilidad. */
class AccessibilityTextTarget(private val service: VocaliaAccessibilityService) : TextTarget {

    private fun node(): AccessibilityNodeInfo? = service.focusedInput()

    override fun isAvailable(): Boolean = node() != null

    override fun isPassword(): Boolean = node()?.isPassword == true

    override fun currentText(): String {
        val n = node() ?: return ""
        // Si el campo muestra su pista (placeholder), en realidad está vacío
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString().orEmpty()
    }

    override fun cursor(): Int = node()?.textSelectionStart?.takeIf { it >= 0 } ?: currentText().length

    override fun setText(text: String): Boolean {
        val n = node() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun insertAtCursor(text: String): Boolean {
        val cur = currentText()
        val pos = cursor().coerceIn(0, cur.length)
        val ok = setText(cur.substring(0, pos) + text + cur.substring(pos))
        if (ok) setSelection(pos + text.length)
        return ok
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val n = node() ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
}

/**
 * Lee los perfiles de gramática de un fichero JSON (`vocalia-grammar-profiles.json`),
 * que se extrae a la misma carpeta pública que la configuración de apps y se puede editar.
 */
class JsonGrammarProfileProvider(private val file: File) : GrammarProfileProvider {

    private var profiles: List<GrammarProfile> = emptyList()

    init { reload() }

    override fun get(id: String): GrammarProfile? = profiles.firstOrNull { it.id == id }

    override fun forKind(kind: InputKind, language: String?, topic: String?): List<GrammarProfile> =
        profiles.filter { it.kind == kind &&
                (it.language == null || language == null || it.language.equals(language, true)) &&
                (it.topic == null || topic == null || it.topic.equals(topic, true)) }
            .sortedByDescending { (if (it.language != null && language != null) 2 else 0) +
                                  (if (it.topic != null && topic != null) 1 else 0) }

    override fun reload() {
        profiles = try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONObject("profiles") ?: return
            val out = mutableListOf<GrammarProfile>()
            val it = arr.keys()
            while (it.hasNext()) {
                val id = it.next()
                val o = arr.optJSONObject(id) ?: continue
                val kind = runCatching { InputKind.valueOf(o.optString("kind").uppercase()) }.getOrNull() ?: continue
                fun list(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }.orEmpty()
                val sym = mutableMapOf<String, String>()
                o.optJSONObject("symbols")?.let { s ->
                    val si = s.keys(); while (si.hasNext()) { val k = si.next(); sym[k] = s.optString(k) }
                }
                out.add(GrammarProfile(
                    id = id, kind = kind,
                    language = o.optString("language").takeIf { it.isNotBlank() },
                    topic = o.optString("topic").takeIf { it.isNotBlank() },
                    description = o.optString("description"),
                    words = list("words"), phrases = list("phrases"), symbols = sym,
                    spelled = o.optBoolean("spelled", false), modifiers = list("modifiers")
                ))
            }
            out
        } catch (_: Throwable) { emptyList() }
    }
}
