package io.github.stringmanolo.vocalia.core

/**
 * Textos de ayuda y de bienvenida. Pensados para oírse: frases cortas, un tema cada vez,
 * siempre con un ejemplo y siempre diciendo qué decir ("di: vocalia, ...").
 */
object HelpTopics {

    // ---- Bienvenida (al abrir la app) ----
    const val WELCOME_LOADING = "Espera, cargando voz inteligente."

    const val WELCOME_GUIDE =
        "Hola, soy Vocalia, tu asistente de voz. " +
        "Para hablarme, di primero: vocalia. Y después lo que quieras. " +
        "Por ejemplo: vocalia, abre WhatsApp. " +
        "Si necesitas ayuda, di: vocalia, ayuda. Te explicaré las cosas por temas, poco a poco. " +
        "Para que me calle mientras hablo, di: vocalia, para. " +
        "Para saber qué hay en la pantalla del móvil, di: vocalia, modo pantalla. " +
        "Ahora te escucho."

    /** Se dice (y se abren los ajustes) cuando falta activar el servicio de accesibilidad. */
    const val ACCESSIBILITY_GUIDE =
        "Para usar el modo pantalla hay que activar Vocalia en los ajustes de accesibilidad. " +
        "Te los abro ahora. " +
        "En la lista, busca Vocalia: puede estar en servicios instalados o en servicios descargados. " +
        "Pulsa sobre Vocalia. Activa el interruptor y acepta el aviso del sistema. " +
        "Después vuelve atrás y di: vocalia, modo pantalla. " +
        "Solo hay que hacerlo una vez. Si no lo consigues, pide ayuda a otra persona."

    const val WELCOME_NO_ACCESSIBILITY =
        "Antes de usar el modo pantalla falta activar la accesibilidad. " +
        "Para que te explique cómo y abra los ajustes, di: vocalia, ayuda accesibilidad."

    // ---- Menús ----
    const val MENU_GENERAL =
        "Puedes pedirme ayuda sobre un tema. Di: vocalia, ayuda, y el tema. " +
        "Los temas son: aplicaciones, pantalla, lectura, cursores, webs, sesión, accesibilidad, voz, y escucha continua. " +
        "Por ejemplo, di: vocalia, ayuda aplicaciones. " +
        "Si estás dentro de una aplicación con ayuda propia, di solo: vocalia, ayuda, y te explico lo de esa aplicación. " +
        "Para volver a esta ayuda general, di: vocalia, ayuda general. " +
        "Si quieres oír todo de una vez, di: vocalia, ayuda completa."

    const val MENU_PANTALLA =
        "Estás en el modo pantalla. Puedo ayudarte con: lectura, cursores, webs, y sesión. " +
        "Di: vocalia, ayuda, y el tema. Por ejemplo: vocalia, ayuda lectura. " +
        "Para saber dónde estás, di: vocalia, dónde estoy. " +
        "Para salir, di: vocalia, salir del modo pantalla."

