package io.github.stringmanolo.vocalia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.stringmanolo.vocalia.core.AppLauncher
import io.github.stringmanolo.vocalia.core.CommandParser
import io.github.stringmanolo.vocalia.core.VocaliaCore
import io.github.stringmanolo.vocalia.tts.TtsManager
import io.github.stringmanolo.vocalia.util.InternalDebug
import java.text.Normalizer

class VocaliaService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vocalia_main_channel"

        @Volatile var isRunning: Boolean = false
            private set
    }

    private lateinit var ttsManager: TtsManager
    private lateinit var voskEngine: VoskEngine
    private lateinit var core: VocaliaCore
    private lateinit var appLauncher: AppLauncher

    override fun onCreate() {
        super.onCreate()
        InternalDebug.mark("Service: onCreate")

        appLauncher = AppLauncher(this)
        appLauncher.preload()
        val appNames = appLauncher.getAllInstalledAppNames()
        val aliases = appLauncher.getKnownAliases()
        InternalDebug.mark("Service: ${appNames.size} apps, ${aliases.size} aliases")
        val grammar = buildGrammar(appNames, aliases)
        InternalDebug.mark("Service: grammar de ${grammar.length} chars")

        ttsManager = TtsManager(this)
        voskEngine = VoskEngine(this)
        core = VocaliaCore(
            voskEngine = voskEngine,
            ttsManager = ttsManager,
            commandParser = CommandParser(appLauncher),
            appLauncher = appLauncher,
            log = { msg -> InternalDebug.mark("[core] $msg") }
        )

        ttsManager.onEngineReady = {
            InternalDebug.mark("Service: TTS listo, arrancando core")
            core.start(grammar)
        }
        ttsManager.init()
    }


        private fun buildGrammar(appNames: List<String>, aliases: Set<String>): String {
        val commands = mutableListOf(
            // Wake words (vocalia / boca lia)
            "vocalia", "vocalía", "boca lia", "boca lía", "bocalia",
            // Comandos
            "que hora es", "que hora", "la hora", "hora es",
            "que dia es", "que dia", "que fecha", "fecha",
            "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "silencio", "callate",
            "hola", "que tal", "como estas",
            "usa voz inteligente", "voz inteligente", "usa voz por defecto", "voz por defecto",
            "escucha continua", "modo continuo", "escucha constante",
            "sal de escucha continua", "desactiva escucha continua", "desactiva modo continuo",
            "pausa el tts", "pausa el texto", "pausa la lectura", "pausa lectura", "pausa voz",
            "para el tts", "para el texto", "para la lectura",
            "pausa", "para", "parar", "pausar",
            "continua el tts", "continua el texto", "continua la lectura", "continua lectura", "continua voz",
            "reanuda el tts", "reanuda la lectura", "sigue leyendo", "continua", "reanuda", "sigue",
            "lista apps", "lista de apps", "que apps tengo", "lista de aplicaciones",
            "listame las apps", "lista de apps disponibles", "todas las apps", "todas las aplicaciones",
            "cierra esta app", "cierra la app actual", "cierra esto",
            "cierra la app que esté abierta", "cierra lo que hay delante",
            "ayuda", "que puedes hacer", "que sabes hacer",
            "dime todo lo que puedes hacer", "dime lo que puedes hacer",
            "guia", "comandos", "lista de comandos",
            "que comandos tienes"
        )

        val added = mutableSetOf<String>()
        fun addUnique(s: String) {
            if (s.isNotBlank() && added.add(s)) commands.add(s)
        }

        val openPrefixes = listOf("abre", "abrir")
        val closePrefixes = listOf("cierra", "cerrar", "mata", "matar")

        for (app in appNames) {
            val clean = app.replace("\"", "").replace("\\", "").trim()
            if (clean.isBlank()) continue
            val words = clean.split("\\s+".toRegex())
            if (words.size <= 2) addUnique(clean)
            for (p in openPrefixes) addUnique("$p $clean")
            for (p in closePrefixes) addUnique("$p $clean")

            val sinTildes = normalize(clean)
            if (sinTildes != clean && sinTildes.isNotBlank()) {
                if (words.size <= 2) addUnique(sinTildes)
                for (p in openPrefixes) addUnique("$p $sinTildes")
                for (p in closePrefixes) addUnique("$p $sinTildes")
            }
        }

        for (alias in aliases) {
            val clean = alias.replace("\"", "").replace("\\", "").trim()
            if (clean.isBlank()) continue
            addUnique(clean)
            for (p in openPrefixes) addUnique("$p $clean")
            for (p in closePrefixes) addUnique("$p $clean")
        }

        commands.add("[unk]")
        return commands.joinToString(",", "[", "]") { "\"$it\"" }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        InternalDebug.mark("Service: onDestroy")
        try { core.stop() } catch (_: Exception) {}
        try { voskEngine.shutdown() } catch (_: Exception) {}
        try { ttsManager.shutdown() } catch (_: Exception) {}
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_description) }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
