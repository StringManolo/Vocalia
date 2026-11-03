package io.github.stringmanolo.vocalia.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.stringmanolo.vocalia.SpeechText
import io.github.stringmanolo.vocalia.util.InternalDebug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// ====================================================================
// Modelo
// ====================================================================

/** Coordenada en píxeles (negativa = desde el borde derecho/inferior) o en porcentaje (0..100). */
data class Coord(val value: Double, val percent: Boolean) {
    fun toPx(total: Int): Float {
        var v = if (percent) total * value / 100.0 else value
        if (!percent && v < 0) v += total
        return v.toFloat().coerceIn(0f, (total - 1).coerceAtLeast(0).toFloat())
    }
}

/** Un "cursor" configurado: un punto/zona de la UI de una app con nombre, gramática y descripción. */
data class UiElement(
    val key: String,
    val displayName: String,
    /** Frases para la gramática de Vosk (minúsculas, con acentos). */
    val grammar: List<String>,
    /** Las mismas frases normalizadas (sin acentos) para comparar. */
    val matchKeys: List<String>,
    val description: String,
    val longDescription: String,
    /** tap | longpress | doubletap | swipe */
    val action: String,
    val x: Coord?, val y: Coord?,
    val x2: Coord?, val y2: Coord?,
    val durationMs: Long,
    /** Selectores (se prueban en orden; cada uno puede tener varias alternativas, p. ej. es/en). */
    val selIds: List<String>, val selTexts: List<String>, val selDescs: List<String>,
    /** "auto" = si el elemento no está a la vista, desplazar hasta encontrarlo; "none" = no desplazar. */
    val scrollMode: String,
    val scrollMax: Int,
    /** Volver al principio de la lista antes de buscar hacia abajo. */
    val scrollReset: Boolean,
    /** Para action = "scroll": "down" | "up". */
    val scrollDir: String,
    /** Para action = "scroll": cuántas veces. */
    val scrollTimes: Int
) {
    val hasPoint: Boolean get() = x != null && y != null
    val hasSelector: Boolean get() = selIds.isNotEmpty() || selTexts.isNotEmpty() || selDescs.isNotEmpty()
}

/** Condición para reconocer una pantalla por lo que hay en el árbol de accesibilidad. */
data class DetectCond(
    val id: String?, val text: String?, val desc: String?, val cls: String?,
    val activity: String?, val selected: Boolean
)

/** Reglas de detección: todas las de `all`, al menos una de `any` (si hay) y ninguna de `none`. */
data class ScreenDetect(
    val all: List<DetectCond>, val any: List<DetectCond>, val none: List<DetectCond>, val priority: Int
)

/** Una pantalla (categoría de pantalla) de una app: tiene sus propios cursores. */
data class UiScreen(
    val key: String,
    val displayName: String,
    val grammar: List<String>,
    val matchKeys: List<String>,
    val description: String,
    /** Pantalla que se usa si no se reconoce ninguna otra. */
    val isDefault: Boolean,
    val detect: ScreenDetect?,
    val elements: List<UiElement>
)

/** Cómo saber si hay sesión iniciada en una web/app. */
data class LoginRules(
    /** La web no sirve sin iniciar sesión (Gmail, ChatGPT, DeepSeek…). */
    val required: Boolean,
    val loggedIn: ScreenDetect?,
    val loggedOut: ScreenDetect?,
    /** Qué decir al usuario si no hay sesión. */
    val hint: String?
)

