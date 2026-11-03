# Configuración de apps de Vocalia

Estos ficheros dicen a Vocalia dónde están y qué hacen los elementos de cada app,
para poder usar por voz incluso lo que no es "pulsable" para el sistema.

Carpeta (la misma donde está este fichero):
`/sdcard/Android/data/io.github.stringmanolo.vocalia.debug/files/configs/`

Se **recargan solos** cuando guardas un cambio (o di "vocalia, recarga configuración").
Si Vocalia se actualiza y tú has editado un fichero, no se pisa: la versión nueva se deja
como `<fichero>.new` para que la compares.

## Qué ficheros se leen
Todos los `vocalia-*.json` de la carpeta. Puedes crear los tuyos, p. ej. `vocalia-mis-apps-config.json`.

## Estructura

```json
{
  "schema": 2,
  "devices": {
    "default":       { "apps": { ... } },
    "huawei":        { "apps": { ... } },
    "HuaweiP40Lite": { "match": ["JNY-LX1"], "apps": { ... } }
  }
}
```

**Prioridad (gana la última):** `default` < fabricante < modelo. A igual prioridad, el orden
alfabético del nombre del fichero. Solo se aplican las secciones que coinciden con tu móvil.
Las claves de dispositivo se comparan sin mayúsculas ni símbolos con: fabricante, marca,
modelo, `Build.DEVICE` y el nombre del dispositivo de Ajustes. `match` añade alias.
Para ver qué claves tiene tu móvil: `[config] claves de dispositivo reconocidas` en el log.

**Fusión:** una sección específica no sustituye a la general, la **parchea**: solo pones lo que
cambia. Para borrar un elemento heredado: `"chat3": null`. Para desactivarlo: `"enabled": false`.

## Una app

```json
"telegram": {
  "canonical-name": "org.telegram.messenger.web",
  "packages": ["org.telegram.messenger"],
  "description": "Telegram",
  "groups":  { "pestanas": { ... cursores reutilizables ... } },
  "ui":      { ... cursores válidos en TODAS las pantallas ... },
  "screens": { "listaChats": { ... }, "chat": { ... } },
  "versions": { "13.1.0": { "ui": {...}, "screens": {...}, "groups": {...} } }
}
```

- `canonical-name` / `package` y `packages`: paquetes de Android que identifican la app.
- `ui`: cursores comunes, disponibles en cualquier pantalla de la app.
- `screens`: las pantallas de la app. **Los cursores de una pantalla solo existen mientras
  estás en ella** (en un chat no existe "sexto chat"; en ajustes sí existe "idioma").
- `groups`: conjuntos de cursores con nombre que varias pantallas pueden reutilizar con
  `"include": ["nombre"]` (por ejemplo, la barra de pestañas inferior).
- `versions`: ajustes por versión de la app. Se usa la versión de configuración más alta que no
  supere la instalada (si la instalada es más nueva que todas, la más reciente) y se aplica
  encima de `ui`, `screens` y `groups`.

## Una pantalla

```json
"chat": {
  "default": false,
  "gramatica": ["conversación", "chat abierto"],
  "descripcion": "Conversación abierta",
  "include": ["pestanas"],
  "detect": {
    "all":  [ { "desc": "Adjuntar" } ],
    "any":  [ { "text": "Mensaje" }, { "desc": "Enviar" } ],
    "none": [ { "text": "Privacidad y seguridad" } ],
    "priority": 1
  },
  "ui": { ... cursores propios ... }
}
```

- `default: true`: pantalla que se usa si no se reconoce ninguna otra.
- `gramatica`: nombres con los que se puede forzar: `vocalia, pantalla chat abierto`.
- `include`: grupos de cursores que se añaden a la pantalla.
- `detect`: cómo reconocerla **sola**, mirando lo que hay visible en la app.
  - `all`: se tienen que cumplir todas. `any`: al menos una. `none`: no debe cumplirse ninguna.
  - Condiciones: `text` (contiene, sin mayúsculas ni acentos), `desc` (descripción de contenido,
    igual), `id` (id de vista, corto o completo), `class` (termina en…), `activity` (clase de la
    ventana), y `"selected": true` para exigir que el elemento esté seleccionado.
  - `priority`: si varias pantallas cumplen, gana la de mayor prioridad y luego la que cumple más condiciones.
  - Un atajo: `"detect": { "text": "Idioma" }` equivale a `all` con una condición.
