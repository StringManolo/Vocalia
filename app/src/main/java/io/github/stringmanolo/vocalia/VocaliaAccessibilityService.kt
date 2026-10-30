package io.github.stringmanolo.vocalia

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.stringmanolo.vocalia.util.InternalDebug

/**
 * Servicio de accesibilidad de Vocalia.
 *
 * Expone una lista ordenada de nodos "interactivos" (BFS) y métodos para
 * navegar por ella, leer su descripción, pulsarla, hacer scroll, ir atrás
 * y pegar del portapapeles.
 *
 * Toda la operativa se asume llamada desde el hilo principal (VocaliaCore
 * lo hace a través de mainHandler.post).
 */
class VocaliaAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 120

        @Volatile
        var instance: VocaliaAccessibilityService? = null
            private set
    }

    enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

    /** Lista actual de nodos interactivos (orden BFS). */
    private var interactiveNodes: List<AccessibilityNodeInfo> = emptyList()

    /** Índice actual sobre `interactiveNodes`. -1 si no hay selección. */
    private var currentIndex: Int = -1

    private fun dbg(msg: String) {
        InternalDebug.mark("[A11y] $msg")
    }

    // ----------------------------------------------------------------
    // Ciclo de vida
    // ----------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dbg("onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub. En fases posteriores refrescaremos en TYPE_WINDOW_STATE_CHANGED.
    }

    override fun onInterrupt() {
        dbg("onInterrupt")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        dbg("onDestroy")
        super.onDestroy()
    }

    // ----------------------------------------------------------------
    // Construcción de la lista de nodos
    // ----------------------------------------------------------------

    /**
     * Recorre el árbol de la ventana activa (BFS) y construye una lista con
     * los nodos visibles que son interactuables o contienen texto.
     *
     * Los nodos que son solo texto y cuelgan de un contenedor pulsable se omiten:
     * su texto ya forma parte del nombre del contenedor (ver [accessibleName]),
     * así no se lee dos veces ("Ajustes, botón" y luego "Ajustes, texto").
     */
    fun refreshNodeList(preserveIndex: Boolean = false) {
        val previousIndex = currentIndex
        val root = rootInActiveWindow
        if (root == null) {
            interactiveNodes = emptyList()
            currentIndex = -1
            dbg("refreshNodeList: rootInActiveWindow=null")
            return
        }
        val result = mutableListOf<AccessibilityNodeInfo>()
        // (nodo, ¿tiene un ancestro pulsable?)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)

        while (queue.isNotEmpty() && result.size < MAX_NODES) {
            val (node, underClickable) = queue.removeFirst()
            if (!node.isVisibleToUser) continue

            val hasText = !node.text.isNullOrBlank() ||
                          !node.contentDescription.isNullOrBlank()
            val isInteractive = node.isClickable || node.isFocusable || node.isEditable

            if (isInteractive || (hasText && !underClickable)) {
                result.add(node)
            }
            val childFlag = underClickable || node.isClickable
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to childFlag) }
            }
        }

        interactiveNodes = result
        currentIndex = when {
            result.isEmpty() -> -1
            preserveIndex && previousIndex >= 0 -> previousIndex.coerceAtMost(result.size - 1)
            else -> 0
        }
        dbg("refreshNodeList: ${result.size} nodos, index=$currentIndex")
    }

    private fun getCurrentNode(): AccessibilityNodeInfo? =
        if (currentIndex in interactiveNodes.indices) interactiveNodes[currentIndex] else null

    // ----------------------------------------------------------------
    // Descripción de nodos
    // ----------------------------------------------------------------

    private fun CharSequence?.clean(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** Texto de los descendientes (máx. 3 niveles, 6 fragmentos) para contenedores sin nombre propio. */
    private fun descendantsText(node: AccessibilityNodeInfo, depth: Int = 0, out: MutableList<String> = mutableListOf()): List<String> {
        if (depth >= 3 || out.size >= 6) return out
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            val t = c.text.clean() ?: c.contentDescription.clean()
            if (t != null && t !in out) out.add(t)
            if (out.size >= 6) break
            descendantsText(c, depth + 1, out)
        }
        return out
    }

    /** "com.app:id/btn_go_home" -> "btn go home" (los ids suelen ser descriptivos). */
    private fun idToWords(id: String?): String? {
        val raw = id?.substringAfter(":id/", "")?.takeIf { it.isNotBlank() } ?: return null
        return raw.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ').replace('-', ' ').lowercase().trim().takeIf { it.isNotEmpty() }
    }

    private fun hintOf(node: AccessibilityNodeInfo): String? =
        if (Build.VERSION.SDK_INT >= 26) node.hintText.clean() else null

    /**
     * Nombre accesible, como haría un lector de pantalla. Orden de prioridad:
     * contentDescription / texto → etiqueta asociada (labeledBy) → tooltip →
     * texto de descendientes → pista → id de vista.
     * Devuelve el nombre y si procede de una fuente "débil" (id), para avisar.
     */
    private fun accessibleName(node: AccessibilityNodeInfo): Pair<String?, Boolean> {
        val text = node.text.clean()
        val desc = node.contentDescription.clean()
        // En campos de texto, `text` es el contenido, no el nombre: el nombre es la pista/etiqueta
        if (node.isEditable) {
            val label = desc
                ?: node.labeledBy?.let { it.text.clean() ?: it.contentDescription.clean() }
                ?: hintOf(node)
                ?: (if (Build.VERSION.SDK_INT >= 28) node.tooltipText.clean() else null)
            return if (label != null) label to false else idToWords(node.viewIdResourceName) to true
        }
        val own = when {
            text != null && desc != null && text != desc -> "$text, $desc"
            else -> text ?: desc
        }
        if (own != null) return own to false
        node.labeledBy?.let { l -> (l.text.clean() ?: l.contentDescription.clean())?.let { return it to false } }
        if (Build.VERSION.SDK_INT >= 28) node.tooltipText.clean()?.let { return it to false }
        val fromChildren = descendantsText(node)
        if (fromChildren.isNotEmpty()) return fromChildren.joinToString(", ") to false
        hintOf(node)?.let { return it to false }
        return idToWords(node.viewIdResourceName) to true
    }

    private fun roleOf(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty()
        return when {
            node.isEditable || cls.endsWith("EditText") -> "campo de texto"
            cls.endsWith("CheckBox") -> "casilla"
            cls.endsWith("RadioButton") -> "opción"
            cls.endsWith("Switch") || cls.endsWith("ToggleButton") -> "interruptor"
            cls.endsWith("ImageButton") -> "botón con icono"
            cls.endsWith("Spinner") -> "lista desplegable"
            cls.endsWith("SeekBar") || cls.endsWith("Slider") -> "control deslizante"
            cls.endsWith("ProgressBar") -> "barra de progreso"
            cls.endsWith("TabWidget") || cls.endsWith("Tab") -> "pestaña"
            cls.endsWith("ImageView") -> if (node.isClickable) "imagen pulsable" else "imagen"
            cls.endsWith("WebView") -> "contenido web"
            cls.endsWith("ListView") || cls.endsWith("RecyclerView") -> "lista"
            node.isCheckable -> "casilla"
            cls.endsWith("Button") || node.isClickable -> "botón"
            node.isFocusable -> "elemento"
            else -> "texto"
        }
    }

    private fun stateOf(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 30) node.stateDescription.clean()?.let { out.add(it) }
        if (node.isCheckable && out.isEmpty()) out.add(if (node.isChecked) "marcado" else "sin marcar")
        if (node.isSelected) out.add("seleccionado")
        if (!node.isEnabled) out.add("desactivado")
        return out
    }

    /**
     * Descripción breve: "<nombre>. <tipo>, <estado>". Si no hay nombre real,
     * lo dice explícitamente en vez de limitarse a "botón".
     */
    private fun describeNode(node: AccessibilityNodeInfo): String {
        val (name, weak) = accessibleName(node)
        val role = roleOf(node)

        if (node.isEditable && node.isPassword) {
            val len = node.text?.length ?: 0
            return "${name ?: "contraseña"}. campo de contraseña, $len caracteres"
        }

        val parts = mutableListOf<String>()
        parts.add(role)
        parts.addAll(stateOf(node))
        if (node.isEditable) {
            val content = node.text.clean()
            // Si el texto coincide con la pista, el campo está realmente vacío
            if (content != null && content != hintOf(node)) parts.add("contiene: $content")
            else parts.add("vacío")
        }

        return when {
            name == null -> "sin nombre. ${parts.joinToString(", ")}"
            weak -> "$name. ${parts.joinToString(", ")}, sin etiqueta, nombre deducido del identificador"
            else -> "$name. ${parts.joinToString(", ")}"
        }
    }

    /** Descripción ampliada del nodo actual: nombre, tipo, acciones, estado y posición en pantalla. */
    fun describeCurrentNodeDetailed(): String {
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        node.refresh()
        val sb = StringBuilder(describeNode(node))

        val actions = mutableListOf<String>()
        if (node.isClickable) actions.add("se puede pulsar")
        if (node.isLongClickable) actions.add("admite pulsación larga")
        if (node.isScrollable) actions.add("se puede desplazar")
        if (node.isEditable) actions.add("se puede escribir")
        if (actions.isNotEmpty()) sb.append(". ").append(actions.joinToString(", "))

        node.error.clean()?.let { sb.append(". error: $it") }
        if (Build.VERSION.SDK_INT >= 28) node.paneTitle.clean()?.let { sb.append(". panel: $it") }

        val r = Rect(); node.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        val horiz = when { r.centerX() < dm.widthPixels / 3 -> "izquierda"; r.centerX() > dm.widthPixels * 2 / 3 -> "derecha"; else -> "centro" }
        val vert  = when { r.centerY() < dm.heightPixels / 3 -> "arriba"; r.centerY() > dm.heightPixels * 2 / 3 -> "abajo"; else -> "en medio" }
        sb.append(". posición: $vert $horiz")
        sb.append(". elemento ${currentIndex + 1} de ${interactiveNodes.size}")
        idToWords(node.viewIdResourceName)?.let { sb.append(". identificador: $it") }
        dbg("detail [$currentIndex] -> $sb")
        return sb.toString()
    }

    // ----------------------------------------------------------------
    // Navegación
    // ----------------------------------------------------------------

    /** Descripción breve del elemento seleccionado (no mueve la selección). */
    fun readCurrentNode(): String {
        if (interactiveNodes.isEmpty()) {
            return "no hay elementos. Prueba a decir siguiente o anterior."
        }
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        val desc = describeNode(node)
        dbg("read [$currentIndex/${interactiveNodes.size - 1}] -> \"$desc\"")
        return desc
    }

    /**
     * Si el nodo actual ya no es válido (la pantalla cambió), reconstruye la
     * lista conservando la posición, en vez de volver al principio.
     */
    private fun ensureListIsFresh() {
        if (interactiveNodes.isEmpty()) {
            refreshNodeList()
            return
        }
        val node = getCurrentNode()
        if (node != null && !node.refresh()) {
            dbg("nodo actual obsoleto, refrescando lista conservando posición")
            refreshNodeList(preserveIndex = true)
        }
    }

    /** Mueve la selección al elemento siguiente (circular) y lo anuncia. */
    fun nextNode(): String {
        ensureListIsFresh()
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        currentIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % interactiveNodes.size
        return readCurrentNode()
    }

    /** Mueve la selección al elemento anterior (circular) y lo anuncia. */
    fun previousNode(): String {
        ensureListIsFresh()
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        currentIndex = if (currentIndex <= 0) interactiveNodes.size - 1 else currentIndex - 1
        return readCurrentNode()
    }

    // ----------------------------------------------------------------
    // Acciones
    // ----------------------------------------------------------------

    fun clickCurrentNode(): String {
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        if (!node.refresh()) {
            refreshNodeList()
            return "el elemento ya no está disponible, lista actualizada"
        }
        if (!node.isClickable) return "este elemento no se puede pulsar"
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        dbg("click [$currentIndex] -> ok=$ok")
        return if (ok) "pulsado" else "no se pudo pulsar"
    }

    fun performBack(): String {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        dbg("back -> ok=$ok")
        return if (ok) "atrás" else "no se pudo volver atrás"
    }

    fun scroll(direction: ScrollDirection): String {
        val node = getCurrentNode() ?: rootInActiveWindow
        if (node == null) return "no hay ventana activa"
        val action = when (direction) {
            ScrollDirection.UP, ScrollDirection.LEFT ->
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN, ScrollDirection.RIGHT ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val ok = node.performAction(action)
        dbg("scroll $direction -> ok=$ok (nodo=${node.className})")
        return if (ok) "desplazado" else "no se pudo desplazar"
    }

    fun pasteClipboard(): String {
        val root = rootInActiveWindow ?: return "no hay ventana activa"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "no hay campo de texto enfocado"
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        dbg("paste -> ok=$ok (foco=${focused.className})")
        return if (ok) "pegado" else "no se pudo pegar"
    }
}
