package io.github.stringmanolo.vocalia

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import io.github.stringmanolo.vocalia.config.AppConfigManager
import io.github.stringmanolo.vocalia.config.AppUiConfig
import io.github.stringmanolo.vocalia.config.Coord
import io.github.stringmanolo.vocalia.config.DetectCond
import io.github.stringmanolo.vocalia.config.ScreenDetect
import io.github.stringmanolo.vocalia.config.UiElement
import io.github.stringmanolo.vocalia.config.UiScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.stringmanolo.vocalia.util.InternalDebug

/**
 * Servicio de accesibilidad de Vocalia.
 *
 * Expone una lista ordenada de nodos "interactivos" (orden de lectura) y
 * métodos para navegar por ella, leer su descripción, pulsarla, hacer scroll,
 * ir atrás y pegar del portapapeles.
 *
 * Además funciona como un "ratón": puede pulsar, mantener, doble pulsación y
 * deslizar en cualquier coordenada (dispatchGesture) y trae "cursores" definidos
 * por app en ficheros de configuración (ver AppConfigManager).
 *
 * Toda la operativa se asume llamada desde el hilo principal (VocaliaCore
 * lo hace a través de mainHandler.post).
 */
class VocaliaAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_NODES = 120
        private const val MAX_VOCAB = 150
        private const val MAX_CONFIG_PHRASES = 150

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

    // --- Estado de la configuración por app ---
    @Volatile private var foregroundPackage: String? = null
    private var activeConfig: AppUiConfig? = null
    /** Pantalla actual de la app (detectada por reglas, o forzada por voz con "pantalla X"). */
    private var currentScreen: UiScreen? = null
    private var forcedScreenKey: String? = null
    /** Clase de la última ventana (actividad) que cambió de estado. */
    @Volatile private var lastWindowClass: String? = null
    private var configPhrases: List<String> = emptyList()
    /** Cursor configurado seleccionado (virtual). Mientras exista, lee/pulsa/detalle/arrastra actúan sobre él. */
    private var virtualElement: UiElement? = null
    private var ignoredPackages: Set<String> = setOf("com.android.systemui")

    private val gestureThread = HandlerThread("vocalia-gesture").apply { start() }
    private val gestureHandler = Handler(gestureThread.looper)

    // ----------------------------------------------------------------
    // Ciclo de vida
    // ----------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dbg("onServiceConnected")
        try {
            val imes = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }
            ignoredPackages = (imes + "com.android.systemui").toSet()
            AppConfigManager.init(applicationContext)
            dbg("configuración en ${AppConfigManager.configDirPath()}")
        } catch (t: Throwable) {
            dbg("init config THREW ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                noteWindowPackage(it.packageName)
                val p = it.packageName?.toString()
                if (p != null && p != packageName && p !in ignoredPackages) {
                    lastWindowClass = it.className?.toString()
                }
            }
        }
    }

    /** Recuerda la última app "real" en primer plano (ignora Vocalia, teclados y la barra del sistema). */
    private fun noteWindowPackage(pkg: CharSequence?) {
        val p = pkg?.toString() ?: return
        if (p == packageName || p in ignoredPackages) return
        if (p != foregroundPackage) {
            foregroundPackage = p
            dbg("app en primer plano: $p")
        }
    }

    /**
     * Carga (o recarga si cambió algún fichero) la configuración de la app en primer plano,
     * detecta la pantalla actual y recalcula las frases de gramática en caliente.
     */
    private fun updateActiveConfig() {
        try {
            rootInActiveWindow?.let { noteWindowPackage(it.packageName) }
            AppConfigManager.reloadIfChanged()
            val cfg = foregroundPackage?.let { AppConfigManager.configFor(it) }
            if (cfg?.appKey != activeConfig?.appKey) forcedScreenKey = null
            activeConfig = cfg

            val prevScreen = currentScreen?.key
            currentScreen = cfg?.let { detectScreen(it) }
            if (cfg?.appKey != lastLoggedApp || currentScreen?.key != prevScreen) {
                lastLoggedApp = cfg?.appKey
                dbg("config activa: ${cfg?.appKey ?: "ninguna"}, pantalla=${currentScreen?.key ?: "ninguna"}" +
                    (if (forcedScreenKey != null) " (forzada)" else "") +
                    " (${cfg?.elementsFor(currentScreen)?.size ?: 0} cursores, versión instalada=" +
                    "${cfg?.installedVersion}, config=${cfg?.configVersion})")
            }

            val els = cfg?.elementsFor(currentScreen).orEmpty()
            val screenNames = cfg?.screens?.flatMap { it.grammar }.orEmpty()
            configPhrases = (els.flatMap { it.grammar } + screenNames).distinct().take(MAX_CONFIG_PHRASES)
        } catch (t: Throwable) {
            dbg("updateActiveConfig THREW ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var lastLoggedApp: String? = null

    // ---- Detección de pantalla ----

    private class TreeSnap(
        val ids: List<String>, val texts: List<String>, val descs: List<String>,
        val classes: Set<String>, val selectedTexts: List<String>
    )

    private fun snapshotTree(root: AccessibilityNodeInfo): TreeSnap {
        val ids = ArrayList<String>(); val texts = ArrayList<String>(); val descs = ArrayList<String>()
        val classes = HashSet<String>(); val sel = ArrayList<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < 1500) {
            val n = stack.removeLast()
            count++
            if (!n.isVisibleToUser) continue
            n.viewIdResourceName?.let { ids.add(it) }
            n.className?.toString()?.let { classes.add(it) }
            val t = n.text.clean()?.let { SpeechText.normalize(it) }
            val d = n.contentDescription.clean()?.let { SpeechText.normalize(it) }
            if (t != null) texts.add(t)
            if (d != null) descs.add(d)
            if (n.isSelected) { t?.let { sel.add(it) }; d?.let { sel.add(it) } }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        return TreeSnap(ids, texts, descs, classes, sel)
    }

    private fun matchCond(c: DetectCond, snap: TreeSnap): Boolean {
        if (c.id == null && c.text == null && c.desc == null && c.cls == null && c.activity == null) return false
        c.activity?.let { a ->
            val w = lastWindowClass
            if (w == null || !(w == a || w.endsWith(a))) return false
        }
        c.id?.let { id ->
            if (snap.ids.none { it == id || it.endsWith(":id/$id") || it.endsWith("/$id") }) return false
        }
        c.text?.let { t ->
            val n = SpeechText.normalize(t)
            val pool = if (c.selected) snap.selectedTexts else snap.texts
            if (pool.none { it.contains(n) }) return false
        }
        c.desc?.let { t ->
            val n = SpeechText.normalize(t)
            val pool = if (c.selected) snap.selectedTexts else snap.descs
            if (pool.none { it.contains(n) }) return false
        }
        c.cls?.let { cl -> if (snap.classes.none { it.endsWith(cl) }) return false }
        return true
    }

    /** Puntuación de una pantalla, o null si no cumple sus reglas. */
    private fun scoreScreen(d: ScreenDetect, snap: TreeSnap): Int? {
        if (d.all.any { !matchCond(it, snap) }) return null
        val anyHits = d.any.count { matchCond(it, snap) }
        if (d.any.isNotEmpty() && anyHits == 0) return null
        if (d.none.any { matchCond(it, snap) }) return null
        return d.priority * 100 + d.all.size + anyHits
    }

    private fun detectScreen(cfg: AppUiConfig): UiScreen? {
        val default = cfg.defaultScreen()
        forcedScreenKey?.let { k -> cfg.screens.firstOrNull { it.key == k }?.let { return it } }
        val detectable = cfg.screens.filter { it.detect != null }
        if (detectable.isEmpty()) return default
        val root = rootInActiveWindow ?: return default
        val snap = snapshotTree(root)
        var best: UiScreen? = null
        var bestScore = -1
        for (sc in detectable) {
            val score = scoreScreen(sc.detect!!, snap) ?: continue
            if (score > bestScore) { best = sc; bestScore = score }
        }
        if ((best ?: default)?.key != currentScreen?.key) {
            // Ayuda para escribir reglas de detección: qué hay en la pantalla ahora mismo
            dbg("pantalla -> ${(best ?: default)?.key ?: "ninguna"}; actividad=$lastWindowClass")
            dbg("  textos=${snap.texts.distinct().take(30)}")
            dbg("  descripciones=${snap.descs.distinct().take(30)}")
            dbg("  ids=${snap.ids.distinct().take(30)}")
        }
        return best ?: default
    }

    override fun onInterrupt() {
        dbg("onInterrupt")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        gestureThread.quitSafely()
        dbg("onDestroy")
        super.onDestroy()
    }

    // ----------------------------------------------------------------
    // Construcción de la lista de nodos
    // ----------------------------------------------------------------

    /**
     * Recorre el árbol de la ventana activa (en profundidad) y construye una lista con
     * los nodos visibles que son interactuables o contienen texto.
     *
     * Los nodos que son solo texto y cuelgan de un contenedor pulsable se omiten:
     * su texto ya forma parte del nombre del contenedor (ver [accessibleName]),
     * así no se lee dos veces ("Ajustes, botón" y luego "Ajustes, texto").
     */
    fun refreshNodeList(preserveIndex: Boolean = false) {
        val previousIndex = currentIndex
        if (!preserveIndex) virtualElement = null
        val root = rootInActiveWindow
        if (root == null) {
            interactiveNodes = emptyList()
            currentIndex = -1
            dbg("refreshNodeList: rootInActiveWindow=null")
            updateActiveConfig()
            return
        }
        noteWindowPackage(root.packageName)
        updateActiveConfig()
        val result = mutableListOf<AccessibilityNodeInfo>()
        // Recorrido en profundidad (preorden): sigue el orden de lectura visual,
        // a diferencia de BFS, que mezclaba niveles del árbol.
        // (nodo, ¿tiene un ancestro pulsable?)
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        stack.addLast(root to false)
        truncated = false

        while (stack.isNotEmpty()) {
            if (result.size >= MAX_NODES) { truncated = true; break }
            val (node, underClickable) = stack.removeLast()
            if (!node.isVisibleToUser) continue

            val hasText = !node.text.isNullOrBlank() ||
                          !node.contentDescription.isNullOrBlank()
            val isInteractive = node.isClickable || node.isFocusable || node.isEditable

            if (isInteractive || (hasText && !underClickable)) {
                result.add(node)
            }
            val childFlag = underClickable || node.isClickable
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to childFlag) }
            }
        }

        interactiveNodes = result
        currentIndex = when {
            result.isEmpty() -> -1
            preserveIndex && previousIndex >= 0 -> previousIndex.coerceAtMost(result.size - 1)
            else -> 0
        }
        rebuildVocabulary()
        dbg("refreshNodeList: ${result.size} nodos, index=$currentIndex")
    }

    // ----------------------------------------------------------------
    // Numeración de elementos ("lee todo") y búsqueda
    // ----------------------------------------------------------------

    /**
     * Palabras de los nombres de los elementos en pantalla. Core las añade en
     * caliente a la gramática de Vosk para poder buscar por nombre.
     */
    @Volatile private var vocabulary: List<String> = emptyList()
    fun getVocabulary(): List<String> = configPhrases + vocabulary

    private val WORD_REGEX = Regex("^[a-záéíóúüñ]{2,20}$")

    private fun rebuildVocabulary() {
        val words = LinkedHashSet<String>()
        val sources = interactiveNodes + numbered
        for (n in sources) {
            val name = try { accessibleName(n).first } catch (_: Throwable) { null } ?: continue
            for (w in name.lowercase().split(Regex("[^\\p{L}0-9]+"))) {
                if (WORD_REGEX.matches(w)) words.add(w)
                if (words.size >= MAX_VOCAB) break
            }
            if (words.size >= MAX_VOCAB) break
        }
        vocabulary = words.toList()
    }

    /** Instantánea numerada: elemento N = numbered[N-1]. Solo cambia con "lee todo" / búsqueda. */
    private var numbered: List<AccessibilityNodeInfo> = emptyList()
    private var truncated = false

    private fun buildNumbering() {
        refreshNodeList()
        numbered = interactiveNodes.toList()
        rebuildVocabulary()
        dbg("buildNumbering: ${numbered.size} elementos numerados (app=$foregroundPackage)")
        numbered.forEachIndexed { i, n ->
            val r = Rect(); n.getBoundsInScreen(r)
            dbg("  #${i + 1} ${n.className} [${r.left},${r.top}][${r.right},${r.bottom}] " +
                "centro=(${r.centerX()},${r.centerY()}) id=${n.viewIdResourceName} " +
                "text=${n.text?.toString()?.take(40)} desc=${n.contentDescription?.toString()?.take(40)}")
        }
    }

    /** Descripción para listas: "<tipo> <n>: <nombre>, <estado>". */
    private fun describeNumbered(node: AccessibilityNodeInfo, n: Int): String {
        val role = roleOf(node)
        if (node.isEditable && node.isPassword) return "campo de contraseña $n"
        val (rawName, weak) = accessibleName(node)
        val name = rawName?.let { SpeechText.speakable(it, 80) }
        val parts = mutableListOf<String>()
        when {
            name == null -> parts.add("sin nombre")
            weak -> parts.add("sin etiqueta, $name")
            else -> parts.add(name)
        }
        if (node.isEditable) {
            val content = node.text.clean()
            if (content != null && content != hintOf(node)) parts.add("contiene ${SpeechText.speakable(content, 80)}")
            else parts.add("vacío")
        }
        parts.addAll(stateOf(node))
        return "$role $n: ${parts.joinToString(", ")}"
    }

    /** "lee todo": numera la interfaz y la lee entera. */
    fun readAllElements(): String {
        buildNumbering()
        if (numbered.isEmpty()) return "no hay elementos en pantalla"
        val sb = StringBuilder()
        sb.append("La interfaz tiene ${numbered.size} elementos")
        if (truncated) sb.append(", mostrando solo los primeros $MAX_NODES")
        sb.append(". ")
        numbered.forEachIndexed { i, n -> sb.append(describeNumbered(n, i + 1)).append(". ") }
        dbg("readAll -> ${numbered.size} elementos")
        return sb.toString().trim()
    }

    /**
     * Selecciona el elemento número [n] (de la última numeración). Devuelve un
     * mensaje de error, o null si quedó seleccionado.
     */
    fun selectNumbered(n: Int): String? {
        virtualElement = null
        if (numbered.isEmpty()) return "no hay números asignados, di lee todo"
        if (n !in 1..numbered.size) return "no existe el elemento $n, hay ${numbered.size}"
        val node = numbered[n - 1]
        if (!node.refresh()) return "el elemento $n ya no está disponible, di lee todo para renumerar"
        val idx = interactiveNodes.indexOf(node)
        if (idx >= 0) {
            currentIndex = idx
        } else {
            interactiveNodes = numbered
            currentIndex = n - 1
        }
        dbg("selectNumbered($n) -> index=$currentIndex")
        return null
    }

    /** Número del nodo en la numeración vigente, o 0 si no está. */
    private fun numberOf(node: AccessibilityNodeInfo): Int = numbered.indexOf(node) + 1

    /**
     * "busca elemento con texto X": busca en la pantalla actual (renumerando,
     * lo que no cambia nada si la pantalla es la misma), selecciona la primera
     * coincidencia y dice su número.
     */
    fun searchElements(query: String): String {
        buildNumbering()
        val words = SpeechText.normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return "no he entendido qué buscar"
        if (numbered.isEmpty()) return "no hay elementos en pantalla"

        val matches = numbered.withIndex().filter { (_, node) ->
            val hay = SpeechText.normalize(searchableText(node))
            words.all { hay.contains(it) }
        }.map { it.index }

        if (matches.isEmpty()) return "no he encontrado ningún elemento con ${words.joinToString(" ")}"
        selectNumbered(matches[0] + 1)
        val first = describeNumbered(numbered[matches[0]], matches[0] + 1)
        return if (matches.size == 1) first
        else "${matches.size} coincidencias. $first. También: elementos " +
             matches.drop(1).take(5).joinToString(", ") { (it + 1).toString() }
    }

    private fun searchableText(node: AccessibilityNodeInfo): String =
        listOfNotNull(
            accessibleName(node).first,
            node.text.clean(),
            node.contentDescription.clean(),
            idToWords(node.viewIdResourceName)
        ).joinToString(" ")

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
        val (rawName, weak) = accessibleName(node)
        val name = rawName?.let { SpeechText.speakable(it, 100) }
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
            if (content != null && content != hintOf(node)) parts.add("contiene: ${SpeechText.speakable(content, 140)}")
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
        virtualElement?.let { return describeVirtualDetailed(it) }
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
        run {
            val dmx = resources.displayMetrics
            val px = r.centerX() * 100 / dmx.widthPixels.coerceAtLeast(1)
            val py = r.centerY() * 100 / dmx.heightPixels.coerceAtLeast(1)
            sb.append(". coordenadas ${r.centerX()} ${r.centerY()}, $px por ciento y $py por ciento")
        }
        sb.append(". elemento ${currentIndex + 1} de ${interactiveNodes.size}")
        val num = numberOf(node)
        if (num > 0) sb.append(". número $num")
        idToWords(node.viewIdResourceName)?.let { sb.append(". identificador: $it") }
        dbg("detail [$currentIndex] -> $sb")
        return sb.toString()
    }

    // ----------------------------------------------------------------
    // Navegación
    // ----------------------------------------------------------------

    /** Descripción breve del elemento seleccionado (no mueve la selección). */
    fun readCurrentNode(): String {
        virtualElement?.let { return describeVirtual(it) }
        if (interactiveNodes.isEmpty()) {
            return "no hay elementos. Prueba a decir siguiente o anterior."
        }
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        var desc = describeNode(node)
        val n = numberOf(node)
        if (n > 0) desc += ". elemento $n"
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
        virtualElement = null
        ensureListIsFresh()
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        currentIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % interactiveNodes.size
        return readCurrentNode()
    }

    /** Mueve la selección al elemento anterior (circular) y lo anuncia. */
    fun previousNode(): String {
        virtualElement = null
        ensureListIsFresh()
        if (interactiveNodes.isEmpty()) return "no hay elementos"
        currentIndex = if (currentIndex <= 0) interactiveNodes.size - 1 else currentIndex - 1
        return readCurrentNode()
    }

    // ----------------------------------------------------------------
    // Acciones
    // ----------------------------------------------------------------

    fun clickCurrentNode(): String {
        virtualElement?.let { return pressElement(it) }
        forcedScreenKey = null
        val node = getCurrentNode() ?: return "no hay elemento seleccionado"
        if (!node.refresh()) {
            refreshNodeList()
            return "el elemento ya no está disponible, lista actualizada"
        }
        val how = clickNode(node)
        dbg("click [$currentIndex] -> $how")
        return when (how) {
            "click" -> "pulsado"
            "gesture" -> "pulsado por coordenadas"
            else -> "no se pudo pulsar"
        }
    }

    fun performBack(): String {
        forcedScreenKey = null
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        dbg("back -> ok=$ok")
        return if (ok) "atrás" else "no se pudo volver atrás"
    }

    fun scroll(direction: ScrollDirection): String {
        virtualElement?.let { el ->
            if (!el.hasPoint) return "este cursor no tiene posición"
            val (w, h) = screenSize()
            return if (swipeFrom(el.x!!.toPx(w), el.y!!.toPx(h), direction, w, h)) "desplazado"
            else "no se pudo desplazar, ${gestureHint()}"
        }
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

    // ----------------------------------------------------------------
    // Gestos: clicks "de ratón" por coordenadas
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun canGesture(): Boolean =
        ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0

    private fun gestureHint(): String =
        if (!canGesture()) "falta canPerformGestures en la configuración del servicio de accesibilidad"
        else "el sistema rechazó el gesto"

    /**
     * Envía el gesto SIN bloquear el hilo principal. Los resultados de dispatchGesture se
     * entregan a través del hilo principal del servicio, así que esperarlos aquí lo bloquearía
     * hasta el timeout y daría un falso "cancelado". Se devuelve si el sistema ACEPTÓ el gesto;
     * el resultado final solo se registra en el log (un "cancelado" puede ser normal si la
     * pantalla cambió justo al pulsar).
     */
    private fun dispatchAsync(gesture: GestureDescription): Boolean {
        val sent = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { dbg("gesto completado") }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    dbg("gesto cancelado (puede ser normal si la pantalla cambió)")
                }
            }, gestureHandler)
        } catch (t: Throwable) {
            dbg("dispatchGesture THREW ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!sent) dbg("dispatchGesture rechazado (canGesture=${canGesture()})")
        return sent
    }

    private fun stroke(path: Path, startMs: Long, durMs: Long) =
        GestureDescription.StrokeDescription(path, startMs, durMs.coerceAtLeast(1))

    /** Pulsación simple en (x, y) en píxeles de pantalla. */
    fun tapAt(x: Float, y: Float, durationMs: Long = 60): Boolean {
        dbg("tap ($x, $y) ${durationMs}ms")
        val p = Path().apply { moveTo(x, y) }
        return dispatchAsync(GestureDescription.Builder().addStroke(stroke(p, 0, durationMs)).build())
    }

    fun longPressAt(x: Float, y: Float, durationMs: Long = 900): Boolean {
        dbg("longpress ($x, $y) ${durationMs}ms")
        val p = Path().apply { moveTo(x, y) }
        return dispatchAsync(GestureDescription.Builder().addStroke(stroke(p, 0, durationMs)).build())
    }

    fun doubleTapAt(x: Float, y: Float): Boolean {
        dbg("doubletap ($x, $y)")
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        return dispatchAsync(
            GestureDescription.Builder().addStroke(stroke(p1, 0, 40)).addStroke(stroke(p2, 140, 40)).build()
        )
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        dbg("swipe ($x1,$y1) -> ($x2,$y2) ${durationMs}ms")
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchAsync(GestureDescription.Builder().addStroke(stroke(p, 0, durationMs)).build())
    }

    /**
     * Desliza desde (x, y). Mismo criterio que scroll(): ARRIBA/IZQUIERDA = ver contenido anterior
     * (el dedo se mueve hacia abajo/derecha); ABAJO/DERECHA = ver contenido posterior.
     */
    private fun swipeFrom(x: Float, y: Float, dir: ScrollDirection, w: Int, h: Int): Boolean {
        val dy = h * 0.35f
        val dx = w * 0.35f
        val maxX = (w - 1).toFloat(); val maxY = (h - 1).toFloat()
        return when (dir) {
            ScrollDirection.UP    -> swipe(x, y, x, (y + dy).coerceAtMost(maxY))
            ScrollDirection.DOWN  -> swipe(x, y, x, (y - dy).coerceAtLeast(0f))
            ScrollDirection.LEFT  -> swipe(x, y, (x + dx).coerceAtMost(maxX), y)
            ScrollDirection.RIGHT -> swipe(x, y, (x - dx).coerceAtLeast(0f), y)
        }
    }

    /**
     * Pulsa un nodo como lo haría un ratón: ACTION_CLICK sobre el nodo o su primer ancestro
     * pulsable y, si no hay o falla, un toque real en el centro de sus límites.
     * Devuelve "click", "gesture" o null si no se pudo.
     */
    private fun clickNode(node: AccessibilityNodeInfo): String? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "click"
                break
            }
            n = n.parent
            depth++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.isEmpty) return null
        return if (tapAt(r.exactCenterX(), r.exactCenterY())) "gesture" else null
    }

    private fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isLongClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            n = n.parent
            depth++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return !r.isEmpty && longPressAt(r.exactCenterX(), r.exactCenterY())
    }

    // ----------------------------------------------------------------
    // Cursores configurados por app
    // ----------------------------------------------------------------

    private fun noConfigMessage(): String =
        "no hay configuración para esta aplicación" + (foregroundPackage?.let { ", paquete $it" } ?: "")

    private fun describeVirtual(el: UiElement): String {
        val sb = StringBuilder(el.displayName)
        if (!el.description.equals(el.displayName, ignoreCase = true)) sb.append(". ").append(el.description)
        if (!el.hasPoint && !el.hasSelector) sb.append(". solo informativo")
        return sb.toString()
    }

    private fun pctOf(c: Coord, total: Int): Int =
        if (c.percent) c.value.toInt() else (c.toPx(total) * 100 / total.coerceAtLeast(1)).toInt()

    private fun describeVirtualDetailed(el: UiElement): String {
        val (w, h) = screenSize()
        val sb = StringBuilder(el.displayName).append(". ").append(el.longDescription)
        val act = when (el.action) {
            "longpress", "mantener" -> "mantener pulsado"
            "doubletap", "doble" -> "doble pulsación"
            "swipe", "deslizar" -> "deslizar"
            else -> "pulsar"
        }
        if (el.hasPoint || el.hasSelector) sb.append(". acción: $act")
        else sb.append(". solo informativo")
        if (el.hasPoint) sb.append(". posición ${pctOf(el.x!!, w)} por ciento horizontal, ${pctOf(el.y!!, h)} por ciento vertical")
        if (el.hasSelector) sb.append(". se localiza por selector")
        activeConfig?.let { sb.append(". configuración de ${it.appKey}, versión ${it.configVersion ?: "general"}") }
        return sb.toString()
    }

    private fun findByDesc(node: AccessibilityNodeInfo, needle: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        if (node.isVisibleToUser &&
            node.contentDescription?.toString()?.lowercase()?.contains(needle) == true) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findByDesc(c, needle, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findBySelector(el: UiElement): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        el.selId?.let { id ->
            val full = if (id.contains(":id/")) id else "${foregroundPackage ?: root.packageName}:id/$id"
            root.findAccessibilityNodeInfosByViewId(full).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        el.selText?.let { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        el.selDesc?.let { d -> findByDesc(root, d.lowercase(), 0)?.let { return it } }
        return null
    }

    /**
     * Ejecuta la acción de un cursor: primero por selector (id/texto/descripción), que es más
     * robusto a cambios de diseño, y si no se encuentra, por coordenadas.
     */
    private fun pressElement(el: UiElement): String {
        forcedScreenKey = null   // pulsar suele cambiar de pantalla: volver a detectarla
        dbg("pressElement ${el.key} action=${el.action} point=${el.hasPoint} selector=${el.hasSelector}")
        if (el.hasSelector) {
            val node = findBySelector(el)
            if (node != null) {
                val ok = when (el.action) {
                    "longpress", "mantener" -> longClickNode(node)
                    "doubletap", "doble" -> {
                        val r = Rect(); node.getBoundsInScreen(r)
                        doubleTapAt(r.exactCenterX(), r.exactCenterY())
                    }
                    else -> clickNode(node) != null
                }
                return if (ok) "pulsado" else "no se pudo pulsar ${el.displayName}"
            }
            if (!el.hasPoint) return "no encuentro ${el.displayName} en la pantalla"
            dbg("selector sin resultado, se usan coordenadas")
        }
        if (!el.hasPoint) return "${el.displayName} es solo informativo, no se puede pulsar"

        val (w, h) = screenSize()
        val x = el.x!!.toPx(w)
        val y = el.y!!.toPx(h)
        val ok = when (el.action) {
            "longpress", "mantener" -> longPressAt(x, y, if (el.durationMs > 0) el.durationMs else 900)
            "doubletap", "doble" -> doubleTapAt(x, y)
            "swipe", "deslizar" -> {
                val x2 = el.x2?.toPx(w)
                val y2 = el.y2?.toPx(h)
                if (x2 == null || y2 == null) return "${el.displayName} no tiene destino de deslizamiento"
                swipe(x, y, x2, y2, if (el.durationMs > 0) el.durationMs else 350)
            }
            else -> tapAt(x, y, if (el.durationMs > 0) el.durationMs else 60)
        }
        return if (ok) "pulsado" else "no se pudo pulsar, ${gestureHint()}"
    }

    private fun screenLabel(): String = currentScreen?.displayName ?: "sin pantalla"

    /** "cursor primer chat": selecciona el cursor y lo describe. */
    fun cursorTo(phrase: String): String {
        updateActiveConfig()
        val cfg = activeConfig ?: return noConfigMessage()
        val found = cfg.find(phrase, currentScreen)
        if (found.isEmpty()) return "no hay ningún cursor llamado $phrase en la pantalla ${screenLabel()}"
        virtualElement = found[0]
        dbg("cursor -> ${found[0].key} (${found.size} coincidencias, pantalla=${currentScreen?.key})")
        return describeVirtual(found[0]) + if (found.size > 1) ". hay ${found.size} coincidencias" else ""
    }

    /** "pulsa primer chat": selecciona y ejecuta el cursor directamente. */
    fun pressByPhrase(phrase: String): String {
        updateActiveConfig()
        val cfg = activeConfig ?: return noConfigMessage()
        val found = cfg.find(phrase, currentScreen)
        if (found.isEmpty()) return "no hay ningún cursor llamado $phrase en la pantalla ${screenLabel()}"
        virtualElement = found[0]
        return pressElement(found[0])
    }

    /** "lee cursores": lista los cursores de la pantalla actual de la app en primer plano. */
    fun listCursors(): String {
        updateActiveConfig()
        val cfg = activeConfig ?: return noConfigMessage()
        val els = cfg.elementsFor(currentScreen)
        if (els.isEmpty()) return "la pantalla ${screenLabel()} de ${cfg.appKey} no tiene cursores"
        val sb = StringBuilder("En ${cfg.appKey}, pantalla ${screenLabel()}, hay ${els.size} cursores. ")
        els.take(40).forEach { el ->
            sb.append(el.displayName)
            if (!el.description.equals(el.displayName, ignoreCase = true)) sb.append(": ").append(el.description)
            sb.append(". ")
        }
        if (els.size > 40) sb.append("y ${els.size - 40} más.")
        return sb.toString().trim()
    }

    /** "pantalla ajustes": fuerza la pantalla actual (hasta pulsar algo, volver atrás o cambiar de app). */
    fun screenTo(name: String): String {
        updateActiveConfig()
        val cfg = activeConfig ?: return noConfigMessage()
        if (cfg.screens.isEmpty()) return "${cfg.appKey} no tiene pantallas definidas"
        val sc = cfg.findScreen(name)
            ?: return "no conozco la pantalla $name. Las pantallas son: " + cfg.screens.joinToString(", ") { it.displayName }
        forcedScreenKey = sc.key
        virtualElement = null
        updateActiveConfig()
        return "pantalla ${sc.displayName}, ${cfg.elementsFor(sc).size} cursores"
    }

    /** "pantalla automática": vuelve a detectar la pantalla sola. */
    fun screenAuto(): String {
        forcedScreenKey = null
        virtualElement = null
        updateActiveConfig()
        return "detección automática de pantalla, " + (currentScreen?.let { "ahora: ${it.displayName}" } ?: "no reconozco la pantalla")
    }

    /** "qué pantalla es": pantalla actual y cómo se decidió. */
    fun screenInfo(): String {
        updateActiveConfig()
        val cfg = activeConfig ?: return noConfigMessage()
        val cur = currentScreen
        val how = if (forcedScreenKey != null) "forzada" else "detectada"
        val sb = StringBuilder()
        if (cur == null) sb.append("No reconozco la pantalla de ${cfg.appKey}.")
        else sb.append("Pantalla $how: ${cur.displayName}. ${cur.description}.")
        if (cfg.screens.isNotEmpty()) {
            sb.append(" Pantallas disponibles: ").append(cfg.screens.joinToString(", ") { it.displayName }).append(".")
        }
        return sb.toString()
    }

    /** "recarga configuración": relee los ficheros ahora. */
    fun reloadConfig(): String {
        val msg = AppConfigManager.reload()
        updateActiveConfig()
        return msg + ". " + (activeConfig?.let {
            "${it.appKey}, pantalla ${screenLabel()}: ${it.elementsFor(currentScreen).size} cursores"
        } ?: "esta aplicación no tiene configuración")
    }

    /** "estado de la configuración": diagnóstico hablado. */
    fun configStatus(): String {
        updateActiveConfig()
        return AppConfigManager.statusFor(activeConfig, foregroundPackage) +
            (if (activeConfig != null) " Pantalla actual: ${screenLabel()}." else "")
    }
}