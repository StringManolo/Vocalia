# Vocalia

**Tu Android, solo con la voz.** Asistente de voz offline, sin Google, sin manos, sin mirar la pantalla. Diseñado para conductores, personas ciegas, personas mayores, personas sin movilidad en las manos, y cualquiera que quiera controlar su teléfono sin tocarlo.

---

## 📥 Descargar

**Última versión estable:** [`v0.5.0-vocalia`](https://github.com/StringManolo/Vocalia/releases)

APK directo: [`debug-apk.zip`](https://github.com/StringManolo/Vocalia/releases/download/v0.5.0-vocalia/debug-apk.zip)

> 🚧 La rama `main` ya incluye novedades de la próxima versión (**v0.6.0**): modo pantalla con ayuda por temas, configuración por app y por web, pantallas, sesión iniciada, scroll automático y bienvenida al abrir la app. Ver [ROADMAP.md](ROADMAP.md).

> ⚠️ La APK está firmada con clave de debug. Android mostrará un aviso al instalar. Es normal: la app es sideload, no viene de Play Store. Quizás necesites desactivar Play Protect para permitir la instalación.

---

## 🎯 ¿Qué es Vocalia?

Un asistente de voz ligero que se ejecuta **íntegramente en tu dispositivo**, sin enviar audio a la nube, sin depender de servicios de Google Play, y sin que tengas que tocar la pantalla ni mirarla.

Piensa en Siri o Google Assistant, pero:

- **100% offline** para las tareas esenciales (voz a texto, texto a voz, comandos).
- **Sin Google Play Services**. Funciona en Huawei y en cualquier Android sin GMS.
- **Código abierto** (GPLv3 para uso personal).
- **Extensible** por módulos: TTS, STT, comandos, cada pieza se puede cambiar.
- **Controla cualquier app y web** mediante el modo pantalla (servicio de accesibilidad): te dice dónde estás, lee lo que hay, numera los elementos y pulsa solo con la voz.
- **Se explica solo**: al abrir la app te guía por voz, y la ayuda se pide **por temas** para no abrumar.
- **Configurable sin recompilar**: ficheros JSON por dispositivo, app y web que se editan y recargan en caliente.

### ¿Para quién es?

- 🚛 **Conductores** de camión, taxi, reparto, que quieren programar, gestionar negocios o consultar cosas mientras conducen sin distraerse.
- 👁️ **Personas ciegas o con baja visión** que necesitan un control por voz total de su Android.
- 👵 **Personas mayores** que necesitan que el móvil les explique qué pasa, con órdenes sencillas.
- 🖐️ **Personas sin movilidad en las manos** que no pueden tocar la pantalla.
- 🍳 **Usuarios normales** que quieren usar el móvil con las manos ocupadas (cocinando, en el taller, etc.).

> 📖 ¿Primera vez? Lee la [**Guía de uso**](GUIA_DE_USO.md): es lo que Vocalia también te explica hablando.

---

## ✨ Funcionalidades actuales

### Bienvenida y arranque automático

- Al **abrir la app**, el servicio arranca solo (si hay permiso de micrófono). Solo se detiene si pulsas el botón de detener.
- Vocalia dice con la voz sencilla *"Espera, cargando voz inteligente"*. Si la voz inteligente carga, la usa; si no, sigue con la de por defecto.
- Después te explica cómo usarla: *"di primero vocalia, y después lo que quieras"*, cómo pedir ayuda y cómo hacerla callar.
- La bienvenida se da una vez por arranque del servicio.

### Wake word

Di **"vocalia"** y el sistema te responde. No hay que pulsar ningún botón.

Solo se reconoce **al inicio** del texto (para no confundir "abre vocalia" con una wake word).

### Comandos por voz

```
# Básicos
"vocalia, hola"
"vocalia, cómo estás"
"vocalia, qué hora es"
"vocalia, qué día es"

# Ayuda POR TEMAS (no suelta todo de golpe)
"vocalia, ayuda"                  ← dice los temas
"vocalia, ayuda aplicaciones"     ← también: pantalla, lectura, cursores, webs,
                                     sesión, voz, escucha, escritura, completa

# Abrir / cerrar apps
"vocalia, abre Telegram"
"vocalia, abre tarjetas"          ← alias de AnkiDroid
"vocalia, abre ajustes"
"vocalia, cierra Telegram"
"vocalia, cierra esta app"        ← trae Vocalia al frente y descarta la actual

# Listar apps
"vocalia, lista de apps"          ← lee los nombres
"vocalia, lístame las apps"       ← lee los nombres + log de alias

# Webs conocidas
"vocalia, abre la web de wikipedia"
"vocalia, qué webs conoces"

# Modo continuo
"vocalia, escucha continua"       ← comandos sin wake word hasta "descansa"
"vocalia, desactiva escucha continua"

# Voz
"vocalia, usa voz inteligente"
"vocalia, usa voz por defecto"

# Control del TTS (durante una lectura)
"vocalia, para"                   ← también "pausa", "detente", "stop", "silencio"…
"vocalia, continúa"               ← reanuda desde la frase donde se quedó

# Modo pantalla (control de cualquier app o web) — ver sección siguiente
"vocalia, modo pantalla"
"vocalia, dónde estoy"

# Descansar
"vocalia, descansa"               ← Sale de los modos PANTALLA y ESCUCHA CONTINUA
```

También funciona **wake word + comando en un solo turno**:

```
"vocalia, qué hora es"   ← detecta wake word + comando a la vez
```

### 🖐️ Modo pantalla (control de cualquier app o web)

> Antes se llamaba *modo usar*. Los nombres antiguos (`modo usar`, `sal del modo usar`) siguen funcionando.

Con `vocalia, modo pantalla` Vocalia se apoya en el **servicio de accesibilidad de Android** para leer y manejar la pantalla de cualquier app o web, sin tocarla. Al entrar te dice dónde estás: aplicación, web, pantalla y si tienes la sesión iniciada.

> Requiere activar **Vocalia** en *Ajustes → Accesibilidad → Servicios instalados*. Si pones "accesibilidad" en la barra de búsqueda de ajustes, te sale.

**Reglas de voz en modo pantalla**

- Cada comando debe empezar por **"vocalia"**, salvo que tengas activada la **escucha continua** (en ese caso no hace falta).
- Si dices solo **"vocalia"** suena un pitido y durante unos segundos se acepta un comando sin repetir la wake word.
- Al salir del modo se vuelve a escucha continua si estaba activa, o a reposo si no.
- El modo se cierra solo tras un rato de inactividad.

**Saber dónde estás y qué hay**

```
"vocalia, dónde estoy"          ← app, web, pantalla, sesión, cuántos botones/campos/textos,
                                   algunos textos y qué decir a continuación
"vocalia, he iniciado sesión"   ← ¿tengo la cuenta iniciada en esta web?
"vocalia, lee los textos"       ← solo textos
"vocalia, lee los botones"      ← solo lo que se puede pulsar
"vocalia, lee los campos"       ← dónde se puede escribir
```

**Navegar de uno en uno**

```
"vocalia, siguiente"            ← pasa al elemento siguiente y lo anuncia
"vocalia, anterior"             ← vuelve al anterior
"vocalia, lee"                  ← vuelve a leer el elemento actual
"vocalia, detalle"              ← descripción ampliada: acciones, posición, coordenadas, nº, id
"vocalia, pulsa"                ← pulsa el elemento actual
"vocalia, atrás"                ← botón atrás de Android
"vocalia, arrastra arriba/abajo/izquierda/derecha"
"vocalia, portapapeles pegar"
"vocalia, salir del modo pantalla"
```

La posición se **conserva** entre comandos: la lista solo se actualiza al entrar en el modo o cuando la pantalla cambia (pulsar, atrás, desplazar).

**Leer toda la interfaz, por páginas y con números**

```
"vocalia, lee todo"
   → "La pantalla tiene 40 elementos, 12 con información. Botón 1: menú.
      Campo de texto 2: url, contiene html punto duckduckgo punto com.
      Elementos del 3 al 20, sin información. Elemento 21: … Quedan 9. Para seguir, di: más elementos."
"vocalia, más elementos"        ← siguiente página (8 en 8)
```

- Lee **por páginas** para que sea fácil enterarse de una interfaz.
- Los elementos **sin nombre ni texto se agrupan** en la lectura ("elementos del 3 al 20, sin información"). Siguen teniendo su número para controlarlos.
- Los textos **no se recortan** (por ejemplo, los mensajes de Telegram se leen enteros).

Los números quedan guardados hasta el siguiente `lee todo`. Después puedes actuar sobre cualquier elemento sin ir uno por uno:

```
"vocalia, elemento cuatro, pulsa"      (o "pulsa elemento cuatro")
"vocalia, elemento dos, lee"
"vocalia, elemento tres, arrastra derecha"
"vocalia, elemento cinco, detalle"
"vocalia, elemento siete"              ← lo selecciona y lo lee
```

**Buscar un elemento por su texto**

```
"vocalia, abre telegram"
"vocalia, modo pantalla"
"vocalia, busca elemento con texto ajustes"
   → "botón 4: ajustes"
"vocalia, pulsa"                       (o "elemento cuatro, pulsa")
```

> Las palabras de los elementos en pantalla se añaden **en caliente** a la gramática de Vosk del modo pantalla, así que se pueden reconocer sin haberlas dicho antes. Si hay varias coincidencias, Vocalia dice cuántas hay y sus números. La búsqueda vuelve a numerar la pantalla actual (si no ha cambiado, los números son los mismos).

**Pulsar "como un ratón"**

`pulsa` funciona aunque el elemento no sea pulsable para Android: si no hay click normal, Vocalia hace un **toque real por coordenadas** en el centro del elemento (`dispatchGesture`). Esto requiere `canPerformGestures="true"` en la configuración del servicio de accesibilidad.

**Qué dice de cada elemento**

Vocalia construye el nombre como un lector de pantalla: `contentDescription`/texto → etiqueta asociada → tooltip → texto de los elementos hijos → pista del campo → identificador de la vista. Añade el tipo (botón, botón con icono, campo de texto, interruptor, casilla, lista…) y el estado (marcado, seleccionado, desactivado). Las URLs se leen de forma hablada ("punto", "barra") y las **contraseñas nunca se leen**, solo su longitud. Si un elemento no tiene etiqueta, lo dice ("sin nombre" / "sin etiqueta, nombre deducido del identificador").

### 🎯 Cursores: botones que Vocalia ya conoce

Muchas apps y webs tienen elementos que Android no expone bien (zonas sin etiqueta, botones dibujados, listas). Para esos casos, Vocalia lee **ficheros de configuración** que describen qué hay en cada pantalla, con su nombre, descripción y posición. A cada elemento descrito se le llama **cursor**.

```
"vocalia, lee cursores"          ← lista numerada de los cursores de ESTA pantalla
"vocalia, pulsa primer chat"     ← lo pulsa directamente
"vocalia, cursor primer chat"    ← te coloca en él sin pulsar (luego: pulsa, lee, detalle, arrastra)
"vocalia, qué pantalla es"       ← pantalla actual (detectada o forzada)
"vocalia, pantalla ajustes"      ← fuerza una pantalla
"vocalia, pantalla automática"   ← vuelve a detectarla sola
"vocalia, recarga configuración" ← relee los ficheros
"vocalia, estado de la configuración"
```

**Pantallas.** Cada app o web tiene varias pantallas con sus propios cursores: en un chat abierto no existe "sexto chat", y en los ajustes de Telegram sí existe "idioma". Vocalia detecta la pantalla mirando lo que hay visible (reglas `detect`); si no la reconoce, usa la pantalla marcada como `default`, y siempre se puede forzar por voz.

**Scroll automático.** Si pides algo que está en una lista pero no a la vista (por ejemplo, *"pulsa configuración"* en el menú de Chrome), Vocalia **desplaza la lista hasta encontrarlo**. Un cursor también puede ser un desplazamiento (`action: "scroll"`).

### 🌐 Webs y sesión iniciada

Vocalia lee el **dominio** de la barra de direcciones del navegador (Chrome, Firefox, Brave, Edge, Samsung, DuckDuckGo, Opera) y aplica la configuración de esa web, sumada a la del navegador.

- Webs incluidas: **Google, X, Marca, Instagram, YouTube, Facebook, Amazon, ChatGPT, WhatsApp Web, DeepSeek, Wikipedia, GitHub, Gmail, Gist, foro.elhacker.net y Google Traductor**.
- Apps incluidas: **Chrome, Telegram, WhatsApp, YouTube, Gmail, Ajustes, Spotify, Maps, Instagram, Facebook y X**.
- Como las webs no tienen versión pública, cada configuración lleva **la fecha en que se escribió**.
- **Sesión iniciada**: para webs que no funcionan sin cuenta (Gmail, ChatGPT, DeepSeek, X…), Vocalia avisa de si hay sesión al entrar en el modo pantalla y con *"he iniciado sesión"*.

> ⚠️ Los selectores y coordenadas de las apps y webs incluidas son **estimaciones**: hay que calibrarlas en cada móvil con `lee todo`, `elemento N, detalle` y el log. Ver [vocalia-configs-LEEME.md](app/src/main/assets/configs/vocalia-configs-LEEME.md).

### 🗂️ Ficheros de configuración

- Se incluyen en la app (`assets/configs/`) y se **extraen a una carpeta pública editable** en el primer arranque:
  `/sdcard/Android/data/io.github.stringmanolo.vocalia.debug/files/configs/`
- Se **recargan solos** al guardar un cambio (o con `recarga configuración`). No hace falta recompilar.
- **Un fichero por dispositivo o general**: la sección `default` vale para todos; las de fabricante o modelo la **parchean** (solo se pone lo que cambia).
- Si editas un fichero y Vocalia se actualiza, **no lo pisa**: deja la nueva versión como `<fichero>.new`.
- Se eligen por **versión de la app**: se usa la configuración más alta que no supere la instalada.
- Formato **tolerante**: admite comentarios y campos de nota (`_nota`). Cada fichero lleva su guía dentro (`_guia`).

### Pausas y reanudación del TTS

> Esta funcionalidad es inestable. No depender de ella. Requiere retoques.

Mientras un TTS se está reproduciendo (lista de apps, apertura de app, hora, etc.), puedes decir **`vocalia pausa`** (o `para`, `detente`, `stop`, `silencio`…) para pausarlo. Después, **`vocalia continúa`** para reanudarlo **desde la frase donde se quedó**.

- **Anti-eco:** sin wake word, las palabras de pausa (`para`, `pausa`, `stop`…) se ignoran durante la reproducción. Así el propio TTS no se auto-pausa por el eco del altavoz. Con una lectura larga (por ejemplo `lee todo`), para detenerla di **`vocalia para`**.
- **Chunking por frase:** el TTS trocea el texto en frases (`. ! ?` seguidos de espacio o fin de texto), uniendo frases cortas hasta ~240 caracteres. Las pausas caen entre frases completas, no a mitad de palabra. El texto final sin punto también se lee.
- **Base para el futuro:** cada frase es una unidad reanudable. Repetir la última frase, saltar N frases, copiar al portapapeles, timestamps por frase… todo eso será trivial de añadir.

### La ayuda es un TTS bloqueante

A diferencia del resto de TTS, mientras se reproduce la **ayuda**, Vosk se detiene por completo y **no procesa ningún resultado de voz**. Esto evita que el eco del propio altavoz (que contiene frases como *"di vocalia, pausa"*) dispare pausas o comandos accidentales.

Cuando el TTS de ayuda termina, Vosk vuelve a escuchar con normalidad.

### Doble motor de voz

| Motor | Calidad | Uso |
| :--- | :--- | :--- |
| **espeak-ng** | Robótica, ligera | Default. Rápido, bajo consumo, siempre disponible. |
| **Piper (Sherpa-onnx)** | Neuronal, natural | Se carga al abrir la app o por voz. Suena como un asistente moderno. |

Cambio en caliente:

```
Tú: "vocalia, usa voz inteligente"
Vocalia: "cargando voz inteligente" → (1-4 s) → "voz inteligente activada"

Tú: "vocalia, usa voz por defecto"
Vocalia: "voz por defecto activada"
```

### STT offline

Vosk con modelo español (`vosk-model-small-es-0.42`). Reconoce comandos y wake word sin conexión. Usa gramática personalizada (solo reconoce las palabras que le programé), ampliada en caliente con las palabras de la pantalla y los cursores de la app o web activa.

### Resistencia

- Arranca al encender el teléfono (`BOOT_COMPLETED`) y al abrir la app.
- `ForegroundService` con notificación persistente.
- Watchdog que lo relanza si muere.
- Exención de optimización de batería (evita que EMUI lo mate).
- Logs internos en `/sdcard/Android/data/io.github.stringmanolo.vocalia.debug/files/INTERNAL_DEBUG_FILE`.

### 🔮 Preparado para el modo dictado

Todavía no se puede escribir por voz, pero ya están las **interfaces** (`dictation/DictationApi.kt`) y los **perfiles de gramática** (`vocalia-grammar-profiles.json`) para la siguiente fase: un "vim por voz" con modos de insertar, seleccionar, guardar y descartar, y gramáticas distintas según lo que se vaya a escribir (contraseñas deletreadas, correo, código en JavaScript/Python/C/C++, conversación, jerga técnica). Ver [ROADMAP.md](ROADMAP.md).

---

## 🔧 Instalación

### Requisitos

- Android 7.0 (API 24) o superior.
- ARM64 (arm64-v8a). Cubre el 99% de móviles desde 2016.
- 200 MB de almacenamiento (modelo STT + modelos TTS + APK).
- 500 MB de RAM disponible.

### Pasos

1. **Descarga la APK** desde la última [release](https://github.com/StringManolo/Vocalia/releases).
2. **Instálala** (acepta "instalar desde fuentes desconocidas").
3. **Abre Vocalia**:
   - Concede permiso de notificaciones (Android 13+).
   - Concede permiso de micrófono (el servicio arranca solo al concederlo).
   - Acepta la exención de batería.
4. **Espera** (carga Vosk y espeak-ng). Vocalia te saludará y te explicará cómo usarla.
5. **Di "vocalia"**. Debe oírse un pitido y estar atento al siguiente comando.
6. **(Para el modo pantalla)** Activa Vocalia en *Ajustes → Accesibilidad → Servicios instalados*. Pide ayuda a otra persona una sola vez si hace falta.

El botón "Iniciar servicio" sigue disponible; el servicio solo se detiene al pulsar el de detener.

### En Huawei / EMUI (importante)

EMUI es muy agresivo matando apps en segundo plano. Para que Vocalia funcione de verdad:

1. **Ajustes → Batería → Inicio de apps → Vocalia**:
   - Desactiva "Administrar automáticamente".
   - Activa "Autoinicio", "Ejecución en segundo plano", "Ejecución al bloquear pantalla".
2. **Ajustes → Batería → Optimización de batería**:
   - Busca Vocalia, selecciona "No permitir" (no optimizar).

Sin esto, EMUI matará el servicio a los minutos. Con esto, es estable.

> En la mayoría de dispositivos esto no es necesario porque los permisos de ignorar optimizaciones de batería evitan que EMUI cierre la app.

---

## 🧪 Cómo probarlo

Con el servicio iniciado, di en voz alta y clara:

```
"vocalia"
   → pitido corto

"vocalia, qué hora es"
   → "son las 14 y 30"

"vocalia, abre Telegram"
   → "abriendo Telegram" (y Telegram se abre)

"vocalia, ayuda"
   → te dice los temas. Después: "vocalia, ayuda lectura"

"vocalia, usa voz inteligente"
   → "cargando voz inteligente" ... "voz inteligente activada" (voz Piper)

"vocalia, lista de apps"    ← mientras lee:
"vocalia, para"              → el TTS se detiene

"vocalia, modo pantalla"
   → "Modo pantalla activado. Estás en Telegram. Pantalla: lista de chats."
"vocalia, dónde estoy"
   → resumen de la pantalla y qué decir a continuación
"vocalia, lee todo"
   → "La pantalla tiene N elementos, M con información. Botón 1: …"
"vocalia, más elementos"
   → siguiente página
"vocalia, lee cursores"
   → "Hay 14 cursores en esta pantalla. 1, menú, … 2, buscar, …"
"vocalia, pulsa primer chat"
   → "pulsado"
"vocalia, elemento dos, pulsa"
   → "pulsado"
"vocalia, busca elemento con texto ajustes"
   → "botón 4: ajustes"

"vocalia, abre la web de wikipedia"
   → "Abriendo wikipedia.org"
"vocalia, he iniciado sesión"
   → "Tienes la sesión iniciada en Gmail." / "No has iniciado sesión en Gmail. …"

"vocalia, salir del modo pantalla"
   → "Has salido del modo pantalla."

"vocalia, descansa"
   → "descanso" (vuelve a modo wake word)
```

Si algo no responde:

1. Abre **"Ver logs"** en la app.
2. Copia el contenido del `INTERNAL_DEBUG_FILE`.
3. Abre un issue con ese log y qué esperabas que pasara.

Para calibrar una app o web: `qué pantalla es` y las líneas `pantalla -> …` del log muestran los textos, descripciones e ids visibles.

---

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                    VocaliaService                       │
│     (ForegroundService; arranca al abrir la app)        │
└─────────────────────────┬───────────────────────────────┘
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
    ┌────────────────┐         ┌────────────────┐
    │  VoskEngine    │         │  TtsManager    │
    │   (STT)        │         │  (chunks por   │
    │                │         │   frase)       │
    │  Modelo small  │◀───────▶│  ┌──────────┐  │
    │  español       │         │  │espeak-ng │  │
    │  Gramática     │         │  └──────────┘  │
    │  dinámica      │         │  ┌──────────┐  │
    │  (wake word +  │         │  │  Sherpa  │  │
    │  apps + cmds + │         │  │  (Piper) │  │
    │  elementos UI  │         │  └──────────┘  │
    │  + cursores)   │         └────────────────┘
    └────────┬───────┘
             │
             ▼
    ┌──────────────────────────────────────────┐
    │            VocaliaCore                   │
    │  Máquina de estados:                     │
    │  IDLE → LISTENING → EXECUTING → …        │
    │  … → TTS_SPEAKING ⇄ TTS_PAUSED           │
    │  … ⇄ CONTINUOUS ⇄ UI_NAVIGATION          │
    │  (isBlockingHelp ignora Vosk en Ayuda)   │
    │  welcome(): bienvenida al abrir la app   │
    │                                          │
    │  CommandParser → Sleep | Help(Topic) |   │
    │  ListApps | OpenApp | CloseApp |         │
    │  PauseTts | ResumeTts | Ui* …            │
    │  HelpTopics → ayuda por temas            │
    │  AppLauncher → abre/cierra apps          │
    └───────────────────┬──────────────────────┘
                        │
                        ▼
    ┌──────────────────────────────────────────┐
    │   VocaliaAccessibilityService            │
    │   Árbol de nodos en orden de lectura,    │
    │   nombres accesibles, numeración,        │
    │   lectura por páginas, búsqueda,         │
    │   gestos (tap / swipe / scroll), web     │
    │   abierta, sesión, pantalla, cursores    │
    └───────────────────┬──────────────────────┘
                        │
                        ▼
    ┌──────────────────────────────────────────┐
    │   AppConfigManager                       │
    │   JSON por dispositivo / app / web,      │
    │   pantallas, selectores, scroll, login,  │
    │   recarga en caliente                    │
    └──────────────────────────────────────────┘

    (interfaces listas, sin implementar)  ┌───────────────────────┐
                                          │ dictation/DictationApi│
                                          │ perfiles de gramática │
                                          └───────────────────────┘
```

### Stack

| Componente | Tecnología | Licencia |
| :--- | :--- | :--- |
| STT | [Vosk](https://alphacephei.com/vosk/) + modelo español | Apache 2.0 |
| TTS default | [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPLv3 |
| TTS neuronal | [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Piper | Apache 2.0 |
| Control de apps | Android `AccessibilityService` + `dispatchGesture` | Android |
| Configuración | JSON (`org.json` de Android, parser tolerante) | Android |
| Audio | `AudioTrack` nativo (STREAM, PCM 16-bit) | Android |
| Build | GitHub Actions + Gradle | — |

**Sin Google Play Services**. Sin dependencias de la nube. Sin telemetría.

---

## 🛣️ Roadmap

### ✅ Completado

- **Fase 0**: Servicio persistente, arranque al boot, watchdog, exención de batería.
- **Fase 1.1**: TTS con espeak-ng en español.
- **Fase 1.2**: Wake word, máquina de estados, comandos básicos, TTS dual (espeak + Piper).
- **Fase 1.3**: Intents para abrir/cerrar apps con alias, listar apps, pausa/reanudación del TTS, TTS bloqueante de ayuda, chunking por frase.
- **Fase 2.0**: Modo usar con `AccessibilityService`: navegación, lectura con nombres accesibles, elementos numerados, búsqueda por texto, gramática en caliente, ayuda propia.

### 🚧 En validación (próxima versión v0.6.0)

- **Fase 2.1**: Modo pantalla para todos: configuración por app y web (pantallas, cursores, selectores, scroll automático, sesión), clicks por coordenadas, lectura por páginas con agrupación, preguntas "dónde estoy", ayuda por temas, bienvenida y arranque automático, guía de uso, interfaces del dictado.

### 🔮 Planificado

- **Fase 2.2**: Etiquetar elementos por voz, avisos de cambios de pantalla, ventanas y notificaciones, más apps y webs calibradas.
- **Fase 1.4**: Modo dictado, un "vim por voz" (insertar, visual, navegar, guardar, descartar) con gramáticas por tipo de texto.
- **Fase 3**: Integración con Termux + terminal navegable por voz (programar en vim, compilar, git).
- **Fase 4**: Sherpa en proceso separado para aislamiento total de crashes nativos.
- **Fase 5**: Instalador "todo en uno" para que un tercero pueda instalar Vocalia en el móvil de una persona ciega sin conocimientos técnicos.

Detalle completo en [ROADMAP.md](ROADMAP.md).

---

## 🛠️ Desarrollo

### Compilar

Vocalia usa GitHub Actions para compilar. **No necesitas Android Studio**.

1. Haz fork del repo.
2. Edita lo que quieras.
3. Push a `main`.
4. Descarga el APK desde la pestaña **Actions**.

Si quieres compilar en local:

```bash
git clone https://github.com/StringManolo/Vocalia.git
cd Vocalia
# Necesitas: JDK 17, Android SDK 34, NDK 27
./gradlew assembleDebug
```

### Estructura

```
GUIA_DE_USO.md                           # Guía para el usuario final
README.md  ROADMAP.md
app/src/main/
├── java/io/github/stringmanolo/vocalia/
│   ├── MainActivity.kt                  # UI mínima (start/stop/logs); arranca el servicio al abrir
│   ├── VocaliaApp.kt                    # Application, init de CrashHandler
│   ├── VocaliaService.kt                # ForegroundService principal (gramática, bienvenida)
│   ├── VocaliaAccessibilityService.kt   # Lectura y control de la pantalla (modo pantalla), gestos
│   ├── SpeechText.kt                    # Texto apto para TTS (URLs, recortes) y normalización
│   ├── VoskEngine.kt                    # STT (Voz a texto)
│   ├── core/
│   │   ├── VocaliaCore.kt               # Máquina de estados
│   │   ├── CommandParser.kt             # Intérprete de comandos
│   │   ├── HelpTopics.kt                # Ayuda por temas y mensaje de bienvenida
│   │   └── AppLauncher.kt               # Abre/cierra apps, alias, transliteración
│   ├── config/
│   │   └── AppConfigManager.kt          # JSON por dispositivo/app/web, pantallas, selectores, login
│   ├── dictation/
│   │   └── DictationApi.kt              # Interfaces del futuro modo dictado (sin implementar)
│   ├── tts/
│   │   ├── TtsEngine.kt                 # Interfaz común
│   │   ├── TtsManager.kt                # Gestor de motores + chunking por frase
│   │   ├── EspeakNgEngine.kt            # Motor espeak-ng
│   │   └── SherpaOnnxEngine.kt          # Motor Sherpa/Piper
│   └── util/
│       ├── InternalDebug.kt             # Logger a disco
│       ├── VocaliaLogger.kt             # Logger en memoria
│       └── CrashHandler.kt              # UncaughtExceptionHandler
├── assets/
│   ├── configs/
│   │   ├── vocalia-default-apps-config.json   # Apps y webs para TODOS los dispositivos
│   │   ├── vocalia-huawei-apps-config.json    # Ejemplo de parche por fabricante/modelo
│   │   ├── vocalia-grammar-profiles.json      # Perfiles de gramática (futuro dictado)
│   │   └── vocalia-configs-LEEME.md           # Guía del formato de los JSON
│   ├── vosk-model-small-es-0.42/        # Modelo STT (~50 MB)
│   └── tts/
│       └── vits-piper-es_ES-miro-high/  # Modelo TTS neuronal (~80 MB)
├── jniLibs/
│   └── arm64-v8a/libttsespeak.so        # espeak-ng nativo
└── res/
    ├── raw/espeakdata.zip               # Datos de espeak-ng (~15 MB)
    └── xml/accessibility_service_config.xml  # Config del servicio (flagReportViewIds, canPerformGestures)
```

### Añadir un comando nuevo

1. Edita `CommandParser.kt`:
   ```kotlin
   matches(text, "tu-comando") -> CommandResult.Response("respuesta")
   ```
2. Añade la variante a la gramática en `VocaliaService.kt` (`buildGrammar()`). Si es un comando del modo pantalla, añádela a `GRAMMAR_UI_NAVIGATION` en `VocaliaCore.kt`:
   ```kotlin
   "tu-comando", "variante1", "variante2",
   ```
3. (Opcional) Si el comando requiere una acción nueva en `VocaliaCore.kt`, añade un `case` al `when (parsed)` y el correspondiente `CommandResult`.
4. (Opcional) Si es algo que el usuario debe saber, añádelo al tema correspondiente de `HelpTopics.kt` y a `GUIA_DE_USO.md`.
5. Commit, push, espera al APK.

### Añadir una app o una web

No hace falta tocar código: edita (o crea) un fichero `vocalia-*-apps-config.json`. Mira [`vocalia-configs-LEEME.md`](app/src/main/assets/configs/vocalia-configs-LEEME.md) y el `_guia` del JSON por defecto. En el móvil puedes editarlo en la carpeta `configs/` y se recarga solo.

---

## 📄 Licencia

**GPLv3 para uso personal y open source.**

- Puedes usar, modificar y redistribuir Vocalia libremente **para uso personal** o **en proyectos open source**.
- **Uso comercial requiere licencia aparte**. Contacta con el autor (`StringManolo`) para negociar los términos.

Esto significa:

- ✅ Usar Vocalia en tu móvil.
- ✅ Modificarla para tus necesidades.
- ✅ Compartir tus mejoras con la comunidad (bajo GPLv3).
- ❌ Venderla.
- ❌ Usarla en un producto comercial sin permiso.

Ver [LICENSE](LICENSE) para el texto completo.

---

## 🙏 Créditos

- **[Vosk](https://alphacephei.com/vosk/)** — STT offline.
- **[espeak-ng](https://github.com/espeak-ng/espeak-ng)** — TTS ligero.
- **[Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** — TTS neuronal offline.
- **[Piper](https://github.com/rhasspy/piper)** — Modelos de voz neuronal.
- **[Termux](https://termux.com/)** — El entorno donde nació este proyecto.

---

## 💬 Soporte

- **Bug / idea**: [abre un issue](https://github.com/StringManolo/Vocalia/issues).
- **Uso comercial**: contacta con `StringManolo` por correo.
- **Antes de abrir un issue**: incluye el `INTERNAL_DEBUG_FILE` de tu instalación. Sin él, no se puede diagnosticar.

---

**Hecho con 🎙️ por y para gente que quiere usar el móvil sin tocarlo.**