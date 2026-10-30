package io.github.stringmanolo.vocalia

import android.accessibilityservice.AccessibilityService
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
     */
    fun refreshNodeList() {
        val root = rootInActiveWindow
        if (root == null) {
            interactiveNodes = emptyList()
            currentIndex = -1
            dbg("refreshNodeList: rootInActiveWindow=null")
            return
        }
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && result.size < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser) continue

            val hasText = !node.text.isNullOrBlank() ||
                          !node.contentDescription.isNullOrBlank()
            val isInteractive = node.isClickable || node.isFocusable || node.isEditable

            if (isInteractive || hasText) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        interactiveNodes = result
        currentIndex = if (result.isNotEmpty()) 0 else -1
        dbg("refreshNodeList: ${result.size} nodos, index=$currentIndex")
    }

    private fun getCurrentNode(): AccessibilityNodeInfo? =
        if (currentIndex in interactiveNodes.indices) interactiveNodes[currentIndex] else null

    /**
     * Genera una descripción natural de un nodo: texto + contentDescription
     * + tipo (botón, campo de texto, casilla…) + pista opcional.
     */
    private fun describeNode(node: AccessibilityNodeInfo): String {
        val text  = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc  = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val hint  = node.hintText?.toString()?.takeIf { it.isNotBlank() }

        val type = when {
            node.isEditable   -> "campo de texto"
            node.isCheckable  -> "casilla"
            node.isClickable  -> "botón"
            node.isFocusable  -> "elemento"
            else              -> "texto"
        }

        val content = when {
            text != null && desc != null && text != desc -> "$text, $desc"
            text != null -> text
            desc != null -> desc
            else -> ""
        }

        val hintSuffix = if (hint != null) ", pista: $hint" else ""

        return if (content.isBlank()) type
               else "$content. $type$hintSuffix"
    }

    // ----------------------------------------------------------------
    // Navegación
    // ----------------------------------------------------------------

    fun readCurrentNode(): String {
        if (interactiveNodes.isEmpty()) {
            return "no hay elementos. Prueba a decir siguiente o anterior."
        }
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        val desc = describeNode(node)
        dbg("read [$currentIndex/${interactiveNodes.size - 1}] -> \"$desc\"")
        return desc
    }

    fun nextNode(): String {
        if (interactiveNodes.isEmpty()) refreshNodeList()
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        currentIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % interactiveNodes.size
        return readCurrentNode()
    }

    fun previousNode(): String {
        if (interactiveNodes.isEmpty()) refreshNodeList()
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
        refreshNodeList()
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
        refreshNodeList()
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
