#!/usr/bin/env bash
# Runs the :core JUnit tests without Gradle (kotlinc + JUnit 4.13.2). Same sources, same tests as `./gradlew :core:test`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
JUNIT="${JUNIT:-/usr/share/java/junit4.jar}"
HAMCREST="${HAMCREST:-/usr/share/java/hamcrest-core.jar}"
KBIN="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")"
STDLIB="${KOTLIN_STDLIB:-$KBIN/../lib/kotlin-stdlib.jar}"
OUT="$ROOT/out/tests"; rm -rf "$OUT"; mkdir -p "$OUT"
"$KOTLINC" -jvm-target 17 -d "$OUT/core.jar" $(find "$ROOT/core/src/main" -name '*.kt')
CP="$OUT/core.jar:$JUNIT:$HAMCREST:$STDLIB"
"$KOTLINC" -jvm-target 17 -cp "$CP" -d "$OUT/tests.jar" $(find "$ROOT/core/src/test" -name '*.kt')
CLASSES=$(grep -rhoE "^class [A-Za-z0-9]+Test\b" "$ROOT/core/src/test" | awk '{print "dev.nyushu.optimizer.core."$2}')
java -cp "$OUT/tests.jar:$CP" org.junit.runner.JUnitCore $CLASSES
