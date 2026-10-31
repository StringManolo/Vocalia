# Roadmap de Vocalia

**Documento vivo.** Última actualización: Fase 2.0 completada (v0.5.0-vocalia).

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
7. **El feedback por voz debe bastar.** Cada elemento, estado y acción tiene que poder entenderse solo con el audio.

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
- [x] Wake word: se probó primero "asistente" y se pensó descartar "vocalia" por no estar en el vocabulario del modelo. **Corregido en Fase 1.3**: Vosk transcribe "vocalia" como `boca lia` / `bocalia`, y el wake word se acepta como variantes transcripcionales. Ver Fase 1.3.
- [x] Máquina de estados `IDLE → LISTENING → EXECUTING → IDLE`.
- [x] Wake + comando en un solo turno ("vocalia, qué hora es").
- [x] Timeout de 5 s en LISTENING, safety timeout de 60 s en EXECUTING.
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

### ✅ Fase 1.3 — Intents para abrir/cerrar apps + control del TTS

**Estado:** completada (v0.4.0-vocalia).

Objetivo: abrir/cerrar cualquier app instalada por voz, listar apps, y poder pausar/reanudar cualquier lectura del TTS.

- [x] `AppLauncher` con lista curada de ~90 aliases → paquetes (`telegram`, `whatsapp`, `chrome`, `youtube`, `ajustes`, `contactos`, `tarjetas` → AnkiDroid, `sed` → SetEdit, `osu` → osu!droid, etc.).
- [x] Búsqueda difusa por nombre visible (`PackageManager.queryIntentActivities`) normalizando tildes y transliterando cirílico → latino.
- [x] `QUERY_ALL_PACKAGES` en el manifest.
- [x] `getLaunchIntentForPackage` con fallback a `Intent.CATEGORY_LAUNCHER` y flags `NEW_TASK | REORDER_TO_FRONT | BROUGHT_TO_FRONT | SINGLE_TOP`.
- [x] Comandos:
  - `abre <app>` / `abrir <app>`.
  - `cierra <app>` / `cerrar <app>` / `mata <app>` / `matar <app>`.
  - `cierra esta app` → trae Vocalia al frente y descarta la app en primer plano.
  - `lista de apps` → lee los nombres instalados (transliterados).
  - `lístame las apps` → como el anterior + log de alias por app.
- [x] **Wake word cambiada a `vocalia`** con variantes transcripcionales que produce Vosk: `vocalia`, `vocalía`, `boca lia`, `boca lía`, `bocalia`. Solo se matchea al **inicio** del texto.
- [x] Gramática dinámica de Vosk generada en `VocaliaService.buildGrammar()` con: wake words + comandos fijos + todas las apps instaladas + todos los aliases, en variantes con y sin tildes, con prefijos `abre`/`cierra`/etc.
- [x] **Chunking por frase en `TtsManager`**: el texto se trocea en frases, uniendo frases cortas hasta ~240 caracteres, partiendo frases largas por `,` `;` `:` o espacio.
- [x] **Pausa / reanudación del TTS**:
  - `vocalia pausa` / `detente` / `stop` / `silencio`… → `ttsManager.pause()` + estado `TTS_PAUSED`.
  - `vocalia continúa` → `ttsManager.resumePaused()`, reanuda desde el chunk/frase en curso.
  - `pendingChunks` + `pendingStartIndex` guardados en `TtsManager` para reanudar incluso si el usuario ejecutó otro comando por medio y el estado ya no es `TTS_PAUSED`.
- [x] **Anti-eco** en `TTS_SPEAKING`: sin wake word, las palabras de pausa (`para`, `pausa`, `stop`…) se ignoran para evitar que el propio TTS se auto-pause.
- [x] **`vocalia ayuda` es un TTS bloqueante**: `isBlockingHelp = true` → Vosk se detiene por completo (`stopListening`) y `handleResult` descarta cualquier resultado hasta que el TTS de ayuda termina. Es el **único** TTS que no acepta pausas ni comandos. El resto siguen siendo navegables.
- [x] `stop()` síncrono con `join(2000)` para evitar bloqueos tras pausas.
- [x] Transliteración cirílico → latino para que el TTS lea labels como `Настройки` como `Nastroyki`.
- [x] Fallback: "no encuentro la app X" si no hay match.

