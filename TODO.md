# TODO

Cosas concretas que añadir/parchear/evaluar.

### Fase siguiente
Añadir lo de usar apps (ahora mismo solo abre y cierra, useless).

1. Tiene que leer lo que hay en pantalla. (TODO).

2. Tiene que poder interactuar (equivalente a hacer tab en teclado de pc), (toggle por elementos)

2.1 Tiene que leer lo que hay toggleado

2.2 Tiene que poder enviar eventos al elemento toggleado, como click, drag, etc. Se podría hacer por ejemplo con el fake input que usa el repo https://github.com/stringmanolo/selfcontrol

3 Sistema de dictado (te relee lo que llevar escrito y demás. Básicamente es como vi/vim pero interno por voz, y una vez confirmado te permite usar el texto por etiquetas). Por ejemplo: Pega el texto1. Pega el Texto2. Lee el texto2. Borra el texto2. Edita el Texto1. ...

#### general

- Avisar de chuncks largos (para que el usuario sepa que el tts continua) o usar chunks exactos para tts.

- Testear autonicio tras reboot una vez instalada y configurada (que funcione sin problemas, testear esto también en android moderno)

- Añadir mensaje de bienvenida corto a vocalia indicando que se cambia a voz inteligente. Cuando se cambia a Voz inteligente por primera vez, mensaje de bienvenida completo (solo una vez por instalación), indicando como usar el software por voz)

- Revisar como añadir tests (test suite) (para no oerder funcionalidad), complejo, ya que requiere interaccion por voz.

- La app base debe correr sin necesidad de darle al botón de iniciar servicio. 

- Modo de escucha continuo (por activación por voz, no por defecto) (para no tener que decir "asistente" cada vez que se quiera instruir).

- Remplazar "asistente" por una keyword mas robusta (para evitar llamadas accidentales).

- Eliminar/Limitar logs en prod, (logs minimos internos para feedback con bajo consumo de recursos y escrituras) (quizas un pequeño buffer en ram en vez de en disco con volcado cada X tiempo)

- Mirar el tema de como interactua todo con la pantalla/dispositivo bloqueado y cada una de sus fases en distintos dispositivos.

- Añadir "contraseña" (opcional) para usar Vocalia.

- Añadir confirmación robusta (con pin opcional) para tareas sensitivas. (Modo Root, similar a como funciona SUDO y SUDO SU en Linux)

- Cuabdo se cambie a voz inteligente, que el tts diga, espera unos segundos...

- Añadir "repite el último mensaje/tts"

- Test other voices (añadir "cambia voz" para iterar entre ellas o "cambia voz a $VOZ")

- Que el TTS lea los alias en viz alto para las apps instaladas

- Que permits añadir apps con alias al listado (dificil)

- Grammar in 1 place (fuck spaguetti)

- Abuse cache in worflow to reduce build times

- Test if worth to embeed unzip on the apk to reduce apk size and decompress on runtime

- Design icons, etc

- Fastlanes generated from github actions to push for fdroid

- Permitir al usuario final definir comandos (para ello se necesita el modo dictado). Creo que el modelo actual no es lo suficientemente potente para el modo dictado, por ejemplo no se podrá programar con él. Hay que pensar en distintas soluciones. Una podría ser permitir configurar una app como gemini api u ollama cloud, para que esta sea la que interprere el audio. Solo per request, ya que no requiere de internet la app (funciona offline). Aqui debería ser ingenioso para poder lograr el modo dictado con un modelo pequeño. Quizás una tabla enorme con palabras clave, letras, confirmación (extremadamente complejo e impreciso. Hay que evaluar muchas alteenativas inteligentes y robustas de diseño)
