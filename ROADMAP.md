# Roadmap de Vocalia

**Documento vivo.** Última actualización: Fase 2.1 implementada y en validación en dispositivo (camino a v0.6.0-vocalia). Última versión estable: v0.5.0-vocalia.

Este documento describe la visión, las fases, las decisiones técnicas y los casos límite que guían el desarrollo de Vocalia. Se actualiza al cerrar cada fase.

---

## 🎯 Visión

Construir un asistente de voz **totalmente offline y sin dependencias de Google** que permita controlar un Android **sin tocar la pantalla y sin mirarla**, para que cualquier persona pueda:

- **Programar y gestionar negocios** mientras conduce largas horas (caso del autor).
- **Usar el móvil con normalidad** siendo ciega o con baja visión.
- **Usar el móvil sin manos** (discapacidad motriz, conducción, cocina, taller).
- **Usar el móvil siendo una persona mayor**, sin conocimientos técnicos, sabiendo en todo momento qué pasa.
- **Extenderlo** sin miedo: cada componente (STT, TTS, comandos) es modular y reemplazable.

### Principios de diseño

1. **Offline primero.** Todo lo esencial funciona sin red. La red solo para actualizaciones, feedback y plugins opcionales.
2. **Sin Google Play Services.** Debe funcionar en Huawei, dispositivos sin GMS, y versiones antiguas de Android.
3. **Modular.** Cada motor (STT, TTS, comandos) es una pieza independiente que se puede sustituir.
4. **Robusto.** Si un componente falla, el resto sigue vivo. Watchdog, fallbacks, recuperación automática.
5. **Instalable por un tercero sin conocimientos técnicos.** El objetivo final es un APK que cualquiera pueda instalar en el móvil de una persona ciega en 5 minutos.
6. **Open source (GPLv3 para uso personal).** Uso comercial con licencia aparte negociada caso por caso.
7. **El feedback por voz debe bastar.** Cada elemento, estado y acción tiene que poder entenderse solo con el audio.
8. **Se explica solo.** Un usuario final (también una persona mayor) debe saber qué decir y qué pasa sin leer nada: la app guía al abrirse y la ayuda se da **por temas**, nunca toda de golpe. Las órdenes se nombran por lo que hacen ("modo pantalla", no "modo usar").
9. **Configuración como datos, no como código.** Lo específico de cada dispositivo, app o web vive en ficheros JSON editables y recargables en caliente; lo general se parchea por dispositivo sin recompilar.

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
- **La pausa/reanudación sigue siendo inestable** en uso real: no depender de ella.
- **El eco del altavoz es el enemigo real**: durante `vocalia ayuda`, el propio TTS pronunciaba frases como *"di vocalia, pausa"* o *"di vocalia, ayuda"*, y Vosk las transcribía como `boca lia silencio` / `boca lia pausa`, disparando pausas accidentales. La solución robusta es **bloquear Vosk por completo** durante la ayuda (`isBlockingHelp`), en lugar de intentar filtrar el eco por palabra.
- El **anti-eco genérico** (ignorar palabras de pausa sin wake word) cubre el resto de TTS, donde sí queremos aceptar `vocalia pausa` cuando el usuario lo dice de verdad.
- `AppLauncher` cachea la lista de apps instaladas (`cachedInstalledApps`) para no golpear `PackageManager` en cada comando.
- La gramática dinámica puede crecer mucho con muchas apps instaladas. Vosk la acepta, pero conviene vigilar el tamaño si el usuario tiene cientos de apps.

---

### ✅ Fase 2.0 — Modo usar (`AccessibilityService`)

**Estado:** completada (v0.5.0-vocalia). Desde la Fase 2.1 el modo se llama **modo pantalla**; los nombres antiguos siguen valiendo.

Objetivo: leer y controlar cualquier app por voz, sin tocar la pantalla.

**Servicio y estados**
- [x] `VocaliaAccessibilityService`: recorre el árbol de `AccessibilityNodeInfo` de la ventana activa y mantiene la lista de elementos (interactivos o con texto).
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
- [x] `detalle`: acciones posibles, posición en pantalla, coordenadas, "elemento N de M", identificador.
- [x] `SpeechText`: URLs habladas ("punto", "barra"), saltos de línea → comas.