**Notas técnicas críticas:**
- **Chunking por frase** es la base de toda la navegación futura del TTS. Cada chunk es una unidad reanudable. `TtsManager` expone `getChunks()`, `getCurrentChunkIndex()`, `getCurrentChunk()`, `pendingChunks`, `pendingStartIndex` — sin usar aún, pero listos para Fase 1.4.
- **La pausa re-lee el chunk actual desde su inicio** (una frase completa). No se reanuda en mitad de una palabra ni de una frase. Es correcto con chunks pequeños, y es la única forma realista porque Sherpa no expone offset PCM reproducible en medio de la generación.
- **El eco del altavoz es el enemigo real**: durante `vocalia ayuda`, el propio TTS pronunciaba frases como *"di vocalia, pausa"* o *"di vocalia, ayuda"*, y Vosk las transcribía como `boca lia silencio` / `boca lia pausa`, disparando pausas accidentales. La solución robusta es **bloquear Vosk por completo** durante la ayuda (`isBlockingHelp`), en lugar de intentar filtrar el eco por palabra.
- El **anti-eco genérico** (ignorar palabras de pausa sin wake word) cubre el resto de TTS, donde sí queremos aceptar `vocalia pausa` cuando el usuario lo dice de verdad.
- `AppLauncher` cachea la lista de apps instaladas (`cachedInstalledApps`) para no golpear `PackageManager` en cada comando.
- La gramática dinámica puede crecer mucho con muchas apps instaladas. Vosk la acepta, pero conviene vigilar el tamaño si el usuario tiene cientos de apps.

---

### ✅ Fase 2.0 — Modo usar (`AccessibilityService`)

**Estado:** completada (v0.5.0-vocalia).

Objetivo: leer y controlar cualquier app por voz, sin tocar la pantalla.

**Servicio y estados**
- [x] `VocaliaAccessibilityService`: recorre el árbol de `AccessibilityNodeInfo` de la ventana activa y mantiene la lista de elementos (interactivos o con texto, máx. 120).
- [x] Nuevo estado `UI_NAVIGATION` en `VocaliaCore` con gramática reducida y timeout por inactividad.
- [x] `vocalia, modo usar` / `vocalia, sal del modo usar`. Al salir se vuelve a escucha continua si estaba activa, o a `IDLE`.
- [x] **Wake word obligatoria en modo usar**, salvo en escucha continua (`continuousEnabled`). Si se dice solo "vocalia", pitido y ventana corta para el comando siguiente.

**Navegación y acciones**
- [x] `siguiente` / `anterior` (circular), `lee`, `detalle`, `pulsa`, `atrás`, `arrastra arriba/abajo/izquierda/derecha`, `portapapeles pegar`.
- [x] **La posición del elemento actual se conserva** entre comandos. La lista solo se refresca al entrar en el modo y tras acciones que pueden cambiar la pantalla (pulsar, atrás, desplazar). Si el nodo actual queda obsoleto, se reconstruye la lista conservando el índice.
- [x] Recorrido del árbol en profundidad (preorden) para seguir el **orden de lectura visual**.
- [x] Los nodos de solo texto bajo un contenedor pulsable se omiten (su texto forma parte del nombre del contenedor).

**Feedback por voz (nombres accesibles)**
- [x] Nombre del elemento como un lector de pantalla: `contentDescription`/texto → `labeledBy` → tooltip → texto de descendientes → pista del campo → identificador de la vista (avisando de que es deducido).
- [x] Tipo (botón, botón con icono, campo de texto, interruptor, casilla, lista, imagen…) y estado (marcado, seleccionado, desactivado, `stateDescription`).
- [x] Campos de texto: nombre = etiqueta/pista; contenido aparte ("contiene …" / "vacío"). **Las contraseñas nunca se leen**, solo su longitud.
- [x] `detalle`: acciones posibles, posición en pantalla, "elemento N de M", identificador.
- [x] `SpeechText`: URLs habladas ("punto", "barra"), saltos de línea → comas, recorte de textos largos.