- Sin `detect` la pantalla no se reconoce sola: solo es la pantalla por defecto o se fuerza por voz.
- La pantalla forzada con `pantalla X` se olvida al pulsar algo, volver atrás o cambiar de app.

## Un elemento (cursor)

```json
"chat1": {
  "gramatica": ["primer chat", "chat uno"],
  "descripcion": "Primer chat del listado",
  "descripcionLarga": "El chat más reciente. Al pulsarlo se abre la conversación.",
  "xPct": 50, "yPct": 28
}
```

| Campo | Significado |
| :--- | :--- |
| `gramatica` | Frases con las que lo nombras. Se añaden en caliente al reconocimiento de voz mientras estás en esa pantalla. El nombre de la clave (`botonHamburguesa` → "boton hamburguesa") cuenta también. |
| `descripcion` / `descripcionLarga` | Lo que se oye con `lee` y con `detalle`. |
| `x`, `y` | Posición en **píxeles**. Un valor negativo cuenta desde el borde derecho/inferior. También vale `"50%"`. |
| `xPct`, `yPct` | Posición en **porcentaje** (0–100) de la pantalla. Lo más portable entre móviles. |
| `action` | `tap` (por defecto), `longpress`, `doubletap` o `swipe`. |
| `x2`,`y2` / `x2Pct`,`y2Pct` | Destino del deslizamiento (solo `swipe`). |
| `durationMs` | Duración del gesto. |
| `selector` | `{ "id": "...", "text": "...", "desc": "..." }`: se intenta **primero** localizar el elemento real en pantalla (más robusto si cambia el diseño); si no aparece, se usan las coordenadas. |
| `enabled` | `false` para desactivarlo. |

Un elemento sin coordenadas ni selector es **solo informativo**: se puede leer pero no pulsar.

## Uso por voz (modo usar)

- `cursor primer chat` → te coloca en él y lo describe. Luego: `pulsa`, `lee`, `detalle`, `arrastra abajo`.
- `pulsa primer chat` → lo pulsa directamente.
- `lee cursores` → lista los de la pantalla actual.
- `qué pantalla es` → dice la pantalla actual y si fue detectada o forzada.
- `pantalla ajustes` → fuerza esa pantalla. `pantalla automática` → vuelve a detectarla sola.
- `recarga configuración` / `estado de la configuración`.

## Cómo calibrar
**Coordenadas**
1. Entra en la app y di `vocalia, modo usar`, luego `vocalia, lee todo`.
2. `vocalia, elemento N, detalle` dice las coordenadas y el porcentaje de ese elemento.
3. El log (`INTERNAL_DEBUG_FILE`) lista los límites de todos los elementos numerados.

**Reconocer una pantalla**
1. Ve a la pantalla y di `vocalia, qué pantalla es`.
2. En el log, las líneas `pantalla -> …` listan los textos, descripciones e ids visibles. Elige
   algo que solo aparezca en esa pantalla y ponlo en `detect`.

## Formato
El lector es el de Android y es **tolerante**: admite comentarios (`//`, `#`, `/* */`),
claves sin comillas, comillas simples y valores como `v13.1.0` sin comillas.
Los campos que empiezan por `_` (como `_nota`) se ignoran.

---

## Novedades de la versión 3 del formato

### Webs (`webs`)
Además de `apps`, cada sección de dispositivo puede tener `webs`. Una web se identifica por su
**dominio**, que Vocalia lee de la barra de direcciones del navegador (Chrome, Firefox, Brave, Edge, Samsung…).

```json
"webs": {
  "wikipedia.org": {
    "name": "Wikipedia",
    "gramatica": ["wikipedia", "la wikipedia"],
    "url": "https://es.wikipedia.org",
    "version": "2026-11-02",
    "description": "Enciclopedia libre",
    "domains": ["wikipedia.org", "wikipedia.com"],
    "login": { ... },
    "ui": { ... },
    "screens": { ... }
  }
}
```

