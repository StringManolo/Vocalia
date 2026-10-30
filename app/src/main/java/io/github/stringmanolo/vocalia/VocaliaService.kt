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
        InternalDebug.mark("Service: ${appNames.size} apps instaladas")
        val grammar = buildGrammar(appNames)
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

    private fun buildGrammar(appNames: List<String>): String {
        val commands = mutableListOf(
            "asistente", "asistentes",
            "que hora es", "que hora", "la hora", "hora es",
            "que dia es", "que dia", "que fecha", "fecha",
            "descansa", "descanso", "descansar", "cansa", "duerme", "dormir", "silencio", "callate",
            "hola", "que tal", "como estas",
            "usa voz inteligente", "voz inteligente", "usa voz por defecto", "voz por defecto",
            "escucha continua", "modo continuo", "escucha constante",
            "sal de escucha continua", "desactiva escucha continua", "desactiva modo continuo",
            "pausa el tts", "pausa el texto", "pausa la lectura", "pausa lectura", "pausa voz",
            "para el tts", "para el texto", "para la lectura",
            "continua el tts", "continua el texto", "continua la lectura", "continua lectura", "continua voz",
            "reanuda el tts", "reanuda la lectura", "sigue leyendo",
            "lista apps", "lista de apps", "que apps tengo", "lista de aplicaciones",
            "listame las apps", "lista de apps disponibles", "todas las apps"
        )
        // Solo dos prefijos: abre / abrir. Gramática reducida ~120 entradas vs ~340 antes.
        val prefixes = listOf("abre", "abrir")
        for (app in appNames) {
            val original = app.replace("\"", "").replace("\\", "").trim()
            if (original.isBlank()) continue
            for (p in prefixes) commands.add("$p $original")

            val sinTildes = Normalizer.normalize(original, Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
            if (sinTildes != original && sinTildes.isNotBlank()) {
                for (p in prefixes) commands.add("$p $sinTildes")
            }
        }
        commands.add("[unk]")
        return commands.joinToString(",", "[", "]") { "\"$it\"" }
    }

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
