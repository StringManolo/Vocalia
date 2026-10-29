# Roadmap de Vocalia

**Documento vivo.** Última actualización: Fase 1.2 completada (v0.3.0-sherpa).

Este documento describe la visión, las fases, las decisiones técnicas y los casos límite que guían el desarrollo de Vocalia. Se actualiza al cerrar cada fase.

---

## 🎯 Visión

Construir un asistente de voz **totalmente offline y sin dependencias de Google** que permita controlar un Android **sin tocar la pantalla y sin mirarla**, para que cualquier persona pueda:

- **Programar y gestionar negocios** mientras conduce largas horas (caso del autor).
- **Usar el móvil con normalidad** siendo ciega o con baja visión.
- **Usar el móvil sin manos** (discapacidad motriz, conducción, cocina, taller).
- **Extenderlo** sin miedo: cada componente (STT, TTS, comandos) es modular y reemplazable.

### Principios de diseño

1. **Offline primero.** Todo lo esencial funciona sin red. La red solo para actualizaciones, feedback y plugins opcionales.
2. **Sin Google Play Services.** Debe funcionar en Huawei, dispositivos sin GMS, y versiones antiguas de Android.
3. **Modular.** Cada motor (STT, TTS, comandos) es una pieza independiente que se puede sustituir.
4. **Robusto.** Si un componente falla, el resto sigue vivo. Watchdog, fallbacks, recuperación automática.
5. **Instalable por un tercero sin conocimientos técnicos.** El objetivo final es un APK que cualquiera pueda instalar en el móvil de una persona ciega en 5 minutos.
6. **Open source (GPLv3 para uso personal).** Uso comercial con licencia aparte negociada caso por caso.

---

## 📅 Fases

### ✅ Fase 0 — Andamiaje

**Estado:** completada.

- [x] Repositorio `Vocalia` con template `KotlinApkTemplate`.
- [x] Compilación vía GitHub Actions (sin Android Studio).
- [x] `ForegroundService` persistente con notificación.
- [x] Arranque automático al encender (`BOOT_COMPLETED`).
- [x] Watchdog con `AlarmManager` que relanza el servicio si muere.
- [x] Exención de optimización de batería.
- [x] Logs internos a disco (`InternalDebug`), sin depender de logcat.
- [x] `CrashHandler` para excepciones Java + volcado de logcat al arrancar.

**Notas técnicas:**
- `START_NOT_STICKY` para evitar bucles de crashes.
- Huawei/EMUI requiere configuración manual de "Inicio de apps" (documentado en README).
- El `force-stop` de Android es un mecanismo de seguridad: no se puede esquivar. No es un bug.

---

### ✅ Fase 1.1 — TTS offline con espeak-ng

**Estado:** completada.

- [x] Integración de `espeak-ng` (vía wrapper `com.reecedunn.espeak`).
- [x] Compilación del `.so` y `espeakdata.zip` desde GitHub Actions.
- [x] Voz española femenina (`roa/es`).
- [x] `AudioTrack` en `MODE_STREAM` con PCM 16-bit.
- [x] Ajuste de velocidad (`Rate.setValue(wpm)`).
- [x] Reproducción tras `synthesize()` (no dentro del callback nativo).

**Notas técnicas críticas:**
- EMUI de Huawei **no conecta el `AudioTrack` al mezclador si se crea dentro del callback nativo** de `synthesize()`. Hay que acumular PCM y reproducir después.
- `CONTENT_TYPE_MUSIC` funciona donde `CONTENT_TYPE_SPEECH` falla en Huawei.
- `setPreferredDevice` rompe el `AudioTrack` en el P40 Lite. No usar.
- `bufSize = pcm.size` funciona mejor que `bufSize = minBuf * 4` en EMUI.

---

### ✅ Fase 1.2 — Wake word + comandos + TTS dual

**Estado:** completada (v0.3.0-sherpa).