- `domains`: sirven también los subdominios (`es.wikipedia.org` coincide con `wikipedia.org`).
- `version` / `fecha`: las webs no tienen versión pública; se usa **el día en que se escribió** la configuración.
- `url` y `gramatica`: permiten `vocalia, abre la web de wikipedia`.
- Los cursores de la web se suman a los del navegador (barra de direcciones, pestañas…). Si el mismo nombre existe en ambos, gana el de la web.
- Mejor **selectores** que coordenadas: el diseño de una web cambia según el tamaño de pantalla.

### Selectores con alternativas
`text`, `desc` e `id` aceptan una lista, para varios idiomas o variantes:

```json
"selector": { "text": ["Configuración", "Settings"], "id": "menu_button" }
```

### Desplazamiento automático
Si un cursor tiene `selector` y el elemento **no está a la vista** (por ejemplo, "Configuración" al
final del menú de Chrome), Vocalia lo busca desplazando la lista hacia abajo hasta encontrarlo.

```json
"scroll": { "mode": "auto", "maxSteps": 8, "resetFirst": false }
```

- `mode`: `auto` (por defecto) o `none`.
- `maxSteps`: pasos máximos (1–30). Cada paso espera unos 0,35 s.
- `resetFirst`: `true` = volver antes al principio de la lista, para que el recorrido sea siempre el mismo.

### Acción `scroll`
Un cursor puede ser un desplazamiento en sí mismo:

```json
"bajarMenu": { "gramatica": ["bajar menú"], "action": "scroll", "direction": "down", "times": 2 }
```

`direction`: `down` o `up`. `times`: cuántas veces. También sirve `swipe` entre dos puntos.

### Sesión iniciada (`login`)
Para webs y apps que no funcionan sin cuenta:

```json
"login": {
  "required": true,
  "loggedIn":  { "any": [ { "text": "Recibidos" }, { "text": "Inbox" } ] },
  "loggedOut": { "any": [ { "text": "Iniciar sesión" }, { "text": "Sign in" } ] },
  "hint": "Pide ayuda a una persona de confianza para iniciar sesión una vez."
}
```

- `loggedIn` / `loggedOut` usan las mismas reglas que `detect` (`all`, `any`, `none`).
- Si se cumple `loggedIn` → "Tienes la sesión iniciada". Si no, y se cumple `loggedOut` → "No has iniciado sesión…" más el `hint`.
- `required: true` añade "Esta web no funciona sin iniciar sesión".
- Se dice al entrar en el modo pantalla y con `vocalia, he iniciado sesión`.

### Ficheros
Solo se leen como configuración de apps y webs los ficheros `vocalia-*-apps-config.json`.
`vocalia-grammar-profiles.json` es otra cosa: los perfiles de gramática del futuro modo dictado.

## Comandos nuevos (modo pantalla)
`dónde estoy`, `lee los textos`, `lee los botones`, `lee los campos`, `más elementos`,
`he iniciado sesión`, `qué webs conoces`, `abre la web de …`, `ayuda <tema>`.

---

## Ayuda propia por app, web y pantalla (`help`)

Cada app, web o pantalla puede llevar una ayuda que Vocalia dice cuando el usuario pide **"vocalia, ayuda"**
con esa app o web delante. Es un texto o una lista de frases (se unen):

```json
"contactos": {
  "name": "Contactos",
  "help": [
    "Aquí están tus contactos, ordenados por nombre.",
    "Para buscar a una persona, di: vocalia, pulsa buscar."
  ],
  "screens": {
    "lista": { "help": "Estás en la lista. Para oír los contactos, di: vocalia, lee los textos." }
  }
}
```

Qué dice Vocalia: **"Ayuda para <nombre>."** + el `help` de la web (si la hay) y de la app + el `help` de la pantalla actual +
**cuántos cursores tiene declarados** + cómo oírlos (`lee cursores`, `lee los campos`, `dónde estoy`) +
**"Si lo que querías era la ayuda general, di: vocalia, ayuda general."**

- Si la app o web no tiene configuración, "ayuda" da la ayuda general.
- `ayuda general` da siempre la general. `ayuda de esta aplicación` fuerza la específica.
- Escribe las frases como se oyen: di el comando completo ("di: vocalia, pulsa buscar").