**Elementos numerados y búsqueda**
- [x] `lee todo`: numera la interfaz y la lee. Los números persisten hasta el siguiente `lee todo`.
- [x] Comandos por número: `elemento N, pulsa | lee | detalle | arrastra <dirección>` (y orden inverso: `pulsa elemento N`). Números en palabras o cifras.
- [x] `busca elemento con texto X`: selecciona la primera coincidencia y dice "botón N: nombre"; si hay varias, dice cuántas y sus números.
- [x] **Gramática en caliente**: las palabras de los nombres de los elementos en pantalla (hasta 150) se añaden a la gramática de Vosk del modo, para poder reconocerlas sin haberlas dicho antes.

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

### 🚧 Fase 2.1 — Modo pantalla: configuración por app/web, clicks y accesibilidad para todos

**Estado:** implementada; **pendiente de validar en dispositivo** (en especial webs, selectores y detección de pantallas). Se publicará como v0.6.0-vocalia.

Objetivo: que ningún elemento quede sin describir ni sea inalcanzable, y que una persona mayor o ciega sepa en todo momento dónde está y qué puede decir.

**Clicks por coordenadas ("ratón")**
- [x] `dispatchGesture`: toque, pulsación larga, doble pulsación y deslizamiento en cualquier coordenada (`canPerformGestures="true"`).
- [x] `pulsa` funciona con elementos no pulsables: click normal sobre el nodo o su ancestro pulsable y, si no hay, toque real en el centro de sus límites.
- [x] Los gestos se envían **sin bloquear el hilo principal**; el resultado solo se registra en el log (un "cancelado" puede ser normal si la pantalla cambia al pulsar).

**Ficheros de configuración por dispositivo, app y web**
- [x] `AppConfigManager` + `assets/configs/vocalia-*-apps-config.json`, extraídos a una carpeta pública editable (`.../files/configs/`).
- [x] Sección `default` para todos los dispositivos; secciones de **fabricante** y **modelo** que la parchean (prioridad default < fabricante < modelo; `null` borra; `match` añade alias de modelo).
- [x] Selección por **versión de la app** (la configuración más alta que no supere la instalada).
- [x] **Recarga en caliente** (al cambiar el fichero o con `recarga configuración`); los ficheros editados por el usuario no se pisan al actualizar (`.new`).
- [x] Parser tolerante (comentarios, claves sin comillas) y campos `_nota` / `_guia` dentro del propio JSON. Guía completa en `vocalia-configs-LEEME.md`.

**Pantallas y cursores**
- [x] Cada app/web tiene **pantallas** con sus propios cursores (`screens`, `groups` reutilizables con `include`, cursores comunes en `ui`).
- [x] **Detección de pantalla** por reglas sobre lo que hay visible (`detect`: `all`/`any`/`none` sobre texto, descripción, id, clase, actividad, seleccionado; `priority`) y pantalla `default`.
- [x] Comandos: `lee cursores`, `cursor <nombre>`, `pulsa <nombre>`, `qué pantalla es`, `pantalla <nombre>` (forzar), `pantalla automática`, `estado de la configuración`.
- [x] La gramática en caliente solo incluye los cursores de la pantalla actual (y los nombres de las pantallas y webs).
- [x] Cursores por **selector** (`id`/`text`/`desc`, con alternativas por idioma) con **coordenadas** como respaldo (`xPct`/`yPct` o píxeles; negativos desde el borde).
- [x] Acciones: `tap`, `longpress`, `doubletap`, `swipe`, `scroll`.

**Scroll**
- [x] **Scroll automático**: si el elemento por selector no está a la vista, se pide al sistema que lo muestre (`ACTION_SHOW_ON_SCREEN`) o se desplaza la lista hasta encontrarlo (`scroll.maxSteps`, `resetFirst`).
- [x] `arrastra …` busca el contenedor desplazable (el del elemento actual o el primero de la ventana) y, si no hay, hace un gesto por el centro.