    // ---- Temas ----
    private val topics: Map<String, String> = mapOf(
        "aplicaciones" to
            "Ayuda sobre aplicaciones. " +
            "Para abrir una aplicación, di: vocalia, abre, y su nombre. Por ejemplo: vocalia, abre WhatsApp. " +
            "Para cerrarla, di: vocalia, cierra, y su nombre. " +
            "Para cerrar la que tienes delante, di: vocalia, cierra esta aplicación. " +
            "Para saber qué aplicaciones tienes, di: vocalia, lista de aplicaciones.",

        "pantalla" to
            "Ayuda sobre el modo pantalla. Sirve para manejar cualquier aplicación o web sin tocar el móvil. " +
            "Para entrar, di: vocalia, modo pantalla. Te diré dónde estás. " +
            "Para saber qué hay delante, di: vocalia, dónde estoy. " +
            "Para oír todo lo que hay, di: vocalia, lee todo. Te lo leeré por páginas. " +
            "Para moverte de uno en uno, di: siguiente, o anterior. " +
            "Para pulsar el elemento que acabas de oír, di: pulsa. " +
            "Para volver, di: atrás. Para desplazar, di: arrastra abajo, o arrastra arriba. " +
            "Para salir, di: salir del modo pantalla. " +
            "Todas las órdenes empiezan por: vocalia.",

        "lectura" to
            "Ayuda sobre lectura. " +
            "Para oír toda la pantalla, di: vocalia, lee todo. Te la leo por páginas. " +
            "Para seguir con la lista, di: vocalia, más elementos. " +
            "Para oír solo los textos, di: vocalia, lee los textos. " +
            "Para oír solo lo que puedes pulsar, di: vocalia, lee los botones. " +
            "Para saber dónde puedes escribir, di: vocalia, lee los campos. " +
            "Para repetir el elemento actual, di: vocalia, lee. Para saber más de él, di: vocalia, detalle. " +
            "Para que me detenga mientras hablo, di: vocalia, para.",

        "cursores" to
            "Ayuda sobre cursores. Los cursores son botones y zonas que ya conozco de algunas aplicaciones y webs. " +
            "Para oír los que hay en esta pantalla, di: vocalia, lee cursores. " +
            "Para pulsar uno, di: vocalia, pulsa, y su nombre. Por ejemplo: vocalia, pulsa primer chat. " +
            "Para colocarte en uno sin pulsar, di: vocalia, cursor, y su nombre. " +
            "Si me equivoco de pantalla, di: vocalia, pantalla, y su nombre. " +
            "Para saber en cuál estoy, di: vocalia, qué pantalla es.",

        "webs" to
            "Ayuda sobre webs. " +
            "Para abrir una web que conozco, di: vocalia, abre la web de, y su nombre. " +
            "Por ejemplo: vocalia, abre la web de wikipedia. " +
            "Para oír las que conozco, di: vocalia, qué webs conoces. " +
            "Para saber en qué web estás, di: vocalia, dónde estoy. " +
            "Para saber si tienes la sesión iniciada, di: vocalia, he iniciado sesión.",

        "sesion" to
            "Ayuda sobre sesión. Algunas webs, como el correo o los asistentes de inteligencia artificial, " +
            "solo funcionan si has iniciado sesión con tu cuenta. " +
            "Para saber si la tienes, di: vocalia, he iniciado sesión. " +
            "Si no la tienes, te lo diré al entrar en esa web. " +
            "Escribir tu usuario y tu contraseña por voz estará disponible pronto. " +
            "Mientras tanto, pide a una persona de confianza que inicie la sesión una vez. " +
            "Después, la sesión suele mantenerse.",

        "accesibilidad" to ACCESSIBILITY_GUIDE,

        "voz" to
            "Ayuda sobre mi voz. " +
            "Para usar la voz natural, di: vocalia, usa voz inteligente. " +
            "Para volver a la voz sencilla, di: vocalia, usa voz por defecto. " +
            "Para que me detenga mientras hablo, di: vocalia, para. " +
            "Para que continúe, di: vocalia, continúa.",

        "escucha" to
            "Ayuda sobre escucha continua. " +
            "Normalmente tienes que decir vocalia antes de cada orden. " +
            "Si prefieres hablar sin decirlo, di: vocalia, escucha continua. " +
            "Para volver al modo normal, di: vocalia, desactiva escucha continua. " +
            "Para que descanse y deje de escuchar órdenes, di: vocalia, descansa.",

        "escritura" to
            "Ayuda sobre escribir. " +
            "Escribir mensajes, direcciones y contraseñas por voz estará disponible en la próxima versión. " +
            "Por ahora puedes pegar un texto que tengas copiado, diciendo: vocalia, pega."
    )

    /** Palabra dicha por el usuario -> clave del tema (o "completa"). null si no es un tema. */
    fun normalizeTopic(raw: String): String? {
        val t = raw.lowercase().trim()
            .removePrefix("de ").removePrefix("sobre ").removePrefix("del ").removePrefix("para ")
            .removePrefix("el ").removePrefix("la ").removePrefix("los ").removePrefix("las ").trim()
        return when (t) {
            "aplicaciones", "aplicacion", "aplicación", "apps", "app", "abrir", "abrir aplicaciones" -> "aplicaciones"
            "pantalla", "modo pantalla", "modo usar", "usar", "manejar", "manejar pantalla" -> "pantalla"
            "lectura", "leer", "lee", "leer pantalla" -> "lectura"
            "cursores", "cursor" -> "cursores"
            "webs", "web", "internet", "navegador", "paginas", "páginas", "paginas web", "páginas web" -> "webs"
            "sesion", "sesión", "cuenta", "cuentas", "contraseña", "contrasena", "iniciar sesion", "iniciar sesión" -> "sesion"
            "voz", "voces", "hablar" -> "voz"
            "escucha", "escucha continua", "continua", "continuo" -> "escucha"
            "escribir", "escritura", "dictado", "teclado" -> "escritura"
            "accesibilidad", "activar accesibilidad", "ajustes de accesibilidad" -> "accesibilidad"
            "general", "generales", "basica", "básica", "inicio" -> "general"
            "completa", "completo", "todo", "todas" -> "completa"
            else -> null
        }
    }

    fun topic(key: String): String? = when (key) {
        "completa" -> full()
        "general" -> MENU_GENERAL
        else -> topics[key]
    }

    /** Todos los temas seguidos (para "ayuda completa"). */
    fun full(): String =
        "Ayuda completa. " + listOf("aplicaciones", "pantalla", "lectura", "cursores", "webs", "sesion", "voz", "escucha", "escritura")
            .joinToString(" ") { topics.getValue(it) }
}
