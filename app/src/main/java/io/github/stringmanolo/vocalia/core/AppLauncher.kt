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

    /** Lista curada: nombre común → posibles packageNames (en orden de preferencia). */
    private val knownApps: Map<String, List<String>> = mapOf(
        // Mensajería
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "signal" to listOf("org.thoughtcrime.securesms"),

        // Navegadores
        "chrome" to listOf("com.android.chrome"),
        "navegador" to listOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser"),
        "firefox" to listOf("org.mozilla.firefox"),
        "brave" to listOf("com.brave.browser"),

        // Media
        "youtube" to listOf("com.google.android.youtube", "org.schabi.newpipe"),
        "newpipe" to listOf("org.schabi.newpipe"),
        "spotify" to listOf("com.spotify.music"),
        "musica" to listOf("com.spotify.music", "com.huawei.music", "com.android.music"),
        "música" to listOf("com.spotify.music", "com.huawei.music", "com.android.music"),

        // Cámara y galería
        "camara" to listOf("com.android.camera", "com.huawei.camera", "com.google.android.GoogleCamera"),
        "cámara" to listOf("com.android.camera", "com.huawei.camera", "com.google.android.GoogleCamera"),
        "galeria" to listOf("com.android.gallery3d", "com.huawei.photos", "com.google.android.apps.photos"),
        "galería" to listOf("com.android.gallery3d", "com.huawei.photos", "com.google.android.apps.photos"),
        "fotos" to listOf("com.android.gallery3d", "com.huawei.photos", "com.google.android.apps.photos"),

        // Sistema
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

        // Mapas
        "maps" to listOf("com.google.android.apps.maps", "com.huawei.maps"),
        "mapas" to listOf("com.google.android.apps.maps", "com.huawei.maps"),

        // Correo
        "gmail" to listOf("com.google.android.gm"),
        "correo" to listOf("com.google.android.gm", "com.huawei.email"),

        // Tiendas
        "aurora" to listOf("com.aurora.store"),
        "fdroid" to listOf("org.fdroid.fdroid"),
        "app gallery" to listOf("com.huawei.appmarket"),
        "appgallery" to listOf("com.huawei.appmarket")
    )

    fun launch(appName: String): Result {
        val query = appName.lowercase().trim()
        InternalDebug.mark("AppLauncher: launch(\"$query\")")

        // 1. Lista curada
        val curated = knownApps[query]
        if (curated != null) {
            for (pkg in curated) {
                if (launchPackage(pkg)) {
                    return Result.Ok(appName)
                }
            }
            InternalDebug.mark("AppLauncher: curado \"$query\" sin match instalado")
        }

        // 2. Búsqueda difusa por nombre visible
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        InternalDebug.mark("AppLauncher: ${apps.size} apps instaladas con launcher")

        // Match exacto primero, luego parcial
        val exact = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase() == query
        }
        val partial = exact ?: apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(query)
        }

        if (partial != null) {
            val pkg = partial.activityInfo.packageName
            val label = partial.loadLabel(pm).toString()
            InternalDebug.mark("AppLauncher: match difuso \"$query\" -> $pkg ($label)")
            if (launchPackage(pkg)) {
                return Result.Ok(appName)
            }
        }

        InternalDebug.mark("AppLauncher: NO encontrado \"$query\"")
        return Result.NotFound(appName)
    }

    private fun launchPackage(pkg: String): Boolean {
        return try {
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
}
