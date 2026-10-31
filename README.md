# Vocalia

**Tu Android, solo con la voz.** Asistente de voz offline, sin Google, sin manos, sin mirar la pantalla. Diseñado para conductores, personas ciegas, personas sin movilidad en las manos, y cualquiera que quiera controlar su teléfono sin tocarlo.

---

## 📥 Descargar

**Última versión estable:** [`v0.5.0-vocalia`](https://github.com/StringManolo/Vocalia/releases)

APK directo: [`debug-apk.zip`](https://github.com/StringManolo/Vocalia/releases/download/v0.5.0-vocalia/debug-apk.zip)

> ⚠️ La APK está firmada con clave de debug. Android mostrará un aviso al instalar. Es normal: la app es sideload, no viene de Play Store. Quizás necesites desactivar Play Protect para permitir la instalación.

---

## 🎯 ¿Qué es Vocalia?

Un asistente de voz ligero que se ejecuta **íntegramente en tu dispositivo**, sin enviar audio a la nube, sin depender de servicios de Google Play, y sin que tengas que tocar la pantalla ni mirarla.

Piensa en Siri o Google Assistant, pero:

- **100% offline** para las tareas esenciales (voz a texto, texto a voz, comandos).
- **Sin Google Play Services**. Funciona en Huawei y en cualquier Android sin GMS.
- **Código abierto** (GPLv3 para uso personal).
- **Extensible** por módulos: TTS, STT, comandos, cada pieza se puede cambiar.
- **Controla cualquier app** mediante el modo usar (servicio de accesibilidad): navega, lee, numera y pulsa elementos solo con la voz.

### ¿Para quién es?

- 🚛 **Conductores** de camión, taxi, reparto, que quieren programar, gestionar negocios o consultar cosas mientras conducen sin distraerse.
- 👁️ **Personas ciegas o con baja visión** que necesitan un control por voz total de su Android.
- 🖐️ **Personas sin movilidad en las manos** que no pueden tocar la pantalla.
- 🍳 **Usuarios normales** que quieren usar el móvil con las manos ocupadas (cocinando, en el taller, etc.).

---

## ✨ Funcionalidades actuales (v0.5.0)

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
"vocalia, ayuda"                  ← en modo usar lee la ayuda del modo usar

# Abrir / cerrar apps
"vocalia, abre Telegram"
"vocalia, abre tarjetas"          ← alias de AnkiDroid
"vocalia, abre ajustes"
"vocalia, cierra Telegram"
"vocalia, cierra esta app"        ← trae Vocalia al frente y descarta la actual

# Listar apps
"vocalia, lista de apps"          ← lee los nombres
"vocalia, lístame las apps"       ← lee los nombres + log de alias

# Modo continuo
"vocalia, escucha continua"       ← comandos sin wake word hasta "descansa"
"vocalia, desactiva escucha continua"

# Voz
"vocalia, usa voz inteligente"
"vocalia, usa voz por defecto"

# Control del TTS (durante una lectura)
"vocalia, pausa"                  ← también "para", "detente", "stop", "silencio"…
"vocalia, continúa"               ← reanuda desde la frase donde se quedó

# Modo usar (control de cualquier app) — ver sección siguiente
"vocalia, modo usar"
"vocalia, modo usar ayuda"

# Descansar
"vocalia, descansa"               ← Sale de los modos USAR y ESCUCHA CONTINUA
```

También funciona **wake word + comando en un solo turno**:

```
"vocalia, qué hora es"   ← detecta wake word + comando a la vez
```

### 🖐️ Modo usar (control de cualquier app)

Con `vocalia, modo usar` Vocalia se apoya en el **servicio de accesibilidad de Android** para leer y manejar la pantalla de cualquier app, sin tocarla.

> Requiere activar **Vocalia** en *Ajustes → Accesibilidad → Servicios instalados*. Sibpones accesibilidad en la barra de busqueda de ajustes, te sale.

**Reglas de voz en modo usar**

- Cada comando debe empezar por **"vocalia"**, salvo que tengas activada la **escucha continua** (en ese caso no hace falta).
- Si dices solo **"vocalia"** suena un pitido y durante unos segundos se acepta un comando sin repetir la wake word.
- Al salir del modo usar se vuelve a escucha continua si estaba activa, o a reposo si no.
- El modo usar se cierra solo tras un rato de inactividad.

**Navegar de uno en uno**

```
"vocalia, siguiente"            ← pasa al elemento siguiente y lo anuncia
"vocalia, anterior"             ← vuelve al anterior
"vocalia, lee"                  ← vuelve a leer el elemento actual
"vocalia, detalle"              ← descripción ampliada: acciones, posición, nº, id
"vocalia, pulsa"                ← pulsa el elemento actual
"vocalia, atrás"                ← botón atrás de Android
"vocalia, arrastra arriba/abajo/izquierda/derecha"
"vocalia, portapapeles pegar"
"vocalia, sal del modo usar"
```

La posición se **conserva** entre comandos: La lista solo se actualiza al entrar en el modo o cuando la pantalla cambia (pulsar, atrás, desplazar).

**Leer toda la interfaz con números**

```
"vocalia, lee todo"
   → "La interfaz tiene 7 elementos. Botón 1: menú. Campo de texto 2: url,
      contiene html punto duckduckgo punto com. Botón 3: ir. …"
```

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
"vocalia, modo usar"
"vocalia, busca elemento con texto ajustes"
   → "botón 4: ajustes"
"vocalia, pulsa"                       (o "elemento cuatro, pulsa")
```

> Las palabras de los elementos en pantalla se añaden **en caliente** a la gramática de Vosk del modo usar, así que se pueden reconocer sin haberlas dicho antes. Si hay varias coincidencias, Vocalia dice cuántas hay y sus números. La búsqueda vuelve a numerar la pantalla actual (si no ha cambiado, los números son los mismos).

**Qué dice de cada elemento**

Vocalia construye el nombre como un lector de pantalla: `contentDescription`/texto → etiqueta asociada → tooltip → texto de los elementos hijos → pista del campo → identificador de la vista. Añade el tipo (botón, botón con icono, campo de texto, interruptor, casilla, lista…) y el estado (marcado, seleccionado, desactivado). Las URLs se leen de forma hablada ("punto", "barra"), los textos muy largos se recortan y las **contraseñas nunca se leen**, solo su longitud. Si un elemento no tiene etiqueta, lo dice ("sin nombre" / "sin etiqueta, nombre deducido del identificador").

**Ayuda del modo usar**

```
"vocalia, ayuda"                ← dentro del modo usar
"vocalia, modo usar ayuda"      ← desde cualquier sitio
```

### Pausas y reanudación del TTS

> Esta funcionalidad es inestable. No depender de ella. Requiere retoques.

Mientras un TTS se está reproduciendo (lista de apps, apertura de app, hora, etc.), puedes decir **`vocalia pausa`** (o `detente`, `stop`, `silencio`…) para pausarlo. Después, **`vocalia continúa`** para reanudarlo **desde la frase donde se quedó**.

- **Anti-eco:** sin wake word, las palabras de pausa (`para`, `pausa`, `stop`…) se ignoran durante la reproducción. Así el propio TTS no se auto-pausa por el eco del altavoz. Con una lectura larga (por ejemplo `lee todo`), para detenerla di **`vocalia para`**.
- **Chunking por frase:** el TTS trocea el texto en frases (`. ! ?` seguidos de espacio o fin de texto), uniendo frases cortas hasta ~240 caracteres. Las pausas caen entre frases completas, no a mitad de palabra. El texto final sin punto también se lee (antes se perdía).
- **Base para el futuro:** cada frase es una unidad reanudable. Repetir la última frase, saltar N frases, copiar al portapapeles, timestamps por frase… todo eso será trivial de añadir.

### `vocalia ayuda` es un TTS bloqueante

A diferencia del resto de TTS, mientras se reproduce la **ayuda**, Vosk se detiene por completo y **no procesa ningún resultado de voz**. Esto evita que el eco del propio altavoz (que contiene frases como *"di vocalia, pausa"* o *"di vocalia, ayuda"*) dispare pausas o comandos accidentales.

Cuando el TTS de ayuda termina, Vosk vuelve a escuchar con normalidad.

### Doble motor de voz

| Motor | Calidad | Uso |
| :--- | :--- | :--- |
| **espeak-ng** | Robótica, ligera | Default. Rápido, bajo consumo, siempre disponible. |
| **Piper (Sherpa-onnx)** | Neuronal, natural | Se activa por voz. Suena como un asistente moderno. |

Cambio en caliente:

```
Tú: "vocalia, usa voz inteligente"
Vocalia: "cargando voz inteligente" → (1-4 s) → "voz inteligente activada"

Tú: "vocalia, usa voz por defecto"
Vocalia: "voz por defecto activada"
```

### STT offline

Vosk con modelo español (`vosk-model-small-es-0.42`). Reconoce comandos y wake word sin conexión. Usa gramática personalizada (solo reconoce las palabras que le programé)

### Resistencia

- Arranca al encender el teléfono (`BOOT_COMPLETED`).
- `ForegroundService` con notificación persistente.
- Watchdog que lo relanza si muere.
- Exención de optimización de batería (evita que EMUI lo mate).
- Logs internos en `/sdcard/Android/data/io.github.stringmanolo.vocalia.debug/files/INTERNAL_DEBUG_FILE`.

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
   - Concede permiso de micrófono.
   - Acepta la exención de batería.
4. **Pulsa "Iniciar servicio"**.
5. **Espera 5 segundos** (carga Vosk y espeak-ng).
6. **Di "vocalia"**. Debe oírse un pitido y estar atento al siguiente comando.
7. **(Para el modo usar)** Activa Vocalia en *Ajustes → Accesibilidad → Servicios instalados*.

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

"qué hora es"
   → "son las 14 y 30"

"vocalia, abre Telegram"
   → "abriendo Telegram" (y Telegram se abre)

"vocalia, usa voz inteligente"
   → "cargando voz inteligente" ... "voz inteligente activada" (voz Piper)

"vocalia, lista de apps"    ← mientras lee:
"vocalia, pausa"             → el TTS se detiene
"vocalia, continúa"          → el TTS sigue desde la frase donde se quedó

"vocalia, ayuda"
   → lee la ayuda entera. Mientras dura, Vosk no escucha nada.

"vocalia, modo usar"
   → "modo usar activado"
"vocalia, lee todo"
   → "La interfaz tiene N elementos. Botón 1: …"
"vocalia, elemento dos, pulsa"
   → "pulsado"
"vocalia, busca elemento con texto ajustes"
   → "botón 4: ajustes"
"vocalia, modo usar ayuda"
   → lee todos los comandos del modo usar
"vocalia, sal del modo usar"
   → "modo usar desactivado"

"vocalia, descansa"
   → "descanso" (vuelve a modo wake word)
```

Si algo no responde:

1. Abre **"Ver logs"** en la app.
2. Copia el contenido del `INTERNAL_DEBUG_FILE`.
3. Abre un issue con ese log y qué esperabas que pasara.

---

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                    VocaliaService                       │
│              (ForegroundService persistente)            │
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
    │  elementos UI) │         │  └──────────┘  │
    └────────┬───────┘         └────────────────┘
             │
             ▼
    ┌──────────────────────────────────────────┐
    │            VocaliaCore                   │
    │  Máquina de estados:                     │
    │  IDLE → LISTENING → EXECUTING → …        │
    │  … → TTS_SPEAKING ⇄ TTS_PAUSED           │
    │  … ⇄ CONTINUOUS ⇄ UI_NAVIGATION          │
    │  (isBlockingHelp ignora Vosk en Ayuda)   │
    │                                          │
    │  CommandParser → Sleep | Help |          │
    │  ListApps | OpenApp | CloseApp |         │
    │  PauseTts | ResumeTts | Ui* …            │
    │                                          │
    │  AppLauncher → abre/cierra apps,         │
    │  alias, transliteración cirílico→latino  │
    └───────────────────┬──────────────────────┘
                        │
                        ▼
    ┌──────────────────────────────────────────┐
    │   VocaliaAccessibilityService            │
    │   Árbol de nodos en orden de lectura,    │
    │   nombres accesibles, numeración,        │
    │   búsqueda, pulsar / desplazar / atrás   │
    └──────────────────────────────────────────┘
```

### Stack

| Componente | Tecnología | Licencia |
| :--- | :--- | :--- |
| STT | [Vosk](https://alphacephei.com/vosk/) + modelo español | Apache 2.0 |
| TTS default | [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPLv3 |
| TTS neuronal | [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Piper | Apache 2.0 |
| Control de apps | Android `AccessibilityService` | Android |
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

### 🚧 En desarrollo

- **Fase 2.1**: Descripciones por app para elementos sin etiqueta, etiquetado por voz, pulsar por coordenadas.
- **Fase 1.4**: Dictado libre con buffer navegable ("repite la última frase", "salta 5 frases", "copia al portapapeles", timestamps por frase). La base (chunking por frase en `TtsManager`) ya está lista.

### 🔮 Planificado

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
app/src/main/
├── java/io/github/stringmanolo/vocalia/
│   ├── MainActivity.kt                  # UI mínima (start/stop/logs)
│   ├── VocaliaApp.kt                    # Application, init de CrashHandler
│   ├── VocaliaService.kt                # ForegroundService principal
│   ├── VocaliaAccessibilityService.kt   # Lectura y control de la pantalla (modo usar)
│   ├── SpeechText.kt                    # Texto apto para TTS (URLs, recortes) y normalización
│   ├── VoskEngine.kt                    # STT (Voz a texto)
│   ├── core/
│   │   ├── VocaliaCore.kt               # Máquina de estados
│   │   ├── CommandParser.kt             # Intérprete de comandos
│   │   └── AppLauncher.kt               # Abre/cierra apps, alias, transliteración
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
│   ├── vosk-model-small-es-0.42/        # Modelo STT (~50 MB)
│   └── tts/
│       └── vits-piper-es_ES-miro-high/  # Modelo TTS neuronal (~80 MB)
├── jniLibs/
│   └── arm64-v8a/libttsespeak.so        # espeak-ng nativo
└── res/
    ├── raw/espeakdata.zip               # Datos de espeak-ng (~15 MB)
    └── xml/accessibility_service_config.xml  # Config del servicio de accesibilidad
```

### Añadir un comando nuevo

1. Edita `CommandParser.kt`:
   ```kotlin
   matches(text, "tu-comando") -> CommandResult.Response("respuesta")
   ```
2. Añade la variante a la gramática en `VocaliaService.kt` (`buildGrammar()`). Si es un comando del modo usar, añádela a `GRAMMAR_UI_NAVIGATION` en `VocaliaCore.kt`:
   ```kotlin
   "tu-comando", "variante1", "variante2",
   ```
3. (Opcional) Si el comando requiere una acción nueva en `VocaliaCore.kt`, añade un `case` al `when (parsed)` y el correspondiente `CommandResult`.
4. Commit, push, espera al APK.

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