/** Configuración ya resuelta (dispositivo + versión instalada) de una app o web. */
data class AppUiConfig(
    val appKey: String,
    val packageName: String,
    val installedVersion: String?,
    /** Versión de la configuración elegida, o null si se usó solo el bloque base. */
    val configVersion: String?,
    val description: String,
    /** Cursores válidos en cualquier pantalla de la app. */
    val globalElements: List<UiElement>,
    val screens: List<UiScreen>,
    /** "app" o "web". */
    val kind: String = "app",
    /** Dirección de inicio (solo webs). */
    val url: String? = null,
    val login: LoginRules? = null,
    /** Nombre hablado de la web/app. */
    val spokenName: String = appKey
) {
    fun defaultScreen(): UiScreen? = screens.firstOrNull { it.isDefault }

    /** Cursores disponibles en [screen]: los globales + los de la pantalla (los de la pantalla ganan). */
    fun elementsFor(screen: UiScreen?): List<UiElement> {
        if (screen == null) return globalElements
        val m = LinkedHashMap<String, UiElement>()
        globalElements.forEach { m[it.key] = it }
        screen.elements.forEach { m[it.key] = it }
        return m.values.toList()
    }

    /** Busca cursores por frase hablada en [screen]: primero coincidencia exacta, luego por palabras. */
    fun find(query: String, screen: UiScreen?): List<UiElement> {
        val q = SpeechText.normalize(query)
        if (q.isEmpty()) return emptyList()
        val els = elementsFor(screen)
        val exact = els.filter { e -> e.matchKeys.any { it == q } }
        if (exact.isNotEmpty()) return exact
        val words = q.split(' ').filter { it.isNotEmpty() }
        return els.filter { e -> e.matchKeys.any { p -> words.all { p.contains(it) } } }
    }

    fun findScreen(query: String): UiScreen? {
        val q = SpeechText.normalize(query)
        if (q.isEmpty()) return null
        screens.firstOrNull { s -> s.matchKeys.any { it == q } }?.let { return it }
        val words = q.split(' ').filter { it.isNotEmpty() }
        return screens.firstOrNull { s -> s.matchKeys.any { p -> words.all { p.contains(it) } } }
    }
}

// ====================================================================
// Gestor
// ====================================================================

