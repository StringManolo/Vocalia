#!/usr/bin/env bash
# Genera un único archivo con el contexto del proyecto para pasárselo a un LLM
cd "$(dirname "$0")" || exit 1
OUT="vocalia_context.txt"
LOG_LINES=300   # últimas líneas del log de debug a incluir

PRUNE=( \( -path ./.git -o -path '*/build' -o -path ./.gradle
  -o -path ./app/src/main/assets/tts
  -o -path ./app/src/main/assets/vosk-model-small-es-0.42
  -o -path ./app/libs -o -path ./app/src/main/jniLibs
  -o -path ./fastlane -o -path ./debug \) -prune -o )

{
echo "# CONTEXTO DEL PROYECTO: $(basename "$PWD")"
echo "# Generado: $(date '+%F %T')"
echo "# Nota: Android (Kotlin) con sherpa-onnx (TTS Piper), Vosk (STT) y espeak-ng."
echo

echo "################ ÁRBOL (filtrado) ################"
if command -v tree >/dev/null; then
  tree -a --noreport -I '.git|build|.gradle|espeak-ng-data|vosk-model-small-es-0.42|jniLibs|images|mipmap-*|*.png' .
else
  find . "${PRUNE[@]}" -print | sort
fi
echo

dump() {
  echo; echo "===== FILE: $1 ====="
  cat "$1"
}

echo "################ DOCUMENTACIÓN ################"
for f in README.md ROADMAP.md TODO.md bash/README.md; do [ -f "$f" ] && dump "$f"; done

echo; echo "################ CI / BUILD ################"
for f in .github/workflows/*.yml .github/workflows/*.yaml build.gradle settings.gradle \
         app/build.gradle gradle.properties app/proguard-rules.pro .gitignore; do
  [ -f "$f" ] && dump "$f"
done

echo; echo "################ CÓDIGO Y RECURSOS ################"
find . "${PRUNE[@]}" -type f \( \
    -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name 'wake_grammar.json' \
    -o -path './bash/*' \) ! -name 'README.md' ! -name "$OUT" -print | sort | \
while read -r f; do dump "$f"; done

if [ -f debug/termux_cat_app_logs.txt ]; then
  echo; echo "################ ÚLTIMAS $LOG_LINES LÍNEAS DEL LOG ################"
  tail -n "$LOG_LINES" debug/termux_cat_app_logs.txt
fi
} > "$OUT"

echo "Listo: $OUT  ($(wc -c < "$OUT") bytes, ~$(( $(wc -c < "$OUT") / 4 )) tokens aprox)"
