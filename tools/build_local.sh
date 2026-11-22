#!/usr/bin/env bash
# Local, Gradle-free build used to prove the sources produce an installable APK when AGP/Google Maven are not reachable.
# Needs: kotlinc (2.x), android.jar (API 35), aapt, dalvik-exchange (dx 10), zipalign, apksigner, keytool.
# The Gradle build (./gradlew :app:assembleRelease, see .github/workflows/build.yml) is the primary build path.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
ANDROID_JAR="${ANDROID_JAR:?set ANDROID_JAR to an android.jar (API 35, used by kotlinc)}"
AAPT_JAR="${AAPT_JAR:?set AAPT_JAR to an OLDER android.jar (API 29): the Debian aapt cannot read newer resource tables}"
OUT="${OUT:-$ROOT/out/local}"
VERSION_NAME="${VERSION_NAME:-0.1.0}"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "== kotlinc"
"$KOTLINC" -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -include-runtime \
  -cp "$ANDROID_JAR" -d "$OUT/all.jar" $(find "$ROOT/core/src/main" "$ROOT/app/src/main" -name '*.kt')
zip -q -d "$OUT/all.jar" 'META-INF/versions/*' 'module-info.class' 'META-INF/*.kotlin_module' 2>/dev/null || true

echo "== dex"
dalvik-exchange --dex --min-sdk-version=29 --output="$OUT/classes.dex" "$OUT/all.jar"

echo "== aapt"
# AGP injects the package from `namespace`; plain aapt needs it in the manifest
# Local-only manifest tweaks for the old aapt: add package, use FGS type dataSync (specialUse needs API 34 tables), drop <property>.
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import re,sys
t=open(sys.argv[1]).read()
t=t.replace("<manifest ","<manifest package=\"dev.nyushu.optimizer\" ",1)
t=t.replace("specialUse","dataSync")
t=re.sub(r"<property.*?/>","",t,flags=re.S)
t=t.replace("FOREGROUND_SERVICE_SPECIAL_USE","FOREGROUND_SERVICE_DATA_SYNC")
open(sys.argv[2],"w").write(t)
PY
aapt package -f -M "$OUT/AndroidManifest.xml" -S "$ROOT/app/src/main/res" -I "$AAPT_JAR" \
  --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name "$VERSION_NAME" \
  -F "$OUT/unsigned.apk"
( cd "$OUT" && aapt add -f unsigned.apk classes.dex >/dev/null )

echo "== align + sign (debug key)"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="$OUT/debug.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Nyushu Optimizer Debug" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 \
  --out "$OUT/nyushu-optimizer-local-debug.apk" "$OUT/aligned.apk"
apksigner verify --verbose "$OUT/nyushu-optimizer-local-debug.apk" | head -5
sha256sum "$OUT/nyushu-optimizer-local-debug.apk"