- [x] STT con Vosk modelo `small-es-0.42` (50 MB).
- [x] Wake word "asistente" (descartada "vocalia": no está en el vocabulario del modelo).
- [x] Máquina de estados `IDLE → LISTENING → EXECUTING → IDLE`.
- [x] Wake + comando en un solo turno ("asistente, qué hora es").
- [x] Timeout de 5 s en LISTENING, safety timeout de 30–60 s en EXECUTING.
- [x] Pitido corto (100 ms) al entrar en LISTENING (feedback no verbal).
- [x] Comandos: hora, fecha, hola, cómo estás, descansa.
- [x] TTS dual: espeak-ng (default) + Sherpa/Piper (voz natural).
- [x] Cambio de motor en caliente por voz ("usa voz inteligente" / "usa voz por defecto").
- [x] Carga de Sherpa bajo demanda (no al arrancar) para ahorrar RAM.
- [x] Copia de `espeak-ng-data` a `filesDir` para Sherpa (espeak-ng nativo necesita rutas reales, no assets).
- [x] Logcat dump al arrancar para capturar crashes nativos.

**Notas técnicas críticas:**
- Sherpa-onnx **2.0.0+ eliminó espeak-ng**. Los modelos Piper necesitan el AAR **1.13.4** o anterior.
- Sherpa 1.13.4 hace `SIGSEGV` si `espeak-ng-data` no está en disco. La copia a `filesDir` es obligatoria.
- `pickFirsts` en `build.gradle` con `libonnxruntime.so` puede causar más problemas de los que resuelve.
- Vosk no trae `libonnxruntime.so`; no hay conflicto con Sherpa en el APK.

---

### 🚧 Fase 1.3 — Intents para abrir apps

**Estado:** siguiente a empezar.

Objetivo: abrir cualquier app instalada por voz.

- [ ] `AppLauncher` con lista curada de apps comunes (`org.telegram.messenger`, `com.whatsapp`, `com.android.chrome`, `com.google.android.youtube`, `com.android.camera`, `com.android.settings`, `com.android.dialer`, `com.android.contacts`, etc.).
- [ ] Búsqueda difusa por nombre visible (`PackageManager.queryIntentActivities`).
- [ ] `QUERY_ALL_PACKAGES` en el manifest.
- [ ] `Intent.launchIntentForPackage` con fallback a `Intent.CATEGORY_LAUNCHER`.
- [ ] Comandos: "abre Telegram", "abre el navegador", "abre la cámara", "abre ajustes", "abre contactos", "abre el reloj".
- [ ] Gramática de Vosk actualizada con las frases de cada app + variantes.
- [ ] Fallback: "no encuentro esa app" si no hay match.

**Notas:**
- No requiere `AccessibilityService`. `startActivity` es suficiente para abrir apps.
- El `AndroidManifest` necesita `QUERY_ALL_PACKAGES` (Android 11+) para listar las apps instaladas.
- Futuro: "cierra [app]" requiere `ActivityManager.killBackgroundProcesses` o AccessibilityService (ver Fase 2).

---

### 🚧 Fase 1.4 — Dictado libre con buffer navegable

**Estado:** planificada.

Objetivo: dictar texto libre y poder navegarlo, editarlo y usarlo.

- [ ] Modo dictado (`Recognizer` sin gramática).
- [ ] Buffer de texto acumulativo.
- [ ] Comandos de transporte:
  - "repite" / "lee todo" / "borra último" / "borra todo".
  - "busca [término]" → salta a la primera ocurrencia y lee desde ahí.
  - "atrás" / "adelante" → mueve el puntero de lectura.
  - "copia al portapapeles".
  - "fin dictado" → vuelve a wake word.
- [ ] Símbolos especiales por voz: "dos puntos x" → `:x`, "punto y aparte", "tabulador", "enter".
- [ ] Modo programación: atajos de teclado por voz (Ctrl+C, Ctrl+V, Escape, flechas).
- [ ] Feedback: leer cada frase dictada (configurable on/off).

**Notas:**
- Vosk modelo small tiene precisión limitada. Para dictado serio, evaluar:
  - Modelo Vosk `large` (1.4 GB, ~500 MB RAM).
  - **Whisper.cpp en modo batch** (graba frase → transcribe, ~1-2 s por frase, 244 MB modelo small int8).
  - Sherpa-onnx ASR con modelo Zipformer (streaming, ~50-150 MB).
- La decisión se tomará con pruebas reales de precisión y latencia.
- El buffer navegable es la pieza única de este proyecto. Sin él, dictar 2000 líneas es inútil.

---

### 🚧 Fase 1.5 — Comandos de sistema

**Estado:** planificada.