**Elementos numerados y búsqueda**
- [x] `lee todo`: numera la interfaz y la lee ("La interfaz tiene X elementos. Botón 1: …"). Los números persisten hasta el siguiente `lee todo`.
- [x] Comandos por número: `elemento N, pulsa | lee | detalle | arrastra <dirección>` (y orden inverso: `pulsa elemento N`). Números en palabras o cifras, hasta 120.
- [x] `busca elemento con texto X`: selecciona la primera coincidencia y dice "botón N: nombre"; si hay varias, dice cuántas y sus números.
- [x] **Gramática en caliente**: las palabras de los nombres de los elementos en pantalla (hasta 150) se añaden a la gramática de Vosk del modo usar, para poder reconocerlas sin haberlas dicho antes.

**Ayuda contextual**
- [x] `vocalia ayuda` dentro del modo usar lee la ayuda del modo usar; `vocalia, modo usar ayuda` desde cualquier estado.

**Corrección en el TTS (encontrada en pruebas)**
- [x] `TtsManager.splitIntoChunks` descartaba el texto posterior a la última puntuación (`[^.!?]+[.!?]+`), cortando las lecturas largas. Ahora el resto final cuenta como frase y un punto solo parte la frase si va seguido de espacio o fin de texto.

**Notas técnicas críticas:**
- **Reiniciar la lista en cada vuelta a `UI_NAVIGATION` reseteaba `currentIndex` a 0**: "siguiente" siempre iba al elemento 1. La lista debe refrescarse solo cuando la pantalla ha podido cambiar.
- **Wake word en modo usar:** aceptar cualquier comando sin "vocalia" hace que el eco o las conversaciones cercanas disparen acciones. Solo la escucha continua, activada explícitamente, lo permite.
- **BFS mezcla niveles del árbol** y rompe el orden de lectura; el preorden (DFS) lo respeta.
- **Numeración vs. refresco:** hay dos listas. La de navegación (`interactiveNodes`) se refresca con la pantalla; la numerada (`numbered`) es una instantánea que solo cambia con `lee todo` o una búsqueda. Si un nodo numerado ya no es válido (`node.refresh()` falla), se pide renumerar en vez de actuar sobre un elemento equivocado.
- `viewIdResourceName` solo está disponible con `flagReportViewIds` en `accessibility_service_config.xml`.
- Las APIs `hintText`, `tooltipText`, `paneTitle` y `stateDescription` requieren API 26, 28, 28 y 30: se protegen con `Build.VERSION.SDK_INT` (el mínimo es API 24).
- Vosk solo reconoce palabras presentes en el vocabulario del modelo; los nombres raros o de marcas pueden no poder buscarse por voz.
- `isVisibleToUser` y los contenedores pulsables sin texto propio varían mucho entre apps: el árbol de accesibilidad es frágil y hay que probarlo en cada app.

---

### 🚧 Fase 2.1 — Modo usar: elementos difíciles y por app

**Estado:** siguiente a empezar.

Objetivo: que ningún elemento quede sin describir ni sea inalcanzable.

- [ ] **Ficheros de descripción por app** (por nombre de paquete): asocian el identificador o el texto de una vista a una descripción corta y otra larga, para apps con iconos sin etiqueta. Se piden por voz ("describe elemento 4" → descripción larga).
- [ ] **Etiquetar por voz**: "etiqueta este elemento como …" guarda la etiqueta en el fichero de la app.
- [ ] Pulsar por coordenadas / `dispatchGesture` para elementos que no exponen `ACTION_CLICK`.
- [ ] Escribir por voz en el campo seleccionado (`ACTION_SET_TEXT`) y atajos de edición.
- [ ] Avisar por voz de los cambios de pantalla importantes (diálogos, cambios de ventana).
- [ ] Cambiar entre ventanas / notificaciones / barra de estado.
- [ ] Renumeración automática opcional al detectar cambio de pantalla.

