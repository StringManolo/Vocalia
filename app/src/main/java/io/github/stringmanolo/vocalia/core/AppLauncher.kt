package io.github.stringmanolo.vocalia.core

import android.app.ActivityManager
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
        "fennec" to listOf("org.mozilla.fennec_fdroid"),
        "brave" to listOf("com.brave.browser"),
        "tor" to listOf("org.torproject.torbrowser"),
        "torbrowser" to listOf("org.torproject.torbrowser"),
        "youtube" to listOf("com.google.android.youtube", "org.schabi.newpipe"),
        "newpipe" to listOf("org.schabi.newpipe"),
        "spotify" to listOf("com.spotify.music"),
        "musica" to listOf("com.spotify.music", "com.huawei.music"),
        "camara" to listOf("com.android.camera", "com.huawei.camera"),
        "camera" to listOf("com.android.camera", "com.huawei.camera"),
        "cam" to listOf("com.android.camera", "com.huawei.camera"),
        "foto" to listOf("com.android.camera", "com.huawei.camera"),
        "galeria" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "gallery" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "gal" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "fotos" to listOf("com.android.gallery3d", "com.huawei.photos", "com.google.android.apps.photos"),
        "photos" to listOf("com.android.gallery3d", "com.huawei.photos"),
        "photoeditor" to listOf("com.iudesk.android.photo.editor"),
        "fotoeditor" to listOf("com.iudesk.android.photo.editor"),
        "editor de fotos" to listOf("com.iudesk.android.photo.editor"),
        "ajustes" to listOf("com.android.settings"),
        "configuracion" to listOf("com.android.settings"),
        "config" to listOf("com.android.settings"),
        "settings" to listOf("com.android.settings"),
        "contactos" to listOf(
            "com.android.contacts", "com.huawei.contacts",
            "com.google.android.contacts", "com.samsung.android.app.contacts"
        ),
        "contacts" to listOf("com.android.contacts", "com.huawei.contacts", "com.google.android.contacts"),
        "telefono" to listOf(
            "com.huawei.contacts", "com.huawei.dialer",
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer"
        ),
        "phone" to listOf(
            "com.huawei.contacts", "com.huawei.dialer",
            "com.android.dialer", "com.google.android.dialer"
        ),
        "dialer" to listOf(
            "com.huawei.contacts", "com.huawei.dialer",
            "com.android.dialer", "com.google.android.dialer"
        ),
        "marcador" to listOf("com.huawei.contacts", "com.huawei.dialer", "com.android.dialer"),
        "llamar" to listOf(
            "com.huawei.contacts", "com.huawei.dialer",
            "com.android.dialer", "com.google.android.dialer"
        ),
        "mensajes" to listOf(
            "com.android.mms", "com.huawei.message",
            "com.google.android.apps.messaging", "com.samsung.android.messaging"
        ),
        "messages" to listOf("com.android.mms", "com.huawei.message", "com.google.android.apps.messaging"),
        "sms" to listOf("com.android.mms", "com.huawei.message", "com.google.android.apps.messaging"),
        "calculadora" to listOf("com.android.calculator2", "com.huawei.calculator", "com.google.android.calculator"),
        "calculator" to listOf("com.android.calculator2", "com.huawei.calculator", "com.google.android.calculator"),
        "calcu" to listOf("com.android.calculator2", "com.huawei.calculator"),
        "reloj" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "clock" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "alarma" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "alarm" to listOf("com.android.deskclock", "com.huawei.deskclock"),
        "calendario" to listOf("com.android.calendar", "com.huawei.calendar"),
        "calendar" to listOf("com.android.calendar", "com.huawei.calendar"),
        "notas" to listOf("com.android.notes", "com.huawei.notepad"),
        "notes" to listOf("com.android.notes", "com.huawei.notepad"),
        "archivos" to listOf("com.android.documentsui", "com.huawei.hidisk"),
        "files" to listOf("com.android.documentsui", "com.huawei.hidisk"),
        "maps" to listOf("com.google.android.apps.maps", "com.huawei.maps"),
        "mapas" to listOf("com.google.android.apps.maps", "com.huawei.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "correo" to listOf("com.google.android.gm", "com.huawei.email"),
        "email" to listOf("com.google.android.gm", "com.huawei.email"),
        "aurora" to listOf("com.aurora.store"),
        "appgallery" to listOf("com.huawei.appmarket"),
        "anki" to listOf("com.ichi2.anki"),
        "ankidroid" to listOf("com.ichi2.anki"),
        "tarjetas" to listOf("com.ichi2.anki"),
        "estudio" to listOf("com.ichi2.anki"),
        "anqui" to listOf("com.ichi2.anki"),
        "fdroid" to listOf("org.fdroid.fdroid"),
        "efe" to listOf("org.fdroid.fdroid"),
        "efedroide" to listOf("org.fdroid.fdroid"),
        "tienda libre" to listOf("org.fdroid.fdroid"),
        "setedit" to listOf("io.github.muntashirakon.setedit", "by4a.setedit22"),
        "sed" to listOf("io.github.muntashirakon.setedit", "by4a.setedit22"),
        "editor" to listOf("io.github.muntashirakon.setedit", "by4a.setedit22"),
        "ajustes avanzados" to listOf("io.github.muntashirakon.setedit", "by4a.setedit22"),
        "gbox" to listOf("com.gbox.android"),
        "yibox" to listOf("com.gbox.android"),
        "ge box" to listOf("com.gbox.android"),
        "powermenu" to listOf("com.stringmanolo.powermenu", "io.github.visnkmr.powermenu"),
        "power menu" to listOf("com.stringmanolo.powermenu", "io.github.visnkmr.powermenu"),
        "firewall" to listOf("eu.faircode.netguard", "app.greyshirts.firewall"),
        "osu" to listOf("ru.nsu.ccfit.zuev.osuplus"),
        "osudroid" to listOf("ru.nsu.ccfit.zuev.osuplus"),
        "ritmo" to listOf("ru.nsu.ccfit.zuev.osuplus"),
        "juego ritmo" to listOf("ru.nsu.ccfit.zuev.osuplus"),
        "o su" to listOf("ru.nsu.ccfit.zuev.osuplus"),
        "apkexport" to listOf("com.stringmanolo.apkexport", "com.ses.app.apkexport"),
        "apk export" to listOf("com.stringmanolo.apkexport", "com.ses.app.apkexport"),
        "exportar apk" to listOf("com.stringmanolo.apkexport", "com.ses.app.apkexport"),
        "hackerskeyboard" to listOf("org.pocketworkstation.pckeyboard", "com.stringmanolo.hackerskeyboard"),
        "hackers keyboard" to listOf("org.pocketworkstation.pckeyboard", "com.stringmanolo.hackerskeyboard"),
        "hackers" to listOf("org.pocketworkstation.pckeyboard", "com.stringmanolo.hackerskeyboard"),
        "keyboard" to listOf(
            "org.pocketworkstation.pckeyboard", "com.stringmanolo.hackerskeyboard", "com.touchtype.swiftkey"
        ),
        "teclado" to listOf(
            "org.pocketworkstation.pckeyboard", "com.stringmanolo.hackerskeyboard", "com.touchtype.swiftkey"
        ),
        "floatingvolume" to listOf("floatingvolumecontrol.io.github.stringmanolo"),
        "floating volumen" to listOf("floatingvolumecontrol.io.github.stringmanolo"),
        "volumen flotante" to listOf("floatingvolumecontrol.io.github.stringmanolo"),
        "control volumen" to listOf("floatingvolumecontrol.io.github.stringmanolo"),
        "screenrecorder" to listOf("com.kimcy929.screenrecorder"),
        "grabador" to listOf("com.kimcy929.screenrecorder"),
        "grabador pantalla" to listOf("com.kimcy929.screenrecorder"),
        "grabar pantalla" to listOf("com.kimcy929.screenrecorder"),
        "proton" to listOf("ch.protonvpn.android"),
        "protonvpn" to listOf("ch.protonvpn.android"),
        "vpn" to listOf("ch.protonvpn.android", "com.windscribe.vpn"),
        "vocalia" to listOf("io.github.stringmanolo.vocalia", "io.github.stringmanolo.vocalia.debug"),
        "voz galeria" to listOf("io.github.stringmanolo.vocalia", "io.github.stringmanolo.vocalia.debug"),
        "voz a media" to listOf("io.github.stringmanolo.vocalia", "io.github.stringmanolo.vocalia.debug"),
        "boca lia" to listOf("io.github.stringmanolo.vocalia", "io.github.stringmanolo.vocalia.debug"),
        "office" to listOf("com.microsoft.office.officehubrow"),
        "terminal" to listOf("com.termux"),
        "termux" to listOf("com.termux"),
        "consola" to listOf("com.termux"),
        "filmora" to listOf("com.wondershare.filmorago"),
        "filmorago" to listOf("com.wondershare.filmorago"),
        "panther" to listOf("com.stringmanolo.panther")
    )

    // Transliteración ruso → español para que espeak-ng pueda leer nombres cirílicos
    private val cyrillicToSpanish: Map<Char, String> = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Е' to "E",
        'Ё' to "Yo", 'Ж' to "Zh", 'З' to "Z", 'И' to "I", 'Й' to "Y", 'К' to "K",
        'Л' to "L", 'М' to "M", 'Н' to "N", 'О' to "O", 'П' to "P", 'Р' to "R",
        'С' to "S", 'Т' to "T", 'У' to "U", 'Ф' to "F", 'Х' to "J", 'Ц' to "Ts",
        'Ч' to "Ch", 'Ш' to "Sh", 'Щ' to "Shch", 'Ъ' to "", 'Ы' to "Y", 'Ь' to "",
        'Э' to "E", 'Ю' to "Yu", 'Я' to "Ya",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
        'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "j", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    @Volatile private var cachedInstalledApps: List<Pair<String, String>>? = null

    fun preload() {
        cachedInstalledApps = loadInstalledApps()
        val apps = cachedInstalledApps ?: emptyList()
        InternalDebug.mark("AppLauncher: ${apps.size} apps instaladas")
        apps.sortedBy { it.first.lowercase() }.forEach { (label, pkg) ->
            InternalDebug.mark("AppLauncher:   - $label ($pkg) -> TTS: ${transliterateCyrillic(label)}")
        }
        InternalDebug.mark("AppLauncher: ${knownApps.size} aliases")
    }

    fun getAllInstalledAppNames(): List<String> =
        getInstalledApps().map { it.first.lowercase() }.distinct().sorted()

    /** Devuelve los nombres para TTS, con transliteración cirílico→español. */
    fun getAllAppsForTTS(): List<String> =
        getInstalledApps().map { transliterateCyrillic(it.first) }.distinct().sorted()

    fun getKnownAliases(): Set<String> = knownApps.keys

    fun getAliasesForApp(appLabel: String): List<String> {
        val pkg = findPackage(appLabel) ?: return emptyList()
        return knownApps.filterValues { it.contains(pkg) }.keys.toList().sorted()
    }

    fun hasApp(name: String): Boolean = findPackage(name) != null

    fun findPackage(name: String): String? {
        val query = normalize(name)
        if (query.isBlank()) return null

        val curated = knownApps[query]
        if (curated != null) {
            for (pkg in curated) {
                try {
                    if (context.packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
                } catch (_: Throwable) {}
            }
        }

        val apps = getInstalledApps()
        // Normalizar label transliterado para que "galeria" encuentre "Галерея"
        val normalizedApps = apps.map { normalize(transliterateCyrillic(it.first)) to it }
        val exact = normalizedApps.firstOrNull { it.first == query }?.second
        val startsWith = exact ?: normalizedApps.firstOrNull { it.first.startsWith(query) }?.second
        val wordMatch = startsWith ?: normalizedApps.firstOrNull { (label, _) ->
            label.split("\\s+".toRegex()).any { it == query }
        }?.second
        return wordMatch?.second
    }

    private fun getInstalledApps(): List<Pair<String, String>> =
        cachedInstalledApps ?: loadInstalledApps().also { cachedInstalledApps = it }

    private fun loadInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
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
            for (pkg in curated) {
                if (launchPackage(pkg)) {
                    val label = getLabelForPackage(pkg) ?: appName
                    InternalDebug.mark("AppLauncher: launch OK -> $pkg ($label)")
                    return Result.Ok(label)
                }
            }
            InternalDebug.mark("AppLauncher: curado \"$query\" sin match instalado")
        }

        val pkg = findPackage(appName)
        if (pkg != null) {
            InternalDebug.mark("AppLauncher: match \"$query\" -> $pkg")
            if (launchPackage(pkg)) {
                val label = getLabelForPackage(pkg) ?: appName
                return Result.Ok(label)
            }
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
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        context.startActivity(launchIntent)
        InternalDebug.mark("AppLauncher: startActivity OK -> $pkg")
        true
    } catch (t: Throwable) {
        InternalDebug.mark("AppLauncher: launchPackage($pkg) THREW ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    fun bringVocaliaToFront() {
        try {
            val intent = Intent(context, Class.forName("io.github.stringmanolo.vocalia.MainActivity"))
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
            InternalDebug.mark("AppLauncher: Vocalia traída al frente")
        } catch (t: Throwable) {
            InternalDebug.mark("AppLauncher: bringVocaliaToFront THREW ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun closeApp(appName: String): Result {
        val pkg = findPackage(appName)
        if (pkg == null) {
            InternalDebug.mark("AppLauncher: closeApp NO encontrado \"$appName\"")
            return Result.NotFound(appName)
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val foreground = isInForeground(pkg)
        InternalDebug.mark("AppLauncher: closeApp(\"$appName\") pkg=$pkg foreground=$foreground")
        am.killBackgroundProcesses(pkg)
        InternalDebug.mark("AppLauncher: killBackgroundProcesses OK -> $pkg")
        val label = getLabelForPackage(pkg) ?: appName
        return Result.Ok(label)
    }

    private fun isInForeground(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        val tasks = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningTasks(1)
        tasks.isNotEmpty() && tasks[0].topActivity?.packageName == pkg
    } catch (_: Throwable) { false }

    /** Devuelve el label visible de la app (ej: "AnkiDroid") a partir de su packageName. */
    private fun getLabelForPackage(pkg: String): String? = try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Throwable) { null }

    /** Transliteración cirílico → español para que espeak-ng pueda leer nombres rusos. */
    private fun transliterateCyrillic(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            sb.append(cyrillicToSpanish[ch] ?: ch.toString())
        }
        return sb.toString()
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
}