**Webs y sesión**
- [x] Detección de la **web abierta** leyendo el dominio de la barra de direcciones del navegador (Chrome, Firefox, Brave, Edge, Samsung, DuckDuckGo, Opera).
- [x] Sección `webs` por dominio (subdominios incluidos), con `version`/`fecha` = día en que se escribió la configuración. Sus cursores se suman a los del navegador.
- [x] Webs incluidas: Google, X, Marca, Instagram, YouTube, Facebook, Amazon, ChatGPT, WhatsApp Web, DeepSeek, Wikipedia, GitHub, Gmail, Gist, foro.elhacker.net y Google Traductor.
- [x] Apps incluidas: Chrome, Telegram, WhatsApp, YouTube, Gmail, Ajustes, Spotify, Maps, Instagram, Facebook y X.
- [x] **Detección de sesión iniciada** (`login`: `required`, `loggedIn`, `loggedOut`, `hint`). Se avisa al entrar en el modo y con `he iniciado sesión`.
- [x] `abre la web de <nombre>` y `qué webs conoces`.

**Feedback por voz y ayuda para todos**
- [x] **Bienvenida al abrir la app** ("Espera, cargando voz inteligente" con espeak; si Sherpa carga se usa, si no, la de por defecto) y explicación de cómo hablarle. El servicio **arranca solo** al abrir la app y solo se detiene con el botón de detener.
- [x] **Ayuda por temas** (aplicaciones, pantalla, lectura, cursores, webs, sesión, voz, escucha, escritura, completa) con menú en vez de un único mensaje largo.
- [x] **Gramática renombrada**: *modo usar* → **modo pantalla**, con varios alias; mensajes cortos que dicen siempre qué decir.
- [x] **Preguntas sobre la pantalla**: `dónde estoy`, `lee los textos`, `lee los botones`, `lee los campos`.
- [x] **`lee todo` por páginas** (8 en 8, `más elementos`), con **agrupación de elementos desconocidos** solo en la lectura y **sin recortar** textos (mensajes de Telegram completos).
- [x] `lee cursores` rehecho: lista numerada y completa, con instrucciones de uso.
- [x] `GUIA_DE_USO.md` para el usuario final.

**Preparación del modo dictado (sin implementar)**
- [x] `dictation/DictationApi.kt`: `InputKind`, `EditorMode`, `GrammarProfile`, `TextTarget`, `DictationRequest`, `DictationEvent`, `DictationSession`, `DictationEngine` (`NoDictationEngine` de relleno), `AccessibilityTextTarget` y `JsonGrammarProfileProvider`.
- [x] `vocalia-grammar-profiles.json`: deletreo, correo, números, conversación, jerga de informática y ciberseguridad, JavaScript, Python, C y C++.

**Pendiente de validar / calibrar**
- [ ] Selectores, ids y coordenadas de las apps y webs incluidas (son estimaciones hechas sin ver las pantallas reales).
- [ ] Reglas `detect` de las pantallas (Telegram: chat, ajustes, contactos; Chrome: menú, selector de pestañas).
- [ ] Detección del dominio en cada navegador (ids de la barra de direcciones).
- [ ] Reglas de `login` de cada web.
- [ ] Rendimiento del scroll automático (espera hasta ~3 s con el hilo principal ocupado).

**Notas técnicas críticas:**
- **Esperar el resultado de `dispatchGesture` bloqueando el hilo principal** produce un falso "cancelado" tras el timeout: el callback se entrega por el propio hilo principal del servicio. Hay que enviar el gesto sin esperar.
- **`getCurrentNode()` no suele ser desplazable**: para hacer scroll hay que subir al ancestro desplazable o buscar el primero de la ventana.
- **Los nodos fuera de pantalla** pueden existir en el árbol sin ser visibles; `ACTION_SHOW_ON_SCREEN` los trae a la vista antes de desplazar a ciegas.
- **El parser de `org.json` de Android es tolerante**, lo que permite comentarios y valores sin comillas en los JSON editados a mano. Las claves duplicadas no dan error: gana la última, por eso los selectores con alternativas son listas.
- **Los ficheros de configuración de apps y los perfiles de gramática no se pueden mezclar**: solo se leen como configuración los `vocalia-*-apps-config.json`.
- **Las webs responsivas cambian de diseño**: en webs, mejor selectores por texto/descripción que coordenadas.