/**
 * Carga, fusiona y sirve los ficheros de configuración por app.
 *
 * - Los ficheros viajan en `assets/configs/` y se extraen a una carpeta PÚBLICA
 *   (`<externalFilesDir>/configs/`) en el primer arranque y tras cada actualización.
 *   Un fichero que el usuario haya modificado NO se pisa: la versión nueva se deja
 *   al lado como `<nombre>.new`.
 * - Se leen todos los `vocalia-*.json` de esa carpeta. Cada uno tiene una sección
 *   por dispositivo; solo se aplican las que corresponden a este móvil. Orden de
 *   prioridad (la última gana): default < fabricante < modelo; a igual prioridad,
 *   por nombre de fichero.
 * - Se recargan solos cuando cambia algún fichero (o con el comando de voz).
 *
 * El parser es `org.json` de Android, que es TOLERANTE: admite comentarios
 * (`//`, `#`, `/* */`), claves sin comillas y comillas simples.
 */
object AppConfigManager {

    private const val ASSETS_DIR = "configs"
    private const val PREFS = "vocalia_configs"

    private var ctx: Context? = null
    private var dir: File? = null

    /** appKey -> objeto de la app ya fusionado entre todos los ficheros/dispositivos. */
    private var apps: Map<String, JSONObject> = emptyMap()
    /** paquete -> appKey */
    private var packageIndex: Map<String, String> = emptyMap()
    /** webKey -> objeto de la web ya fusionado */
    private var webs: Map<String, JSONObject> = emptyMap()
    /** dominio -> webKey */
    private var domainIndex: Map<String, String> = emptyMap()
    private var fingerprint: String = ""
    private val problems = mutableListOf<String>()
    private val applied = mutableListOf<String>()

    private var manufacturerKeys: Set<String> = emptySet()
    private var modelKeys: Set<String> = emptySet()
    var deviceCandidates: List<String> = emptyList()
        private set

    private fun log(msg: String) = InternalDebug.mark("[config] $msg")

    private fun norm(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    // ----------------------------------------------------------------
    // Inicio
    // ----------------------------------------------------------------

    @Synchronized
    fun init(context: Context) {
        if (ctx != null) return
        val c = context.applicationContext
        ctx = c
        dir = (c.getExternalFilesDir("configs") ?: File(c.filesDir, "configs")).also { it.mkdirs() }
        computeDeviceKeys(c)
        try { extractBundled(c) } catch (t: Throwable) { log("extractBundled THREW ${t.javaClass.simpleName}: ${t.message}") }
        reload()
    }

    fun configDirPath(): String = dir?.absolutePath ?: "(sin carpeta)"

    private fun computeDeviceKeys(c: Context) {
        val deviceName = try { Settings.Global.getString(c.contentResolver, "device_name") } catch (_: Throwable) { null }
        val man = norm(Build.MANUFACTURER)
        val brand = norm(Build.BRAND)
        manufacturerKeys = setOf(man, brand).filter { it.isNotEmpty() }.toSet()
        modelKeys = setOf(
            norm(Build.MODEL), norm(Build.DEVICE), norm(Build.PRODUCT),
            norm(Build.MANUFACTURER + Build.MODEL), norm(Build.BRAND + Build.MODEL),
            norm(deviceName)
        ).filter { it.isNotEmpty() }.toSet()
        deviceCandidates = (manufacturerKeys + modelKeys).toList()
        log("dispositivo: fabricante=${Build.MANUFACTURER} marca=${Build.BRAND} modelo=${Build.MODEL} " +
            "device=${Build.DEVICE} nombre=$deviceName")
        log("claves de dispositivo reconocidas: $deviceCandidates")
    }

    // ----------------------------------------------------------------
    // Extracción de los ficheros incluidos en la app
    // ----------------------------------------------------------------

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun extractBundled(c: Context) {
        val target = dir ?: return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val names = c.assets.list(ASSETS_DIR)?.toList().orEmpty()
        for (name in names) {
            val bytes = c.assets.open("$ASSETS_DIR/$name").use { it.readBytes() }
            val sha = sha256(bytes)
            val out = File(target, name)
            val lastBundled = prefs.getString("sha:$name", null)
            when {
                !out.exists() -> {
                    out.writeBytes(bytes); prefs.edit().putString("sha:$name", sha).apply()
                    log("extraído $name")
                }
                sha256(out.readBytes()) == sha -> {
                    prefs.edit().putString("sha:$name", sha).apply()
                }
                lastBundled != null && sha256(out.readBytes()) == lastBundled -> {
                    // El usuario no lo tocó y la app trae una versión nueva: actualizar
                    out.writeBytes(bytes); prefs.edit().putString("sha:$name", sha).apply()
                    log("actualizado $name (sin cambios locales)")
                }
                lastBundled != sha -> {
                    // Modificado por el usuario y la app trae una versión distinta: no pisar
                    File(target, "$name.new").writeBytes(bytes)
                    prefs.edit().putString("sha:$name", sha).apply()
                    log("$name tiene cambios locales; versión nueva guardada como $name.new")
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Carga y fusión
    // ----------------------------------------------------------------

    private fun listFiles(): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.startsWith("vocalia-") && f.name.endsWith("-apps-config.json") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun computeFingerprint(files: List<File>): String =
        files.joinToString("|") { "${it.name}:${it.lastModified()}:${it.length()}" }

    /** Recarga si algún fichero cambió desde la última carga. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        if (ctx == null) return false
        if (computeFingerprint(listFiles()) == fingerprint) return false
        reload()
        return true
    }

    private class Entry(val spec: Int, val file: String, val deviceKey: String, val apps: JSONObject?, val webs: JSONObject?)

    /** 0 = todos, 1 = fabricante, 2 = modelo, null = no es para este dispositivo. */
    private fun specificity(key: String, obj: JSONObject?): Int? {
        val k = norm(key)
        if (k in setOf("", "default", "all", "any", "todos", "general")) return 0
        val keys = mutableListOf(k)
        obj?.optJSONArray("match")?.let { arr ->
            for (i in 0 until arr.length()) keys.add(norm(arr.optString(i)))
        }
        if (keys.any { it in modelKeys }) return 2
        if (keys.any { it in manufacturerKeys }) return 1
        return null
    }

    private fun deepMerge(base: JSONObject, over: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        val iter = over.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            val v = over.get(key)
            when {
                v == JSONObject.NULL -> out.remove(key)          // "chat3": null  => desactivar
                v is JSONObject && out.optJSONObject(key) != null ->
                    out.put(key, deepMerge(out.getJSONObject(key), v))
                else -> out.put(key, v)
            }
        }
        return out
    }

    /** Lectura de una clave sin distinguir mayúsculas y con varios alias. */
    private fun JSONObject.ci(vararg names: String): Any? {
        val wanted = names.map { it.lowercase() }
        val iter = keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.lowercase() in wanted) return opt(k)
        }
        return null
    }
    private fun JSONObject.ciObj(vararg n: String): JSONObject? = ci(*n) as? JSONObject
    private fun JSONObject.ciStr(vararg n: String): String? =
        (ci(*n) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    @Synchronized
    fun reload(): String {
        val files = listFiles()
        fingerprint = computeFingerprint(files)
        problems.clear(); applied.clear()
        val entries = mutableListOf<Entry>()

        for (f in files) {
            val root = try { JSONObject(f.readText()) } catch (t: Throwable) {
                problems.add("${f.name}: ${t.message}")
                log("ERROR leyendo ${f.name}: ${t.message}")
                continue
            }
            // Admite { "devices": {...} } o dispositivos directamente en la raíz
            val devices = root.ciObj("devices", "dispositivos") ?: root
            val dIter = devices.keys()
            while (dIter.hasNext()) {
                val devKey = dIter.next()
                val dev = devices.optJSONObject(devKey) ?: continue
                val spec = specificity(devKey, dev) ?: continue
                val appsObj = dev.ciObj("apps", "aplicaciones")
                val websObj = dev.ciObj("webs", "sitios")
                if (appsObj == null && websObj == null) continue
                entries.add(Entry(spec, f.name, devKey, appsObj, websObj))
            }
        }

        entries.sortWith(compareBy<Entry>({ it.spec }, { it.file }))
        val merged = linkedMapOf<String, JSONObject>()
        val mergedWebs = linkedMapOf<String, JSONObject>()
        for (e in entries) {
            applied.add("${e.file}:${e.deviceKey}(${e.spec})")
            e.apps?.let { appsObj ->
                val aIter = appsObj.keys()
                while (aIter.hasNext()) {
                    val appKey = aIter.next()
                    val obj = appsObj.optJSONObject(appKey) ?: continue
                    val id = appKey.trim().lowercase()
                    merged[id] = merged[id]?.let { old -> deepMerge(old, obj) } ?: JSONObject(obj.toString())
                }
            }
            e.webs?.let { websObj ->
                val wIter = websObj.keys()
                while (wIter.hasNext()) {
                    val webKey = wIter.next()
                    val obj = websObj.optJSONObject(webKey) ?: continue
                    val id = webKey.trim().lowercase()
                    mergedWebs[id] = mergedWebs[id]?.let { old -> deepMerge(old, obj) } ?: JSONObject(obj.toString())
                }
            }
        }
        apps = merged
        webs = mergedWebs
        val dIndex = hashMapOf<String, String>()
        for ((key, obj) in mergedWebs) {
            (obj.ci("domains", "dominios") as? JSONArray)?.let { a ->
                for (i in 0 until a.length()) a.optString(i).trim().lowercase().removePrefix("www.")
                    .takeIf { d -> d.isNotEmpty() }?.let { d -> dIndex[d] = key }
            }
            if (key.contains('.')) dIndex[key.removePrefix("www.")] = key
        }
        domainIndex = dIndex

        val index = hashMapOf<String, String>()
        for ((key, obj) in merged) {
            val pkgs = mutableSetOf<String>()
            obj.ciStr("package", "canonical-name", "canonicalname", "paquete")?.let { pkgs.add(it) }
            (obj.ci("packages", "paquetes") as? JSONArray)?.let { a ->
                for (i in 0 until a.length()) a.optString(i).takeIf { s -> s.isNotBlank() }?.let { s -> pkgs.add(s) }
            }
            if (key.contains('.')) pkgs.add(key)
            pkgs.forEach { index[it] = key }
        }
        packageIndex = index

        val msg = "configuración cargada: ${merged.size} aplicaciones y ${mergedWebs.size} webs de ${files.size} ficheros; " +
                  "secciones aplicadas: $applied" + if (problems.isNotEmpty()) "; ERRORES: $problems" else ""
        log(msg)
        return "configuración cargada, ${merged.size} aplicaciones y ${mergedWebs.size} webs" +
               if (problems.isNotEmpty()) ", con ${problems.size} errores en ficheros" else ""
    }

    // ----------------------------------------------------------------
    // Consulta
    // ----------------------------------------------------------------

    private fun installedVersion(pkg: String): String? = try {
        ctx?.packageManager?.getPackageInfo(pkg, 0)?.versionName
    } catch (_: Throwable) { null }

    /** "v13.1.0" -> [13,1,0] */
    private fun versionParts(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun compareVersions(a: String, b: String): Int {
        val pa = versionParts(a); val pb = versionParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** Configuración de la app [pkg] para este dispositivo y su versión instalada, o null. */
    @Synchronized
    fun configFor(pkg: String): AppUiConfig? {
        val appKey = packageIndex[pkg] ?: return null
        val app = apps[appKey] ?: return null
        return resolve(appKey, app, pkg, installedVersion(pkg), "app")
    }

    /**
     * Configuración de la web cuyo dominio coincide con [host] (p. ej. "www.google.com" o
     * "mail.google.com"), o null. Se prefiere el dominio más específico.
     */
    @Synchronized
    fun webFor(host: String?): AppUiConfig? {
        val h = host?.trim()?.lowercase()?.removePrefix("www.") ?: return null
        if (h.isEmpty()) return null
        val key = domainIndex.entries
            .filter { (d, _) -> h == d || h.endsWith(".$d") }
            .maxByOrNull { it.key.length }?.value ?: return null
        val obj = webs[key] ?: return null
        return resolve(key, obj, "web:$key", null, "web")
    }

    /** Webs conocidas: (clave, nombres hablados, dirección de inicio, descripción). */
    class WebInfo(val key: String, val phrases: List<String>, val url: String?, val description: String)

    @Synchronized
    fun allWebs(): List<WebInfo> = webs.map { (key, obj) ->
        val phrases = LinkedHashSet<String>()
        stringList(obj.ci("gramatica", "gramática", "grammar", "names", "nombres")).forEach {
            grammarPhrase(it).takeIf { p -> p.isNotEmpty() }?.let { p -> phrases.add(p) }
        }
        val first = (obj.ciStr("name", "nombre") ?: keyToWords(key.substringBefore('.')))
        grammarPhrase(first).takeIf { it.isNotEmpty() }?.let { phrases.add(it) }
        WebInfo(key, phrases.toList(), obj.ciStr("url"), obj.ciStr("description", "descripcion", "descripción") ?: "")
    }

    private fun resolve(appKey: String, app: JSONObject, pkg: String, installed: String?, kind: String): AppUiConfig {
        var ui = app.ciObj("ui", "interfaz") ?: JSONObject()
        var screensObj = app.ciObj("screens", "pantallas") ?: JSONObject()
        var groupsObj = app.ciObj("groups", "grupos") ?: JSONObject()
        var chosen: String? = null

        val versions = app.ciObj("versions", "versiones")
        if (versions != null && versions.length() > 0) {
            val keys = mutableListOf<String>()
            val vIter = versions.keys(); while (vIter.hasNext()) keys.add(vIter.next())
            keys.sortWith { a, b -> compareVersions(a, b) }
            chosen = when {
                installed == null -> keys.last()
                else -> keys.lastOrNull { compareVersions(it, installed) <= 0 }
                    ?: if (ui.length() == 0 && screensObj.length() == 0) keys.first() else null
            }
            chosen?.let { v ->
                val vo = versions.optJSONObject(v)
                vo?.ciObj("ui", "interfaz")?.let { x -> ui = deepMerge(ui, x) }
                vo?.ciObj("screens", "pantallas")?.let { x -> screensObj = deepMerge(screensObj, x) }
                vo?.ciObj("groups", "grupos")?.let { x -> groupsObj = deepMerge(groupsObj, x) }
            }
        }
        if (chosen == null) chosen = app.ciStr("version", "fecha", "date")

        val groups = LinkedHashMap<String, List<UiElement>>()
        val gIter = groupsObj.keys()
        while (gIter.hasNext()) {
            val g = gIter.next()
            groups[g.lowercase()] = parseElements(groupsObj.optJSONObject(g))
        }

        val screens = mutableListOf<UiScreen>()
        val sIter = screensObj.keys()
        while (sIter.hasNext()) {
            val key = sIter.next()
            val o = screensObj.optJSONObject(key) ?: continue
            if (o.ci("enabled", "activo") == false) continue
            parseScreen(key, o, groups)?.let { sc -> screens.add(sc) }
        }

        return AppUiConfig(
            appKey = appKey, packageName = pkg, installedVersion = installed,
            configVersion = chosen,
            description = app.ciStr("description", "descripcion", "descripción") ?: "",
            globalElements = parseElements(ui), screens = screens,
            kind = kind,
            url = app.ciStr("url"),
            login = parseLogin(app.ciObj("login", "sesion", "sesión")),
            spokenName = app.ciStr("name", "nombre") ?: appKey
        )
    }

    private fun parseElements(ui: JSONObject?): List<UiElement> {
        val out = mutableListOf<UiElement>()
        if (ui == null) return out
        val uIter = ui.keys()
        while (uIter.hasNext()) {
            val key = uIter.next()
            val o = ui.optJSONObject(key) ?: continue
            if (o.ci("enabled", "activo") == false) continue
            parseElement(key, o)?.let { e -> out.add(e) }
        }
        return out
    }

    private fun parseLogin(o: JSONObject?): LoginRules? {
        if (o == null) return null
        return LoginRules(
            required = o.ci("required", "obligatoria", "necesaria") == true,
            loggedIn = parseDetect(o.ciObj("loggedIn", "conSesion", "conectado")),
            loggedOut = parseDetect(o.ciObj("loggedOut", "sinSesion", "desconectado")),
            hint = o.ciStr("hint", "aviso", "ayuda")
        )
    }

    private fun parseCond(o: JSONObject) = DetectCond(
        id = o.ciStr("id", "viewId"),
        text = o.ciStr("text", "texto"),
        desc = o.ciStr("desc", "descripcion", "contentDescription"),
        cls = o.ciStr("class", "clase"),
        activity = o.ciStr("activity", "actividad"),
        selected = o.ci("selected", "seleccionado") == true
    )

    private fun condList(v: Any?): List<DetectCond> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optJSONObject(i)?.let { parseCond(it) } }
        is JSONObject -> listOf(parseCond(v))
        else -> emptyList()
    }

    private fun parseDetect(d: JSONObject?): ScreenDetect? {
        if (d == null) return null
        var all = condList(d.ci("all", "todos"))
        val any = condList(d.ci("any", "alguno"))
        val none = condList(d.ci("none", "ninguno"))
        // Atajo: { "text": "..." } directamente en detect
        if (all.isEmpty() && any.isEmpty() && none.isEmpty()) {
            val c = parseCond(d)
            if (c.id != null || c.text != null || c.desc != null || c.cls != null || c.activity != null) all = listOf(c)
        }
        if (all.isEmpty() && any.isEmpty() && none.isEmpty()) return null
        return ScreenDetect(all, any, none, (d.ci("priority", "prioridad") as? Number)?.toInt() ?: 0)
    }

    private fun parseScreen(key: String, o: JSONObject, groups: Map<String, List<UiElement>>): UiScreen? {
        val phrases = LinkedHashSet<String>()
        stringList(o.ci("gramatica", "gramática", "grammar", "names", "nombres")).forEach {
            grammarPhrase(it).takeIf { p -> p.isNotEmpty() }?.let { p -> phrases.add(p) }
        }
        grammarPhrase(keyToWords(key)).takeIf { it.isNotEmpty() }?.let { phrases.add(it) }
        if (phrases.isEmpty()) return null

        // Cursores: primero los de los grupos incluidos, luego los propios (ganan por clave)
        val m = LinkedHashMap<String, UiElement>()
        stringList(o.ci("include", "incluir")).forEach { g ->
            groups[g.lowercase()]?.forEach { e -> m[e.key] = e }
        }
        parseElements(o.ciObj("ui", "interfaz")).forEach { e -> m[e.key] = e }

        return UiScreen(
            key = key, displayName = phrases.first(), grammar = phrases.toList(),
            matchKeys = phrases.map { SpeechText.normalize(it) }.distinct(),
            description = o.ciStr("descripcion", "descripción", "description") ?: phrases.first(),
            isDefault = o.ci("default", "porDefecto", "por_defecto") == true,
            detect = parseDetect(o.ciObj("detect", "detectar")),
            elements = m.values.toList()
        )
    }

    // ----------------------------------------------------------------
    // Parseo de elementos
    // ----------------------------------------------------------------

    private fun coordOf(o: JSONObject, px: String, pct: String): Coord? {
        (o.ci(pct) as? Number)?.let { return Coord(it.toDouble(), true) }
        return when (val v = o.ci(px)) {
            is Number -> Coord(v.toDouble(), false)
            is String -> {
                val s = v.trim()
                if (s.endsWith("%")) s.dropLast(1).trim().toDoubleOrNull()?.let { Coord(it, true) }
                else s.toDoubleOrNull()?.let { Coord(it, false) }
            }
            else -> null
        }
    }

    private fun stringList(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf { s -> s.isNotBlank() } }
        is String -> listOf(v).filter { it.isNotBlank() }
        else -> emptyList()
    }

    /** minúsculas con acentos, solo letras/dígitos/espacios (válido para la gramática de Vosk). */
    private fun grammarPhrase(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun keyToWords(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')

    private fun parseElement(key: String, o: JSONObject): UiElement? {
        val phrases = LinkedHashSet<String>()
        stringList(o.ci("gramatica", "gramática", "grammar", "names", "nombres")).forEach {
            grammarPhrase(it).takeIf { p -> p.isNotEmpty() }?.let { p -> phrases.add(p) }
        }
        grammarPhrase(keyToWords(key)).takeIf { it.isNotEmpty() }?.let { phrases.add(it) }
        if (phrases.isEmpty()) return null

        val sel = o.ciObj("selector", "find", "buscar")
        val action = (o.ciStr("action", "accion", "acción") ?: "tap").lowercase()
        val desc = o.ciStr("descripcion", "descripción", "description") ?: phrases.first()
        return UiElement(
            key = key,
            displayName = phrases.first(),
            grammar = phrases.toList(),
            matchKeys = phrases.map { SpeechText.normalize(it) }.distinct(),
            description = desc,
            longDescription = o.ciStr("descripcionLarga", "descripcion_larga", "descripciónLarga", "longDescription", "long") ?: desc,
            action = action,
            x = coordOf(o, "x", "xPct"), y = coordOf(o, "y", "yPct"),
            x2 = coordOf(o, "x2", "x2Pct"), y2 = coordOf(o, "y2", "y2Pct"),
            durationMs = (o.ci("durationMs", "duracion", "duración") as? Number)?.toLong()?.coerceIn(20, 5000) ?: 0L,
            selIds = stringList(sel?.ci("id", "viewId")),
            selTexts = stringList(sel?.ci("text", "texto")),
            selDescs = stringList(sel?.ci("desc", "contentDescription", "descripcion")),
            scrollMode = (o.ciObj("scroll", "desplazar")?.ciStr("mode", "modo")
                ?: (if (o.ci("scroll", "desplazar") is String) o.ciStr("scroll", "desplazar") else null)
                ?: "auto").lowercase(),
            scrollMax = (o.ciObj("scroll", "desplazar")?.ci("maxSteps", "maxPasos") as? Number)?.toInt()?.coerceIn(1, 30) ?: 8,
            scrollReset = o.ciObj("scroll", "desplazar")?.ci("resetFirst", "alPrincipio") == true,
            scrollDir = (o.ciStr("direction", "direccion", "dirección")
                ?: o.ciObj("scroll", "desplazar")?.ciStr("direction", "direccion") ?: "down").lowercase(),
            scrollTimes = (o.ci("times", "veces") as? Number)?.toInt()?.coerceIn(1, 30) ?: 1
        )
    }

    // ----------------------------------------------------------------
    // Estado (para diagnóstico por voz)
    // ----------------------------------------------------------------

    @Synchronized
    fun statusFor(cfg: AppUiConfig?, pkg: String?): String {
        val sb = StringBuilder()
        sb.append("${apps.size} aplicaciones configuradas")
        if (problems.isNotEmpty()) sb.append(", con ${problems.size} ficheros con errores")
        sb.append(". ")
        if (pkg == null) sb.append("No sé qué aplicación hay en primer plano.")
        else if (cfg == null) sb.append("La aplicación en primer plano no tiene configuración. Paquete $pkg.")
        else {
            sb.append("Aplicación ${cfg.appKey}, ${cfg.screens.size} pantallas y ${cfg.globalElements.size} cursores comunes. ")
            sb.append("Versión instalada ${cfg.installedVersion ?: "desconocida"}, ")
            sb.append("configuración para ${cfg.configVersion ?: "todas las versiones"}.")
        }
        return sb.toString()
    }
}