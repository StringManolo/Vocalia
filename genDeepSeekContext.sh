cd /root/vocalia

: > deepseek_context.txt

FILES=(
  app/src/main/AndroidManifest.xml
  app/build.gradle.kts
  app/build.gradle
  app/src/main/java/io/github/stringmanolo/vocalia/VocaliaApp.kt
  app/src/main/java/io/github/stringmanolo/vocalia/MainActivity.kt
  app/src/main/java/io/github/stringmanolo/vocalia/VocaliaService.kt
  app/src/main/java/io/github/stringmanolo/vocalia/VocaliaAccessibilityService.kt
  app/src/main/java/io/github/stringmanolo/vocalia/VoskEngine.kt
  app/src/main/java/io/github/stringmanolo/vocalia/core/CommandParser.kt
  app/src/main/java/io/github/stringmanolo/vocalia/core/VocaliaCore.kt
  app/src/main/java/io/github/stringmanolo/vocalia/core/AppLauncher.kt
  app/src/main/java/io/github/stringmanolo/vocalia/tts/TtsManager.kt
  app/src/main/java/io/github/stringmanolo/vocalia/tts/TtsEngine.kt

)

for f in "${FILES[@]}"; do
  if [ -f "$f" ]; then
    printf '\n===== %s =====\n' "$f" >> deepseek_context.txt
    cat "$f" >> deepseek_context.txt
  else
    printf '\n===== %s (MISSING) =====\n' "$f" >> deepseek_context.txt
  fi
done

wc -l deepseek_context.txt