---

### 🚧 Fase 1.4 — Dictado libre con buffer navegable

**Estado:** planificada. La base del chunking por frase ya está lista en `TtsManager`.

Objetivo: dictar texto libre y poder navegarlo, editarlo y usarlo. Además, extender la navegación del TTS actual con comandos de transporte.

- [ ] Navegación del TTS (aprovechando el chunking por frase ya existente):
  - "repite la última frase" → re-lee `chunks[currentChunkIndex - 1]`.
  - "repite las últimas 5 frases".
  - "repite el último mensaje" → re-lee desde el principio de la lectura actual.
  - "salta a la siguiente frase" / "salta 5 frases hacia delante".
  - "copia la última frase al portapapeles".
  - "lee desde el principio" / "vuelve al principio".
  - Timestamps por frase (estimados o reales) para "repite los últimos 5 minutos".
- [ ] Modo dictado (`Recognizer` sin gramática).
- [ ] Buffer de texto acumulativo.
- [ ] Comandos de transporte sobre el buffer dictado:
  - "repite" / "lee todo" / "borra último" / "borra todo".
  - "busca [término]" → salta a la primera ocurrencia y lee desde ahí.
  - "atrás" / "adelante" → mueve el puntero de lectura.
  - "copia al portapapeles".
  - "fin dictado" → vuelve a wake word.
- [ ] Símbolos especiales por voz: "dos puntos x" → `:x`, "punto y aparte", "tabulador", "enter".
- [ ] Modo programación: atajos de teclado por voz (Ctrl+C, Ctrl+V, Escape, flechas).
- [ ] Feedback: leer cada frase dictada (configurable on/off).

**Notas:**
- El chunking por frase es deliberadamente la pieza reutilizable aquí: `TtsManager.getChunks()` + `getCurrentChunkIndex()` dan todo lo necesario para implementar repetición y salto de frases sin tocar la lógica de reproducción.
- Cuidado con los comandos que ya existen en el modo usar ("lee todo", "busca elemento…"): el dictado deberá usar otro espacio de nombres o un estado propio.
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
- El modo usar (Fase 2) ya permite leer y pulsar elementos de la propia app Termux; esta fase añade un canal más fiable para terminal.

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
- [ ] Activación guiada del servicio de accesibilidad (requisito del modo usar).
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
| **Chunking TTS** | Por frase (~240 chars) | Pausas naturales + base para navegación futura. |
| **Control de apps** | `AccessibilityService` | Único método sin root ni ADB que funciona en cualquier app. |
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
- ❌ Gramática de wake word con palabras inventadas → Vosk no las reconoce (corregido: `vocalia` sí se reconoce vía variantes `boca lia`/`bocalia`).
- ❌ Recrear `SpeechService` en cada cambio de gramática → pierde audio.
- ❌ **Confiar en el anti-eco por palabra durante un TTS que pronuncia esas mismas palabras** (`vocalia ayuda` dice *"di vocalia, pausa"*). Vosk transcribe el eco como `boca lia silencio` y se auto-pausa. Solución: TTS bloqueante (`isBlockingHelp`) que detiene Vosk por completo mientras dura.
- ❌ **Chunks de 5000 chars para pausas reanudables**: pausar a mitad deja el chunk entero pendiente y no hay offset PCM reproducible. Solución: chunks por frase (~240 chars).
- ❌ **`speak()` rechazado por `speaking = true` tras un `pause()`**: el hilo de chunk seguía vivo y bloqueaba cualquier reanudación. Solución: `stop()` síncrono con `join(2000)` + `pendingChunks` en `TtsManager`.
- ❌ **Buscar wake word con `indexOfFirst` en todo el texto**: confundía `abre vocalia` con wake word. Solución: `startsWith`.
- ❌ **`ResumeTts` ignorado si el estado ya no era `TTS_PAUSED`** (porque el usuario ejecutó otro comando por medio). Solución: `ttsManager.hasPausedSpeech()` en `handleCommand` para reanudar desde `IDLE` si hay buffer pendiente.
- ❌ **Trocear frases con `[^.!?]+[.!?]+`**: descarta todo lo que viene después de la última puntuación y parte las URLs por sus puntos. Solución: cortar solo en `. ! ?` seguidos de espacio o fin de texto, y conservar el resto final.
- ❌ **Refrescar la lista de nodos cada vez que se vuelve a `UI_NAVIGATION`**: resetea el índice a 0 y "siguiente" nunca avanza. Solución: refrescar solo al entrar o tras acciones que cambian la pantalla.
- ❌ **Aceptar comandos sin wake word en modo usar**: cualquier ruido o conversación ejecuta acciones. Solución: exigirla salvo en escucha continua.
- ❌ **Recorrer el árbol de accesibilidad en BFS**: mezcla niveles y desordena la lectura. Solución: preorden (DFS).
- ❌ **Leer solo "botón" para un elemento**: sin nombre no sirve para un usuario ciego. Solución: nombre accesible con varias fuentes, descendientes y, como último recurso, el identificador de la vista.
- ❌ **Leer el contenido de campos de contraseña**: nunca. Solo se dice su longitud.
- ❌ **Renumerar elementos en cada refresco**: los números dejarían de corresponder a lo que oyó el usuario. Solución: instantánea numerada separada, que solo cambia con `lee todo` o una búsqueda.

