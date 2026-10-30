# TODO

Cosas concretas que añadir/parchear/evaluar.

- La app base debe correr sin necesidad de darle al botón de iniciar servicio. 

- Modo de escucha continuo (por activación por voz, no por defecto) (para no tener que decir "asistente" cada vez que se quiera instruir).

- Remplazar "asistente" por una keyword mas robusta (para evitar llamadas accidentales).

- Eliminar/Limitar logs en prod, (logs minimos internos para feedback con bajo consumo de recursos y escrituras) (quizas un pequeño buffer en ram en vez de en disco con volcado cada X tiempo)

- Mirar el tema de como interactua todo con la pantalla/dispositivo bloqueado y cada una de sus fases en distintos dispositivos.

- Añadir "contraseña" (opcional) para usar Vocalia.

- Añadir confirmación robusta (con pin opcional) para tareas sensitivas. (Modo Root, similar a como funciona SUDO y SUDO SU en Linux)

- Añadir "repite el último mensaje/tts"

- Test other voices

- Que el TTS lea los alias en viz alto para las apps instaladas

- Que permits añadir apps con alias al listado (dificil)

- Grammar in 1 place (fuck spaguetti)

- Abuse cache in worflow to reduce build times

- Test if worth to embeed unzip on the apk to reduce apk size and decompress on runtime

- Design icons, etc

- Fastlanes generated from github actions to push for fdroid