- [ ] "sube volumen" / "baja volumen" / "silencia" → `AudioManager`.
- [ ] "qué batería tengo" → `BatteryManager`.
- [ ] "qué wifi estoy usando" → `WifiManager`.
- [ ] "activa/desactiva wifi" (requiere permisos especiales).
- [ ] "abre [url]" → `Intent.ACTION_VIEW`.
- [ ] "busca en Google [término]" → `Intent.ACTION_WEB_SEARCH` (o navegador directo).
- [ ] "reproduce [canción] en YouTube" → `yt-dlp` + `mpv` en Termux (Fase 3).
- [ ] "llama a [contacto]" → `Intent.ACTION_CALL` (requiere `READ_CONTACTS`).

---

### 🔮 Fase 2 — `AccessibilityService` (lectura de pantalla)

**Estado:** planificada.

Objetivo: leer cualquier app y controlarla por voz.

- [ ] `VocaliaAccessibilityService`:
  - `onAccessibilityEvent` para capturar cambios.
  - Recorrer el árbol de `AccessibilityNodeInfo`.
  - Extraer texto, `contentDescription`, roles, coordenadas.
- [ ] Lector de pantalla modular con buffer navegable:
  - "¿qué hay en pantalla?" → describe el árbol.
  - "toca [texto]" → busca nodo y ejecuta `ACTION_CLICK`.
  - "desliza arriba/abajo" → `ACTION_SCROLL_FORWARD/BACKWARD`.
  - "mueve cursor derecha/izquierda" → navegación por foco.
  - "¿qué hay bajo el cursor?" → lee el nodo con foco de accesibilidad.
- [ ] Modo "clic por exploración" para usuarios sin manos:
  - Resalta elementos interactivos uno a uno, los lee.
  - Usuario dice "clic" cuando oye el que quiere.
- [ ] Selectores específicos por app si la accesibilidad genérica falla.

**Notas:**
- Esta fase es la que permite usar **cualquier app**, no solo las que tienen API pública.
- El árbol de accesibilidad es frágil: cambia entre versiones de apps. Hay que testear.
- Alternativa/complemento: `uiautomator2` vía ADB local en Termux (menos elegante, más rápido de prototipar).

---

### 🔮 Fase 3 — Termux + terminal navegable

**Estado:** planificada.

Objetivo: programar por voz en vim, compilar, gestionar git, todo desde el móvil.

- [ ] Puente app ↔ Termux:
  - Opción A: `termux-api` (requiere Termux instalado).
  - Opción B: socket TCP/Unix entre la app y un script en Termux.
- [ ] `tmux-bridge` en Termux:
  - "lee terminal" → `tmux capture-pane -p -S -2000` → TTS con buffer navegable.
  - "busca [término] en terminal" → `tmux copy-mode` + búsqueda.
  - "escribe [texto]" → `tmux send-keys`.
- [ ] vim accesible:
  - Comando de voz "lee línea actual" → `:echo getline('.')` → TTS.
  - "lee desde aquí" → lee desde el cursor.
  - "lee función actual".
  - Atajos de edición por voz ("borra línea", "inserta modo", "guarda", "sal sin guardar").
- [ ] Modo "dictado a terminal" para insertar texto.
- [ ] YouTube audio: `yt-dlp` + `mpv --no-video`.
- [ ] SSH a servidores remotos.

**Notas:**
- Termux **no es obligatorio** para el usuario ciego sin manos. Es una capa extra para el usuario programador.
- El buffer navegable de Fase 1.4 se reutiliza aquí para leer salidas largas.

---

### 🔮 Fase 4 — Sherpa en proceso separado

**Estado:** aparcada hasta que aparezca inestabilidad.

Objetivo: aislar Sherpa en su propio proceso Android para que un `SIGSEGV` no tumbe el servicio principal.

- [ ] `SherpaService` con `android:process=":sherpa"`.
- [ ] Comunicación con `Messenger` (más simple que AIDL):
  - Mensajes: `SPEAK`, `STOP`, `SET_SPEED`.
  - Respuesta: `DONE`, `ERROR`.
- [ ] `TtsManager` delega en el `SherpaService` vía `Messenger`.
- [ ] Watchdog del proceso `:sherpa` para relanzarlo si muere.

**Notas:**
- Solo necesario si en uso real Sherpa hace `SIGSEGV` con frecuencia.
- El `SIGSEGV` en Android no lo captura ningún `try/catch`; solo se aísla en otro proceso.
- Con Sherpa 1.13.4 + copia a `filesDir`, actualmente es estable.

---

### 🔮 Fase 5 — Instalador "todo en uno"

**Estado:** planificada.

Objetivo: que un tercero sin conocimientos técnicos pueda instalar Vocalia en el móvil de una persona ciega en <5 minutos.