---

## ⚠️ Casos límite y consideraciones

### Legales

- **Conducción:** la legalidad del uso de dispositivos manos libres varía por país. Vocalia **no se hace responsable** del uso que se le dé. El README y `LEGALITY.md` advierten de que el usuario debe consultar la normativa de su país.
- **Grabación de audio:** la app no envía audio a la nube. Todo se procesa offline. No hay grabaciones persistentes más allá del buffer temporal en RAM.
- **Datos personales:** el `INTERNAL_DEBUG_FILE` puede contener texto reconocido por Vosk y descripciones de elementos de pantalla (nombres, URLs, contenido de campos de texto). El usuario es responsable de borrarlo si lo comparte.
- **Accesibilidad:** el servicio de accesibilidad da acceso al contenido de la pantalla. Vocalia lo usa solo en local, no envía nada fuera del dispositivo y no lee contraseñas.

### Técnicos

- **Memoria:** Vosk small (~400 MB) + Sherpa (~250 MB) + espeak (~30 MB) + Android + app pueden acercarse a 1 GB. En un P40 Lite con 4 GB va, pero el sistema puede matar procesos en segundo plano bajo presión.
- **Batería:** Vosk con micrófono siempre activo consume. Se recomienda cargar el móvil mientras se conduce.
- **Micrófono + altavoz simultáneos:** funciona en el P40 Lite. Otros dispositivos pueden requerir `MODE_IN_COMMUNICATION` y `speakerphone`. Hay que testear por dispositivo.
- **Eco del TTS en Vosk:** el altavoz se cuela por el micrófono y Vosk transcribe el propio TTS. Mitigaciones: (1) anti-eco por wake word en `TTS_SPEAKING`; (2) TTS bloqueante en `Ayuda`; (3) en el futuro, AEC por hardware o cancelación de eco en software si aparecen más casos. Con lecturas largas (`lee todo`), para detener hay que decir "vocalia para".
- **Wake word en entornos ruidosos:** el modelo small puede fallar. Opciones: modelo Vosk large, o wake word de dos palabras.
- **Gramática dinámica del modo usar:** crece con los nombres de los elementos (tope de 150 palabras). Las palabras fuera del vocabulario de Vosk se ignoran y no se podrán buscar por voz.
- **Árbol de accesibilidad:** cada app lo construye distinto. Apps con WebView, juegos o interfaces propias (Flutter, Unity) pueden exponer pocos o ningún nodo.
- **Idioma:** Vosk y Piper son monolingües por modelo. Cambiar de idioma requiere cargar otro modelo. Planificado para Fase 1.5+.
- **Modelo Vosk small vs large:** el small es rápido pero limitado. Para dictado serio, evaluar Whisper.cpp en batch o Sherpa ASR.

