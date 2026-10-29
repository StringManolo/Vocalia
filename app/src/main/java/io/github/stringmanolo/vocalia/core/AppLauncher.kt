package io.github.stringmanolo.vocalia.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.github.stringmanolo.vocalia.util.InternalDebug

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
        "música" to listOf("com.spotify.music", "com.huawei.music"),
        "camara" to listOf("com.android.camera", "com.huawei.camera"),
        "cámara" to listOf("com.android.camera", "com.huawei.camera"),
        "galeria" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "galería" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "fotos" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "ajustes" to listOf("com.android.settings"),
        "configuracion" to listOf("com.android.settings"),
        "configuración" to listOf("com.android.settings"),
        "contactos" to listOf("com.android.contacts", "com.huawei.contacts"),
        "telefono" to listOf("com.android.dialer", "com.huawei.contacts"),
        "teléfono" to listOf("com.android.dialer", "com.huawei.contacts"),
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
        "app gallery" to listOf("com.huawei.appmarket"),
        "appgallery" to listOf("com.huawei.appmarket")
    )

    @Volatile private var cachedInstalledApps: List<Pair<String, String>>? = null

    /** Pre-carga la lista de apps instaladas. Llamar una vez al arrancar. */
    fun preload() {
        cachedInstalledApps = loadInstalledApps()
        InternalDebug.mark("AppLauncher: preload ${cachedInstalledApps?.size ?: 0} apps")
    }

    /** Lista de nombres visibles de todas las apps instaladas (lowercase, sin duplicados). */
    fun getAllInstalledAppNames(): List<String> {
        return getInstalledApps().map { it.first.lowercase() }.distinct().sorted()
    }

    /** true si el nombre corresponde a una app instalada (curada o por label). */
    fun hasApp(name: String): Boolean {
        val query = name.lowercase().trim()
        if (query.isBlank()) return false
        // 1. Curada
        if (knownApps.containsKey(query)) {
            return knownApps[query]!!.any { pkg ->
                try { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                catch (_: Throwable) { false }
            }
        }
        // 2. Por label
        return getInstalledApps().any { (label, _) -> label.lowercase() == query }
    }

    private fun getInstalledApps(): List<Pair<String, String>> =
        cachedInstalledApps ?: loadInstalledApps().also { cachedInstalledApps = it }

    private fun loadInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return try {
            pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
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
        val query = appName.lowercase().trim()
        InternalDebug.mark("AppLauncher: launch(\"$query\")")

        // 1. Curada
        val curated = knownApps[query]
        if (curated != null) {
            for (pkg in curated) {
                if (launchPackage(pkg)) return Result.Ok(appName)
            }
            InternalDebug.mark("AppLauncher: curado \"$query\" sin match instalado")
        }

        // 2. Búsqueda difusa por label
        val apps = getInstalledApps()
        InternalDebug.mark("AppLauncher: ${apps.size} apps instaladas (caché)")

        val exact = apps.firstOrNull { it.first.lowercase() == query }
        val partial = exact ?: apps.firstOrNull { it.first.lowercase().contains(query) }

        if (partial != null) {
            val (label, pkg) = partial
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
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        InternalDebug.mark("AppLauncher: startActivity OK -> $pkg")
        true
    } catch (t: Throwable) {
        InternalDebug.mark("AppLauncher: launchPackage($pkg) THREW ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}
