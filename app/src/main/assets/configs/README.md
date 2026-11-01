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
  "schema": 1,
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
  "ui": { ... },
  "versions": {
    "13.1.0": { "ui": { ... } }
  }
}
```

- `canonical-name` / `package` y `packages`: paquetes de Android que identifican la app.
- `ui`: elementos válidos para **todas** las versiones.
- `versions`: ajustes por versión. Se usa la versión de configuración más alta que no supere
  la instalada (si la instalada es más nueva que todas, la más reciente). Se aplica encima de `ui`.

## Un elemento (cursor)

```json
"chat1": {
  "gramatica": ["primer chat", "chat uno"],
  "descripcion": "Primer chat del listado",
  "descripcionLarga": "El chat más reciente. Al pulsarlo se abre la conversación.",
  "xPct": 50, "yPct": 14
}
```

| Campo | Significado |
| :--- | :--- |
| `gramatica` | Frases con las que lo nombras. Se añaden en caliente al reconocimiento de voz de esa app. El nombre de la clave (`botonHamburguesa` → "boton hamburguesa") cuenta también. |
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
- `lee cursores` → lista los de la app en primer plano.
- `recarga configuración` / `estado de la configuración`.

## Cómo calibrar coordenadas
1. Entra en la app y di `vocalia, modo usar`, luego `vocalia, lee todo`.
2. `vocalia, elemento N, detalle` dice las coordenadas y el porcentaje de ese elemento.
3. El log (`INTERNAL_DEBUG_FILE`) lista los límites de todos los elementos numerados.
4. Pon esos valores en el JSON, guarda y prueba con `cursor …`.

## Formato
El lector es el de Android y es **tolerante**: admite comentarios (`//`, `#`, `/* */`),
claves sin comillas, comillas simples y valores como `v13.1.0` sin comillas.
Los campos que empiezan por `_` (como `_nota`) se ignoran.
