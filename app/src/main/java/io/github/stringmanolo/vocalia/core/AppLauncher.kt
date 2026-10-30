package io.github.stringmanolo.vocalia.core

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import io.github.stringmanolo.vocalia.util.InternalDebug
import java.text.Normalizer

class AppLauncher(private val context: Context) {

    sealed class Result {
        data class Ok(val appName: String) : Result()
        data class NotFound(val appName: String) : Result()
        data class Error(val appName: String) : Result()
    }

    private val knownApps: Map<String, List<String>> = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "signal" to listOf("org.thoughtcrime.securesms"),
        "chrome" to listOf("com.android.chrome"),
        "navegador" to listOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser"),
        "firefox" to listOf("org.mozilla.firefox"),
        "brave" to listOf("com.brave.browser"),
        "youtube" to listOf("com.google.android.youtube", "org.schabi.newpipe"),
        "newpipe" to listOf("org.schabi.newpipe"),
        "spotify" to listOf("com.spotify.music"),
        "musica" to listOf("com.spotify.music", "com.huawei.music"),
        "camara" to listOf("com.android.camera", "com.huawei.camera"),
        "galeria" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "fotos" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "ajustes" to listOf("com.android.settings"),
        "configuracion" to listOf("com.android.settings"),
        "contactos" to listOf("com.android.contacts", "com.huawei.contacts"),
        "telefono" to listOf("com.android.dialer", "com.huawei.contacts"),
        "marcador" to listOf("com.android.dialer", "com.huawei.contacts"),
        "calculadora" to listOf("com.android.calculator2", "com.huawei.calculator"),
        "reloj" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "alarma" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "calendario" to listOf("com.android.calendar", "com.huawei.calendar"),
        "notas" to listOf("com.android.notes", "com.huawei.notepad"),
        "maps" to listOf("com.google.android.apps.maps", "com.huawei.maps"),
        "mapas" to listOf("com.google.android.apps.maps", "com.huawei.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "correo" to listOf("com.google.android.gm", "com.huawei.email"),
        "aurora" to listOf("com.aurora.store"),
        "fdroid" to listOf("org.fdroid.fdroid"),
        "appgallery" to listOf("com.huawei.appmarket")
    )

    @Volatile private var cachedInstalledApps: List<Pair<String, String>>? = null

    fun preload() {
        cachedInstalledApps = loadInstalledApps()
        InternalDebug.mark("AppLauncher: preload ${cachedInstalledApps?.size ?: 0} apps")
    }

    fun getAllInstalledAppNames(): List<String> =
        getInstalledApps().map { it.first.lowercase() }.distinct().sorted()

    /** Devuelve las primeras N apps instaladas (labels originales) para leer por TTS. */
    fun getTopAppsForTTS(limit: Int = 10): List<String> =
        getInstalledApps().map { it.first }.distinct().sorted().take(limit)

    fun hasApp(name: String): Boolean {
        val query = normalize(name)
        if (query.isBlank()) return false
        if (knownApps.containsKey(query)) {
            return knownApps[query]!!.any { pkg ->
                try { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                catch (_: Throwable) { false }
            }
        }
        return getInstalledApps().any { (label, _) -> normalize(label) == query }
    }

    private fun getInstalledApps(): List<Pair<String, String>> =
        cachedInstalledApps ?: loadInstalledApps().also { cachedInstalledApps = it }

    private fun loadInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return try {
            pm.queryIntentActivities(intent, 0).mapNotNull { ri: ResolveInfo ->
                val label = ri.loadLabel(pm)?.toString() ?: return@mapNotNull null
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                label to pkg
            }
        } catch (t: Throwable) {
            InternalDebug.mark("AppLauncher: loadInstalledApps THREW ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    fun launch(appName: String): Result {
        val query = normalize(appName)
        InternalDebug.mark("AppLauncher: launch(\"$appName\") norm=\"$query\"")

        val curated = knownApps[query]
        if (curated != null) {
            for (pkg in curated) if (launchPackage(pkg)) return Result.Ok(appName)
            InternalDebug.mark("AppLauncher: curado \"$query\" sin match instalado")
        }

        val apps = getInstalledApps()
        InternalDebug.mark("AppLauncher: ${apps.size} apps (caché)")
        val normalizedApps = apps.map { normalize(it.first) to it }
        val exact = normalizedApps.firstOrNull { it.first == query }?.second
        val startsWith = exact ?: normalizedApps.firstOrNull { it.first.startsWith(query) }?.second
        val wordMatch = startsWith ?: normalizedApps.firstOrNull { (label, _) ->
            label.split("\\s+".toRegex()).any { it == query }
        }?.second

        if (wordMatch != null) {
            val (label, pkg) = wordMatch
            InternalDebug.mark("AppLauncher: match \"$query\" -> $pkg ($label)")
            if (launchPackage(pkg)) return Result.Ok(appName)
        }

        InternalDebug.mark("AppLauncher: NO encontrado \"$query\"")
        return Result.NotFound(appName)
    }

    private fun launchPackage(pkg: String): Boolean = try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            InternalDebug.mark("AppLauncher: sin launchIntent para $pkg")
            return false
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        )
        context.startActivity(launchIntent)
        InternalDebug.mark("AppLauncher: startActivity OK -> $pkg")
        true
    } catch (t: Throwable) {
        InternalDebug.mark("AppLauncher: launchPackage($pkg) THREW ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
}