---

### 🔮 Fase 2.2 — Modo pantalla: más cobertura

**Estado:** planificada.

- [ ] **Etiquetar por voz**: "etiqueta este elemento como …" guarda el cursor (nombre, descripción, posición) en el fichero de la app o web.
- [ ] Avisar por voz de los cambios de pantalla importantes (diálogos, cambios de ventana, nuevos mensajes).
- [ ] Cambiar entre ventanas / notificaciones / barra de estado.
- [ ] Renumeración automática opcional al detectar cambio de pantalla.
- [ ] Velocidad de voz ajustable ("habla más despacio") — requiere exponer `setSpeed` en `TtsManager`.
- [ ] Más apps y webs calibradas, y configuraciones por fabricante (Huawei, Xiaomi, Samsung).
- [ ] Ayuda contextual ("qué puedo hacer aquí") a partir de los cursores de la pantalla actual.

---

### 🚧 Fase 1.4 — Modo dictado: un "vim por voz"

**Estado:** planificada. Las **interfaces** (`dictation/`) y los **perfiles de gramática** ya están definidos; la base del chunking por frase ya está lista en `TtsManager`.

Objetivo: dictar texto, navegarlo, editarlo y usarlo, con modos como vim (**insertar**, **visual**, **normal/navegar**, **órdenes**: guardar, descartar), y poder usarlo para rellenar formularios de cualquier app o web: usuario, nombre, fecha, edad, correo, contraseña, captcha, mensajes.

**Decisión de diseño clave:** con Vosk `small` solo en español, hay que **decir antes qué se va a escribir** para cargar una gramática cerrada adaptada. No es lo mismo:

| Tipo (`InputKind`) | Qué necesita |
| :--- | :--- |
| `PASSWORD`, `USERNAME`, `CAPTCHA` | Deletreo letra a letra, mayúsculas/minúsculas, símbolos (`arroba`, `guion bajo`…). Nunca se lee de vuelta ni va al log. |
| `EMAIL`, `URL` | Deletreo + dominios habituales + `arroba`, `punto`, `barra`. |
| `NUMBER`, `DATE`, `AGE` | Números, meses, fórmulas de fecha. |
| `CONVERSATION` | Español corriente + signos de puntuación. |
| `CODE` | Palabras clave del lenguaje (`if`, `for`, `switch`; `var`/`let`; `print`; `printf`; `cout`/`cin`) y símbolos. Un perfil por lenguaje. |
| `FREE_TEXT` con tema | Jerga y siglas (IT: `router`, `PC`; ciberseguridad: `MITM`, `phishing`; etc.). |

**Ya preparado**
- [x] `GrammarProfile` + `GrammarProfileProvider` + `JsonGrammarProfileProvider` (ficheros editables en la misma carpeta que la configuración).
- [x] `TextTarget` con implementación para el campo con foco de otra app (`AccessibilityTextTarget`: `ACTION_SET_TEXT`, selección, cursor, detección de campo de contraseña).
- [x] `DictationRequest` / `DictationSession` / `DictationEngine` / `DictationEvent`, `EditorMode`.
- [x] `focusedInput()` en el servicio de accesibilidad.

**Por hacer**
- [ ] Estado `DICTATION` en `VocaliaCore` y carga de la gramática del perfil en Vosk al entrar.
- [ ] Modo dictado (`Recognizer` con gramática por perfil o sin gramática).
- [ ] Buffer de texto acumulativo y modos tipo vim: insertar, visual, normal, órdenes.
- [ ] Comandos de transporte sobre el buffer:
  - "repite" / "lee todo" / "borra último" / "borra todo".
  - "busca [término]" → salta a la primera ocurrencia y lee desde ahí.
  - "atrás" / "adelante" → mueve el puntero de lectura.
  - "guardar cambios" / "descartar cambios" / "copia al portapapeles".
  - "fin dictado" → vuelve a wake word.
