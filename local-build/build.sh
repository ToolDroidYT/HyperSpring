#!/usr/bin/env bash
# Compila el módulo sin Android SDK/Gradle (útil donde Google Maven no está accesible).
# Necesita: JDK 17+, android.jar (API 35), jadx (trae D8), apktool (trae aapt2), uber-apk-signer.
# Uso: TOOLS=/ruta/a/herramientas ./local-build/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="${TOOLS:-$HOME/tools}"
ANDROID_JAR="$TOOLS/android.jar"
JADX_JAR="$(ls "$TOOLS"/jadx/lib/jadx-*-all.jar | head -1)"
OUT=local-build/out
VFILE=app/src/main/java/com/icehome/up/Version.java
VNAME=$(sed -n 's/.*NAME = "\(.*\)";/\1/p' "$VFILE")
VCODE=$(sed -n 's/.*CODE = \([0-9]*\);/\1/p' "$VFILE")
rm -rf "$OUT" && mkdir -p "$OUT"/{stub,classes,dex,res,apk}

# 1) Stubs de las dos API de Xposed (solo para compilar; NO van al APK)
#    - xposed-stub: API clásica (93), para el motor antiguo.
#    - libxposed-stub: libxposed (102), para el motor moderno. Usa Java 17 (sealed, record).
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/stub" \
  $(find local-build/xposed-stub -name '*.java') 2>&1 | grep -v "^warning\|^Note\|^[0-9] warning" || true
javac -nowarn -encoding UTF-8 --release 17 -cp "$ANDROID_JAR" -d "$OUT/stub" \
  $(find local-build/libxposed-stub -name '*.java') 2>&1 | grep -v "^warning\|^Note\|^[0-9] warning" || true
[ -f "$OUT/stub/io/github/libxposed/api/XposedModule.class" ] || { echo "ERROR: no compila el stub de libxposed"; exit 1; }

# 2) Código del módulo
# Comprobar los shaders AGSL antes de compilar (si hay skia-python)
if python3 -c "import skia" 2>/dev/null && [ -f "local-build/agsl_check.py" ]; then
  for f in $(grep -rl "half4 main" app/src/main/java/com/icehome/up); do
    python3 local-build/agsl_check.py "$f" || { echo "ERROR: shader AGSL en $f"; exit 1; }
  done
fi
if ! javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$OUT/stub" \
  -d "$OUT/classes" $(find app/src/main/java -name '*.java') 2> "$OUT/javac.log"; then
  grep -v "^warning\|^Note\|^[0-9] warning" "$OUT/javac.log"; echo "ERROR: javac ha fallado"; exit 1
fi

[ -n "$(find "$OUT/classes" -name '*.class' 2>/dev/null)" ] || { echo "ERROR: javac ha fallado"; exit 1; }

# 3) DEX con D8 (incluido en jadx)
java -cp "$JADX_JAR" com.android.tools.r8.D8 --release --min-api 30 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# 4) Recursos + manifiesto con aapt2 (incluido en apktool)
# apktool 2.x lo llama aapt2_64; apktool 3.x, aapt2.
AAPT2="$OUT/aapt2"
unzip -p "$TOOLS/apktool.jar" prebuilt/linux/aapt2_64 > "$AAPT2" 2>/dev/null || true
[ -s "$AAPT2" ] || unzip -p "$TOOLS/apktool.jar" prebuilt/linux/aapt2 > "$AAPT2"
chmod +x "$AAPT2"
"$AAPT2" compile --dir app/src/main/res -o "$OUT/res/res.zip"
# Gradle inyecta el package desde "namespace"; aquí lo añadimos a mano
sed 's#<manifest #<manifest package="com.icehome.up" #' app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 30 --target-sdk-version 35 \
  --version-code "$VCODE" --version-name "$VNAME" -A app/src/main/assets \
  -o "$OUT/apk/unsigned.apk" "$OUT/res/res.zip"
(cd "$OUT/dex" && zip -q -j ../apk/unsigned.apk classes.dex)

# 5) Dos APK con el mismo código:
#    - HyperSpring.apk: lleva META-INF/xposed (libxposed, API 102). Para Vector y LSPosed actuales.
#    - HyperSpring-legacy.apk: sin META-INF/xposed, solo API clásica (93). Para LSPosed antiguos,
#      que al ver META-INF/xposed intentarían cargarlo con la API 100 (ya retirada) y fallarían.
mkdir -p "$OUT/apk/legacy" "$OUT/apk/modern"
cp "$OUT/apk/unsigned.apk" "$OUT/apk/legacy/HyperSpring-legacy.apk"
mv "$OUT/apk/unsigned.apk" "$OUT/apk/modern/HyperSpring.apk"
(cd app/src/main/resources && zip -q -r "../../../../$OUT/apk/modern/HyperSpring.apk" META-INF/xposed)

# 6) Alinear y firmar (clave de depuración de uber-apk-signer)
for v in modern legacy; do
  java -jar "$TOOLS/uber-apk-signer.jar" -a "$OUT/apk/$v" -o "$OUT/apk/$v/signed" --allowResign >/dev/null
done
cp "$OUT"/apk/modern/signed/*.apk "$OUT/HyperSpring.apk"
cp "$OUT"/apk/legacy/signed/*.apk "$OUT/HyperSpring-legacy.apk"
echo "OK -> $OUT/HyperSpring.apk (API 102) y $OUT/HyperSpring-legacy.apk (API 93)"