### De instalación

- **Firma de debug:** el APK no está firmado con clave de release. Android muestra aviso al instalar. Aceptable para sideload.
- **Huawei/EMUI:** requiere configuración manual de batería (documentado en README). Sin esto, el servicio muere en minutos.
- **Servicio de accesibilidad:** hay que activarlo a mano en Ajustes → Accesibilidad. Android puede desactivarlo si la app se actualiza o se fuerza su cierre.
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
2. Añade la variante a la gramática en `VocaliaService.kt` (`buildGrammar()`). Si es un comando del modo usar, añádela a `GRAMMAR_UI_NAVIGATION` en `VocaliaCore.kt`:
   ```kotlin
   "tu-comando", "variante1", "variante2",
   ```
3. (Opcional) Si el comando requiere una acción nueva en `VocaliaCore.kt`, añade un `case` al `when (parsed)` y el correspondiente `CommandResult`.
4. Commit, push, espera al APK.
5. Prueba y abre PR.

### Añadir un motor TTS

1. Implementa la interfaz `TtsEngine`.
2. Regístralo en `TtsManager`.
3. Añade un comando de voz para activarlo.
4. Commit, push, PR.

### Añadir navegación al TTS

La infraestructura ya está lista en `TtsManager`:
- `getChunks()`, `getCurrentChunkIndex()`, `getCurrentChunk()` → lectura.
- `pendingChunks`, `pendingStartIndex` → reanudación.
- `resumePaused(onStart, onComplete)` → reanudar desde el chunk pendiente.

Para añadir "repite la última frase", extiende `TtsManager` con un método `repeatChunks(n)` que re-emita `chunks.subList(currentIndex - n, currentIndex)` y conéctalo a un nuevo `CommandResult`.

### Mejorar la descripción de elementos del modo usar

La lógica está en `VocaliaAccessibilityService`:
- `accessibleName()` → de dónde sale el nombre (añade aquí nuevas fuentes).
- `roleOf()` / `stateOf()` → tipo y estado hablados.
- `describeNode()` (breve), `describeNumbered()` (listas) y `describeCurrentNodeDetailed()` (detalle).
- `SpeechText.speakable()` → cómo se pronuncian URLs y textos largos.

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
| Fase 1.3 | ✅ | Abrir/cerrar apps, listar apps, pausa/reanudación TTS, chunking por frase, ayuda bloqueante |
| Fase 2.0 | ✅ | Modo usar: navegación, nombres accesibles, elementos numerados, búsqueda, gramática en caliente, ayuda propia |
| Fase 2.1 | 🚧 | Descripciones por app, etiquetado por voz, gestos |
| Fase 1.4 | 🚧 | Dictado libre + navegación por frases |
| Fase 1.5 | 🚧 | Comandos de sistema |
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
| `v0.3.0-sherpa` | — | Wake word + comandos + TTS dual |
| `v0.4.0-vocalia` | — | Abrir/cerrar apps, listar apps, pausa/reanudación TTS, chunking por frase, ayuda bloqueante |
| `v0.5.0-vocalia` | 2026-10-31 | Modo usar con AccessibilityService: navegación con posición persistente, nombres accesibles, `lee todo` con elementos numerados, búsqueda por texto con gramática en caliente, ayuda del modo usar, wake word obligatoria salvo escucha continua, arreglo del corte del TTS |

---

**Documento mantenido por `StringManolo`. Se acepta feedback vía issues y PRs.**