- [ ] Símbolos especiales por voz: "dos puntos x" → `:x`, "punto y aparte", "tabulador", "enter".
- [ ] Modo programación: atajos de teclado por voz (Ctrl+C, Ctrl+V, Escape, flechas).
- [ ] Navegación del TTS (aprovechando el chunking por frase): "repite la última frase", "salta 5 frases", timestamps por frase.
- [ ] Feedback: leer cada frase dictada (configurable on/off; desactivado para contraseñas).
- [ ] Rellenar campos de formularios por tipo ("escribe mi correo", "escribe la contraseña") con confirmación.

**Notas:**
- El chunking por frase es deliberadamente la pieza reutilizable aquí: `TtsManager.getChunks()` + `getCurrentChunkIndex()` dan todo lo necesario para implementar repetición y salto de frases sin tocar la lógica de reproducción.
- Cuidado con los comandos que ya existen en el modo pantalla ("lee todo", "busca elemento…"): el dictado deberá usar otro espacio de nombres o un estado propio.
- Vosk modelo small tiene precisión limitada. Para dictado serio, evaluar:
  - Modelo Vosk `large` (1.4 GB, ~500 MB RAM).
  - **Whisper.cpp en modo batch** (graba frase → transcribe, ~1-2 s por frase, 244 MB modelo small int8).
  - Sherpa-onnx ASR con modelo Zipformer (streaming, ~50-150 MB).
- La decisión se tomará con pruebas reales de precisión y latencia.
- El buffer navegable es la pieza única de este proyecto. Sin él, dictar 2000 líneas es inútil.
- Las contraseñas y los captchas son un caso aparte: dictado deletreado, sin eco, sin log y con confirmación explícita.

---

### 🚧 Fase 1.5 — Comandos de sistema

**Estado:** planificada.

- [ ] "sube volumen" / "baja volumen" / "silencia" → `AudioManager`.
- [ ] "qué batería tengo" → `BatteryManager`.
- [ ] "qué wifi estoy usando" → `WifiManager`.
- [ ] "activa/desactiva wifi" (requiere permisos especiales).
- [ ] "busca en Google [término]" → `Intent.ACTION_WEB_SEARCH` (o navegador directo). (`abre la web de …` ya existe para las webs configuradas.)
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
- [ ] Modo "dictado a terminal" para insertar texto (reutiliza los perfiles de gramática de código).
- [ ] YouTube audio: `yt-dlp` + `mpv --no-video`.
- [ ] SSH a servidores remotos.

**Notas:**
- Termux **no es obligatorio** para el usuario ciego sin manos. Es una capa extra para el usuario programador.
- El buffer navegable de Fase 1.4 se reutiliza aquí para leer salidas largas.
- El modo pantalla (Fase 2) ya permite leer y pulsar elementos de la propia app Termux; esta fase añade un canal más fiable para terminal.

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
- [ ] Guía de instalación por voz: la app lee los pasos y guía al usuario (la bienvenida de la Fase 2.1 es el primer paso).
- [ ] Activación guiada del servicio de accesibilidad (requisito del modo pantalla).
- [ ] Configuración inicial por voz:
  - Idioma (español/inglés).
  - Números de emergencia (grave/leve).
  - Contacto de emergencia.
  - Wake word personalizada (opcional).
  - Calibración de micrófono.
- [ ] Detección de fabricante (Huawei, Xiaomi, Samsung) y guía específica de batería.
- [ ] Descarga de parches de configuración por dispositivo (actualización de los JSON sin reinstalar).
- [ ] README y `LEGALITY.md` en el repo con enlaces a normativa por país.

---

### 🔮 Fase 6 — Emergencias y feedback

**Estado:** planificada.

