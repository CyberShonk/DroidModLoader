#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $1"
  exit 1
}

echo "Checking project from repo root..."

[[ -f "settings.gradle.kts" || -f "settings.gradle" ]] || fail "Run this script from the repo root."
[[ -f "gradlew" ]] || fail "Missing Gradle wrapper: gradlew"

if [[ ! -x "gradlew" ]]; then
  fail "gradlew is not executable. Run: chmod +x gradlew && git update-index --chmod=+x gradlew"
fi

if ! command -v java >/dev/null 2>&1; then
  fail "java is not available. Set JAVA_HOME to Android Studio JBR or install a JDK."
fi

echo "Java:"
java -version

echo "Checking for bad staged files..."

if git diff --cached --name-only | grep -Ei '\.(apk|aab)$' >/dev/null; then
  fail "APK/AAB files are staged. Do not commit release artifacts."
fi

if git diff --cached --name-only | grep -Ei '(\.save|\.bak|\.tmp|~)$' >/dev/null; then
  fail "Backup/temp files are staged."
fi

if git diff --cached --name-only | grep -Ei '(^|/)(local\.properties|.*keystore.*|.*\.jks|.*\.keystore)$' >/dev/null; then
  fail "Local config or signing key files are staged."
fi

#echo "Running docs check..."
#./tools/check-docs.sh

echo "Running JVM unit tests..."
./gradlew testDebugUnitTest

echo "Project check passed."