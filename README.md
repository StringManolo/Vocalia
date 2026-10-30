# Vocalia

**Tu Android, solo con la voz.** Asistente de voz offline, sin Google, sin manos, sin mirar la pantalla. Diseñado para conductores, personas ciegas, personas sin movilidad en las manos, y cualquiera que quiera controlar su teléfono sin tocarlo.

---

## 📥 Descargar

**Última versión estable:** [`v0.4.0-vocalia`](https://github.com/StringManolo/Vocalia/releases)

APK directo: [`debug-apk.zip`](https://github.com/StringManolo/Vocalia/releases/download/V0.0.0-debug/debug-apk.zip)
                                                                                        > ⚠️ La APK está firmada con clave de debug. Android mostrará un aviso al instalar. Es normal: la app es sideload, no viene de Play Store. Quizás necesites desactivar playprotect para permitir la instalación.

---

## 🎯 ¿Qué es Vocalia?

Un asistente de voz ligero que se ejecuta **íntegramente en tu dispositivo**, sin enviar audio a la nube, sin depender de servicios de Google Play, y sin que tengas que tocar la pantalla ni mirarla.

Piensa en Siri o Google Assistant, pero:

- **100% offline** para las tareas esenciales (voz a texto, texto a voz, comandos).
- **Sin Google Play Services**. Funciona en Huawei y en cualquier Android sin GMS.
- **Código abierto** (GPLv3 para uso personal).
- **Extensible** por módulos: TTS, STT, comandos, cada pieza se puede cambiar.

### ¿Para quién es?

- 🚛 **Conductores** de camión, taxi, reparto, que quieren programar, gestionar negocios o consultar cosas mientras conducen sin distraerse.
- 👁️ **Personas ciegas o con baja visión** que necesitan un control por voz total de su Android.
- 🖐️ **Personas sin movilidad en las manos** que no pueden tocar la pantalla.
- 🍳 **Usuarios normales** que quieren usar el móvil con las manos ocupadas (cocinando, en el taller, etc.).

---

## ✨ Funcionalidades actuales (v0.4.0)

### Wake word

Di **"vocalia"** y el sistema te responde. No hay que pulsar ningún botón.

Vosk transcribe el wake word de varias formas según el ruido y la pronunciación. Vocalia acepta todas estas variantes:

```
vocalia, vocalía, boca lia, boca lía, bocalia
```

Solo se reconocen **al inicio** del texto (para no confundir "abre vocalia" con una wake word).

### Comandos por voz

```
# Básicos
"vocalia, hola"
"vocalia, cómo estás"
"vocalia, qué hora es"
"vocalia, qué día es"
"vocalia, ayuda"

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

# Descansar
"vocalia, descansa"
```

También funciona **wake word + comando en un solo turno**:

```
"vocalia, qué hora es"   ← detecta wake word + comando a la vez
```

### Pausas y reanudación del TTS

Mientras un TTS se está reproduciendo (lista de apps, apertura de app, hora, etc.), puedes decir **`vocalia pausa`** (o `detente`, `stop`, `silencio`…) para pausarlo. Después, **`vocalia continúa`** para reanudarlo **desde la frase donde se quedó**.

- **Anti-eco:** sin wake word, las palabras de pausa (`para`, `pausa`, `stop`…) se ignoran durante la reproducción. Así el propio TTS no se auto-pausa por el eco del altavoz.
- **Chunking por frase:** el TTS trocea el texto en frases (`. ! ?`), uniendo frases cortas hasta ~240 caracteres. Las pausas caen entre frases completas, no a mitad de palabra.
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

Vosk con modelo español (`vosk-model-small-es-0.42`). Reconoce comandos y wake word sin conexión.

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

### En Huawei / EMUI (importante)

EMUI es muy agresivo matando apps en segundo plano. Para que Vocalia funcione de verdad:

1. **Ajustes → Batería → Inicio de apps → Vocalia**:
   - Desactiva "Administrar automáticamente".
   - Activa "Autoinicio", "Ejecución en segundo plano", "Ejecución al bloquear pantalla".
2. **Ajustes → Batería → Optimización de batería**:
   - Busca Vocalia, selecciona "No permitir" (no optimizar).

Sin esto, EMUI matará el servicio a los minutos. Con esto, es estable.

---

## 🧪 Cómo probarlo

Con el servicio iniciado, di en voz alta y clara:

```
"vocalia"
   → pitido corto

"qué hora es"
   → "son las 14 y 30"

"vocalia, qué día es"
   → "hoy es jueves 29 de octubre"

"vocalia, abre Telegram"
   → "abriendo Telegram" (y Telegram se abre)

"vocalia, abre ajustes"
   → "abriendo ajustes"

"vocalia, lista de apps"
   → lee los nombres de las apps instaladas, uno tras otro

"vocalia, usa voz inteligente"
   → "cargando voz inteligente" ... "voz inteligente activada" (voz Piper)

"vocalia, qué hora es"
   → "son las 14 y 31" (con voz Piper, mucho más natural)

"vocalia, usa voz por defecto"
   → "voz por defecto activada" (vuelve a espeak)

"vocalia, lista de apps"    ← mientras lee:
"vocalia, pausa"             → el TTS se detiene
"vocalia, continúa"          → el TTS sigue desde la frase donde se quedó

"vocalia, ayuda"
   → lee la ayuda entera. Mientras dura, Vosk no escucha nada.

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
    │   (STT)        │         │                │
    │                │         │  ┌──────────┐  │
    │  Modelo small  │◀───────▶│  │espeak-ng │  │
    │  español       │         │  └──────────┘  │
    │  Gramática     │         │  ┌──────────┐  │
    │  dinámica      │         │  │  Sherpa  │  │
    │  (wake word +  │         │  │  (Piper) │  │
    │  apps + cmds)  │         │  └──────────┘  │
    └────────┬───────┘         └────────────────┘
             │
             ▼
    ┌──────────────────────────────────────────┐
    │            VocaliaCore                   │
    │  Máquina de estados:                     │
    │  IDLE → LISTENING → EXECUTING → …        │
    │  … → TTS_SPEAKING ⇄ TTS_PAUSED           │
    │  (isBlockingHelp ignora Vosk en Ayuda)   │
    │                                          │
    │  CommandParser → Sleep | Help |          │
    │  ListApps | OpenApp | CloseApp |         │
    │  PauseTts | ResumeTts | …                │
    │                                          │
    │  AppLauncher → abre/cierra apps,         │
    │  alias, transliteración cirílico→latino  │
    └──────────────────────────────────────────┘
```

### Stack

| Componente | Tecnología | Licencia |
| :--- | :--- | :--- |
| STT | [Vosk](https://alphacephei.com/vosk/) + modelo español | Apache 2.0 |
| TTS default | [espeak-ng](https://github.com/espeak-ng/espeak-ng) | GPLv3 |
| TTS neuronal | [Sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) + Piper | Apache 2.0 |
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

### 🚧 En desarrollo

- **Fase 1.4**: Dictado libre con buffer navegable ("repite la última frase", "salta 5 frases", "copia al portapapeles", timestamps por frase). La base (chunking por frase en `TtsManager`) ya está lista.

### 🔮 Planificado

- **Fase 2**: `AccessibilityService` para leer la pantalla y controlar cualquier app.
- **Fase 3**: Integración con Termux + terminal navegable por voz (programar en vim, compilar, git).
- **Fase 4**: Sherpa en proceso separado para aislamiento total de crashes nativos.
- **Fase 5**: Instalador "todo en uno" para que un tercero pueda instalar Vocalia en el móvil de una persona ciega sin conocimientos técnicos.

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
│   ├── MainActivity.kt              # UI mínima (start/stop/logs)
│   ├── VocaliaApp.kt                # Application, init de CrashHandler
│   ├── VocaliaService.kt            # ForegroundService principal
│   ├── VoskEngine.kt                # STT
│   ├── core/
│   │   ├── VocaliaCore.kt           # Máquina de estados
│   │   ├── CommandParser.kt         # Intérprete de comandos
│   │   └── AppLauncher.kt           # Abre/cierra apps, alias, transliteración
│   ├── tts/
│   │   ├── TtsEngine.kt             # Interfaz común
│   │   ├── TtsManager.kt            # Gestor de motores + chunking por frase
│   │   ├── EspeakNgEngine.kt        # Motor espeak-ng
│   │   └── SherpaOnnxEngine.kt      # Motor Sherpa/Piper
│   └── util/
│       ├── InternalDebug.kt         # Logger a disco
│       ├── VocaliaLogger.kt         # Logger en memoria
│       └── CrashHandler.kt          # UncaughtExceptionHandler
├── assets/
│   ├── vosk-model-small-es-0.42/    # Modelo STT (~50 MB)
│   └── tts/
│       └── vits-piper-es_ES-miro-high/  # Modelo TTS neuronal (~80 MB)
├── jniLibs/
│   └── arm64-v8a/libttsespeak.so    # espeak-ng nativo
└── res/
    └── raw/espeakdata.zip           # Datos de espeak-ng (~15 MB)
```

### Añadir un comando nuevo

1. Edita `CommandParser.kt`:
   ```kotlin
   matches(text, "tu-comando") -> CommandResult.Response("respuesta")
   ```
2. Añade la variante a la gramática en `VocaliaService.kt` (`buildGrammar()`):
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