- [ ] "ayuda" / "emergencia" → llama al número configurado (con confirmación obligatoria). (Hoy "ayuda" abre la ayuda por temas; habrá que definir una palabra distinta para emergencias.)
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
| **Control de apps** | `AccessibilityService` + `dispatchGesture` | Único método sin root ni ADB que funciona en cualquier app, incluidos clicks por coordenadas. |
| **Configuración** | JSON (`org.json` de Android) por dispositivo/app/web | Editable y recargable en caliente, sin dependencias, parser tolerante. |
| **Ayuda** | `HelpTopics`, por temas | No abrumar: un tema cada vez, siempre con ejemplo. |
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
- ❌ **Aceptar comandos sin wake word en modo pantalla**: cualquier ruido o conversación ejecuta acciones. Solución: exigirla salvo en escucha continua.
- ❌ **Recorrer el árbol de accesibilidad en BFS**: mezcla niveles y desordena la lectura. Solución: preorden (DFS).
- ❌ **Leer solo "botón" para un elemento**: sin nombre no sirve para un usuario ciego. Solución: nombre accesible con varias fuentes, descendientes y, como último recurso, el identificador de la vista.
- ❌ **Leer el contenido de campos de contraseña**: nunca. Solo se dice su longitud.
- ❌ **Renumerar elementos en cada refresco**: los números dejarían de corresponder a lo que oyó el usuario. Solución: instantánea numerada separada, que solo cambia con `lee todo` o una búsqueda.
- ❌ **Esperar el callback de `dispatchGesture` bloqueando el hilo principal**: el callback se entrega por ese mismo hilo, así que siempre vence el timeout y el TTS dice "cancelado" aunque el toque funcionó. Solución: enviar sin esperar y registrar el resultado en el log.
- ❌ **Hacer scroll sobre el elemento actual**: casi nunca es desplazable. Solución: subir al ancestro desplazable o buscar el primero de la ventana, con gesto como respaldo.
- ❌ **Leer uno a uno los elementos sin nombre** en `lee todo`: ocupan el TTS sin aportar nada. Solución: agruparlos en la lectura ("elementos del 3 al 20, sin información") conservando su numeración.
- ❌ **Acortar textos con "…, y más"** en la lectura: se pierden los mensajes. Solución: no recortar (o recortar muy por encima de lo habitual) y leer por páginas.
- ❌ **Cursores globales para toda la app**: en un chat no existe "sexto chat". Solución: pantallas con sus propios cursores y detección de pantalla (con opción de forzarla).
- ❌ **Un único mensaje de ayuda larguísimo**: abruma y no se retiene. Solución: ayuda por temas, con menú.
- ❌ **Nombres técnicos en las órdenes** ("modo usar"): no dicen qué hace. Solución: nombres descriptivos ("modo pantalla") con alias.
- ❌ **Claves duplicadas en un objeto JSON para dar alternativas** (`"text": "a", "text": "b"`): el parser se queda con la última sin avisar. Solución: listas de alternativas.
- ❌ **Mezclar configuración de apps con otros ficheros `vocalia-*.json`**: se interpretan como dispositivos. Solución: solo se leen los `vocalia-*-apps-config.json`.

---

## ⚠️ Casos límite y consideraciones

### Legales

- **Conducción:** la legalidad del uso de dispositivos manos libres varía por país. Vocalia **no se hace responsable** del uso que se le dé. El README y `LEGALITY.md` advierten de que el usuario debe consultar la normativa de su país.
- **Grabación de audio:** la app no envía audio a la nube. Todo se procesa offline. No hay grabaciones persistentes más allá del buffer temporal en RAM.
- **Datos personales:** el `INTERNAL_DEBUG_FILE` puede contener texto reconocido por Vosk, descripciones de elementos de pantalla (nombres, URLs, contenido de campos de texto) y los textos visibles de cada pantalla detectada. El usuario es responsable de borrarlo si lo comparte.
- **Accesibilidad:** el servicio de accesibilidad da acceso al contenido de la pantalla. Vocalia lo usa solo en local, no envía nada fuera del dispositivo y no lee contraseñas.
- **Sesiones y cuentas:** Vocalia solo **detecta** si hay sesión iniciada en una web; no guarda usuarios ni contraseñas. Cuando exista el modo dictado, las contraseñas no se leerán de vuelta ni se registrarán.

### Técnicos