- [ ] APK "todo en uno" con modelos embebidos (Vosk + espeak + Sherpa).
- [ ] Guía de instalación por voz: la app lee los pasos y guía al usuario.
- [ ] Configuración inicial por voz:
  - Idioma (español/inglés).
  - Números de emergencia (grave/leve).
  - Contacto de emergencia.
  - Wake word personalizada (opcional).
  - Calibración de micrófono.
- [ ] Detección de fabricante (Huawei, Xiaomi, Samsung) y guía específica de batería.
- [ ] README y `LEGALITY.md` en el repo con enlaces a normativa por país.

---

### 🔮 Fase 6 — Emergencias y feedback

**Estado:** planificada.

- [ ] "ayuda" / "emergencia" → llama al número configurado (con confirmación obligatoria).
- [ ] "avisa a [contacto]" → SMS con ubicación GPS.
- [ ] Botón de pánico físico (volumen arriba + abajo durante 3 s).
- [ ] Feedback al repo:
  - "reporta un problema" → genera un issue pre-rellenado en GitHub con el `INTERNAL_DEBUG_FILE`.
  - Requiere backend/tokens. Aparcado hasta que haya usuarios.

---

### 🔮 Fase 7 — Release open source + licencia comercial

**Estado:** planificada.

- [ ] README pulido con capturas (para videntes).
- [ ] Guía de contribución (`CONTRIBUTING.md`).
- [ ] `LEGALITY.md` con normativa por país sobre uso de dispositivos manos libres al conducir.
- [ ] Publicación en F-Droid (si es viable sin GMS).
- [ ] Landing page con licencia comercial y formulario de contacto.

---

## 🔧 Decisiones técnicas

### Stack elegido

| Componente | Tecnología | Motivo |
| :--- | :--- | :--- |
| **STT** | Vosk + `small-es-0.42` | Ligero, offline, latencia baja, gramática cerrada. |
| **TTS default** | espeak-ng | ~30 MB RAM, siempre disponible, fallback garantizado. |
| **TTS neuronal** | Sherpa-onnx 1.13.4 + Piper `es_ES-miro-high` | Voz natural, offline, ~250 MB RAM. |
| **Audio** | `AudioTrack` nativo | Control total. `MODE_STREAM` + PCM 16-bit + `CONTENT_TYPE_MUSIC`. |
| **Compilación** | GitHub Actions | Sin PC. Edición desde Termux. |
| **Logs** | `InternalDebug` (disco) | logcat no accesible en producción. |
| **Estado** | Máquina de estados en `VocaliaCore` | Separación clara de responsabilidades. |

### Restricciones autoimpuestas

- **Sin Google Play Services**. Ni Firebase, ni `FusedLocationProvider`, ni `SafetyNet`.
- **Sin red para tareas esenciales**. La nube solo para updates, feedback, plugins.
- **Sin hardware adicional**. Micrófono del móvil, auriculares estándar.
- **Un solo APK**. No dependencias de otros APKs (Termux es opcional).
- **Sin root**. Todo lo que se pueda, sin privilegios.

### Anti-patrones descubiertos (no repetir)

- ❌ `setPreferredDevice` en `AudioTrack` → rompe el track en Huawei.
- ❌ Reproducir dentro del callback nativo de `synthesize()` → `SIGSEGV` o `head=0`.
- ❌ `CONTENT_TYPE_SPEECH` en Huawei → no suena, `head=0`.
- ❌ Sherpa 2.0.0+ con modelos Piper → no hay espeak-ng, `samples=0`.
- ❌ Sherpa 1.13.4 sin `espeak-ng-data` en `filesDir` → `SIGSEGV`.
- ❌ `START_STICKY` con crashes nativos → bucle infinito de crashes.
- ❌ `packagingOptions` en AGP 8+ → `packaging`.
- ❌ `excludes` de `libonnxruntime.so` → elimina todas las copias.
- ❌ `pickFirsts` sin verificar el orden → puede elegir la versión incorrecta.
- ❌ Gramática de wake word con palabras inventadas → Vosk no las reconoce.
- ❌ Recrear `SpeechService` en cada cambio de gramática → pierde audio.

---

## ⚠️ Casos límite y consideraciones

### Legales

