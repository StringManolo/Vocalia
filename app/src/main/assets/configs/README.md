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