- **Memoria:** Vosk small (~400 MB) + Sherpa (~250 MB) + espeak (~30 MB) + Android + app pueden acercarse a 1 GB. En un P40 Lite con 4 GB va, pero el sistema puede matar procesos en segundo plano bajo presión.
- **Batería:** Vosk con micrófono siempre activo consume. Se recomienda cargar el móvil mientras se conduce.
- **Micrófono + altavoz simultáneos:** funciona en el P40 Lite. Otros dispositivos pueden requerir `MODE_IN_COMMUNICATION` y `speakerphone`. Hay que testear por dispositivo.
- **Eco del TTS en Vosk:** el altavoz se cuela por el micrófono y Vosk transcribe el propio TTS. Mitigaciones: (1) anti-eco por wake word en `TTS_SPEAKING`; (2) TTS bloqueante en `Ayuda` y en la bienvenida; (3) en el futuro, AEC por hardware o cancelación de eco en software si aparecen más casos. Con lecturas largas (`lee todo`), para detener hay que decir "vocalia para".
- **Wake word en entornos ruidosos:** el modelo small puede fallar. Opciones: modelo Vosk large, o wake word de dos palabras.
- **Gramática dinámica del modo pantalla:** crece con los nombres de los elementos, las frases de los cursores y los nombres de las webs (topes de 150 palabras y 150 frases). Las palabras fuera del vocabulario de Vosk se ignoran y no se podrán decir.
- **Árbol de accesibilidad:** cada app lo construye distinto. Apps con WebView, juegos o interfaces propias (Flutter, Unity) pueden exponer pocos o ningún nodo. Las **webs en el navegador** exponen su contenido, pero con etiquetas y estructura muy variables.
- **Detección de la web:** depende del id de la barra de direcciones de cada navegador (`url_bar` en Chrome, etc.). Si el navegador cambia el id o la barra está oculta, no se reconoce la web. El texto de la barra puede ser una búsqueda en vez de una dirección.
- **Selectores y coordenadas:** las configuraciones de apps y webs son **estimaciones** hasta que se calibran en cada dispositivo. Las coordenadas cambian con el tamaño de pantalla, el idioma y las actualizaciones; los selectores por texto cambian con el idioma.
- **Scroll automático:** espera hasta ~3 s con el hilo principal ocupado; si una app tarda en pintar, puede no encontrar el elemento.
- **Idioma:** Vosk y Piper son monolingües por modelo. Cambiar de idioma requiere cargar otro modelo. Planificado para Fase 1.5+. Los selectores de los JSON llevan alternativas es/en.
- **Modelo Vosk small vs large:** el small es rápido pero limitado. Para dictado serio, evaluar Whisper.cpp en batch o Sherpa ASR.

### De instalación

- **Firma de debug:** el APK no está firmado con clave de release. Android muestra aviso al instalar. Aceptable para sideload.
- **Huawei/EMUI:** requiere configuración manual de batería (documentado en README). Sin esto, el servicio muere en minutos.
- **Servicio de accesibilidad:** hay que activarlo a mano en Ajustes → Accesibilidad (y puede requerir desactivarlo y reactivarlo tras cambiar `canPerformGestures`). Android puede desactivarlo si la app se actualiza o se fuerza su cierre. Una persona ciega o mayor necesitará ayuda para este paso una vez.
- **Arranque al abrir la app:** el servicio arranca solo con permiso de micrófono; sigue siendo `START_NOT_STICKY`, así que si Android lo mata, el watchdog o abrir la app lo relanzan.
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
2. Añade la variante a la gramática en `VocaliaService.kt` (`buildGrammar()`). Si es un comando del modo pantalla, añádela a `GRAMMAR_UI_NAVIGATION` en `VocaliaCore.kt`:
   ```kotlin
   "tu-comando", "variante1", "variante2",
   ```
3. (Opcional) Si el comando requiere una acción nueva en `VocaliaCore.kt`, añade un `case` al `when (parsed)` y el correspondiente `CommandResult`.
4. (Opcional) Si el usuario debe conocerlo, añádelo al tema correspondiente de `HelpTopics.kt` y a `GUIA_DE_USO.md`.
5. Commit, push, espera al APK.
6. Prueba y abre PR.

### Añadir una app o una web

No hace falta tocar código. Edita (o crea) un fichero `vocalia-*-apps-config.json`:
1. Añade la app (`apps`) o la web (`webs`, por dominio) a la sección `default`, o a la de un fabricante/modelo si solo vale ahí.
2. Define sus `screens` con sus cursores y reglas `detect`.
3. Usa **selectores** (`text`/`desc`/`id`) siempre que puedas y coordenadas en porcentaje como respaldo.
4. Calibra con `lee todo`, `elemento N, detalle`, `qué pantalla es` y el log.