- **Conducción:** la legalidad del uso de dispositivos manos libres varía por país. Vocalia **no se hace responsable** del uso que se le dé. El README y `LEGALITY.md` advierten de que el usuario debe consultar la normativa de su país.
- **Grabación de audio:** la app no envía audio a la nube. Todo se procesa offline. No hay grabaciones persistentes más allá del buffer temporal en RAM.
- **Datos personales:** el `INTERNAL_DEBUG_FILE` puede contener texto reconocido por Vosk. El usuario es responsable de borrarlo si lo comparte.

### Técnicos

- **Memoria:** Vosk small (~400 MB) + Sherpa (~250 MB) + espeak (~30 MB) + Android + app pueden acercarse a 1 GB. En un P40 Lite con 4 GB va, pero el sistema puede matar procesos en segundo plano bajo presión.
- **Batería:** Vosk con micrófono siempre activo consume. Se recomienda cargar el móvil mientras se conduce.
- **Micrófono + altavoz simultáneos:** funciona en el P40 Lite. Otros dispositivos pueden requerir `MODE_IN_COMMUNICATION` y `speakerphone`. Hay que testear por dispositivo.
- **Wake word en entornos ruidosos:** el modelo small puede fallar. Opciones: modelo Vosk large, o wake word de dos palabras ("oye asistente").
- **Idioma:** Vosk y Piper son monolingües por modelo. Cambiar de idioma requiere cargar otro modelo. Planificado para Fase 1.5+.
- **Modelo Vosk small vs large:** el small es rápido pero limitado. Para dictado serio, evaluar Whisper.cpp en batch o Sherpa ASR.

### De instalación

- **Firma de debug:** el APK no está firmado con clave de release. Android muestra aviso al instalar. Aceptable para sideload.
- **Huawei/EMUI:** requiere configuración manual de batería (documentado en README). Sin esto, el servicio muere en minutos.
- **Xiaomi/MIUI:** problema similar. Habrá que documentar los pasos por fabricante.

---

## 🤝 Cómo contribuir

### Reportar un bug

1. Abre un issue en GitHub.
2. Incluye:
   - Modelo de dispositivo y versión de Android.
   - Pasos para reproducir.
   - El `INTERNAL_DEBUG_FILE` (`/sdcard/Android/data/io.github.stringmanolo.vocalia.debug/files/INTERNAL_DEBUG_FILE`).
   - Si la app crashea, el logcat del arranque posterior (el `[LOGCAT-ANTERIOR]` del log).

### Añadir un comando

1. Edita `CommandParser.kt`:
   ```kotlin
   matches(text, "tu-comando") -> CommandResult.Response("respuesta")
   ```
2. Edita la gramática en `VoskEngine.kt`:
   ```kotlin
   "tu-comando", "variante1", "variante2",
   ```
3. Commit, push, espera al APK.
4. Prueba y abre PR.

### Añadir un motor TTS

1. Implementa la interfaz `TtsEngine`.
2. Regístralo en `TtsManager`.
3. Añade un comando de voz para activarlo.
4. Commit, push, PR.

### Traducir a otro idioma

1. Añade el modelo Vosk del idioma en `assets/`.
2. Añade el modelo Piper del idioma (si existe).
3. Traduce las gramáticas y los mensajes.
4. Añade comando "habla en [idioma]".

---

## 📊 Estado actual

| Fase | Estado | Notas |
| :--- | :--- | :--- |
| Fase 0 | ✅ | Servicio robusto, boot, watchdog |
| Fase 1.1 | ✅ | espeak-ng español |
| Fase 1.2 | ✅ | Wake word, comandos, TTS dual |
| Fase 1.3 | 🚧 | Intents (siguiente) |
| Fase 1.4 | 🚧 | Dictado libre |
| Fase 1.5 | 🚧 | Comandos de sistema |
| Fase 2 | 🔮 | AccessibilityService |
| Fase 3 | 🔮 | Termux + terminal |
| Fase 4 | 🔮 | Sherpa en proceso separado |
| Fase 5 | 🔮 | Instalador todo en uno |
| Fase 6 | 🔮 | Emergencias + feedback |
| Fase 7 | 🔮 | Release + licencia comercial |

**Leyenda:** ✅ completada · 🚧 en desarrollo · 🔮 planificada

---

## 📅 Historial de releases

| Tag | Fecha | Hito |
| :--- | :--- | :--- |
| `v0.0.0-debug` | — | Release de pruebas iniciales |

---

**Documento mantenido por `StringManolo`. Se acepta feedback vía issues y PRs.**