La referencia completa está en `vocalia-configs-LEEME.md`.

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

### Mejorar la descripción de elementos del modo pantalla

La lógica está en `VocaliaAccessibilityService`:
- `accessibleName()` → de dónde sale el nombre (añade aquí nuevas fuentes).
- `roleOf()` / `stateOf()` → tipo y estado hablados.
- `describeNode()` (breve), `describeNumbered()` (listas), `groupedLines()` (agrupación de desconocidos) y `describeCurrentNodeDetailed()` (detalle).
- `SpeechText.speakable()` → cómo se pronuncian URLs y textos largos.

### Implementar el modo dictado

Empieza por `dictation/DictationApi.kt`:
1. Implementa `DictationEngine` y `DictationSession`.
2. Añade un estado `DICTATION` en `VocaliaCore` que cargue en Vosk la gramática del perfil (`GrammarProfileProvider.forKind(...)`).
3. Escribe con `AccessibilityTextTarget` (o un búfer propio que implemente `TextTarget`).
4. Añade perfiles a `vocalia-grammar-profiles.json`.

### Traducir a otro idioma

1. Añade el modelo Vosk del idioma en `assets/`.
2. Añade el modelo Piper del idioma (si existe).
3. Traduce las gramáticas, `HelpTopics` y los mensajes.
4. Añade comando "habla en [idioma]".

---

## 📊 Estado actual

| Fase | Estado | Notas |
| :--- | :--- | :--- |
| Fase 0 | ✅ | Servicio robusto, boot, watchdog |
| Fase 1.1 | ✅ | espeak-ng español |
| Fase 1.2 | ✅ | Wake word, comandos, TTS dual |
| Fase 1.3 | ✅ | Abrir/cerrar apps, listar apps, pausa/reanudación TTS (inestable), chunking por frase, ayuda bloqueante |
| Fase 2.0 | ✅ | Modo usar: navegación, nombres accesibles, elementos numerados, búsqueda, gramática en caliente |
| Fase 2.1 | 🚧 | Modo pantalla: configuración por app/web, pantallas, clicks por coordenadas, scroll automático, sesión, ayuda por temas, bienvenida. Implementada, **en validación** |
| Fase 2.2 | 🔮 | Etiquetar por voz, avisos de cambios de pantalla, más cobertura |
| Fase 1.4 | 🚧 | Modo dictado (vim por voz). Interfaces y perfiles de gramática listos |
| Fase 1.5 | 🚧 | Comandos de sistema |
| Fase 3 | 🔮 | Termux + terminal |
| Fase 4 | 🔮 | Sherpa en proceso separado |
| Fase 5 | 🔮 | Instalador todo en uno |
| Fase 6 | 🔮 | Emergencias + feedback |
| Fase 7 | 🔮 | Release + licencia comercial |

**Leyenda:** ✅ completada · 🚧 en desarrollo / en validación · 🔮 planificada

---

## 📅 Historial de releases

| Tag | Fecha | Hito |
| :--- | :--- | :--- |
| `v0.0.0-debug` | — | Release de pruebas iniciales |
| `v0.3.0-sherpa` | — | Wake word + comandos + TTS dual |
| `v0.4.0-vocalia` | — | Abrir/cerrar apps, listar apps, pausa/reanudación TTS, chunking por frase, ayuda bloqueante |
| `v0.5.0-vocalia` | 2026-10-31 | Modo usar con AccessibilityService: navegación con posición persistente, nombres accesibles, `lee todo` con elementos numerados, búsqueda por texto con gramática en caliente, ayuda del modo usar, wake word obligatoria salvo escucha continua, arreglo del corte del TTS |
| `v0.6.0-vocalia` | próxima | Modo pantalla: configuración por app y web (pantallas, cursores, selectores, scroll automático, sesión), clicks por coordenadas, lectura por páginas, `dónde estoy`, ayuda por temas, bienvenida y arranque automático, `GUIA_DE_USO.md`, interfaces del modo dictado |

---

**Documento mantenido por `StringManolo`. Se acepta feedback vía issues y PRs.**