#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $1"
  exit 1
}

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
  fail "Usage: ./tools/prepare-release.sh v0.x.y-beta"
fi

if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-beta)?$ ]]; then
  fail "Version must look like v0.5.5-beta or v1.0.0"
fi

NOTES_DIR="releases/notes"
TEMPLATE="releases/templates/release-notes-template.md"
OUTPUT="$NOTES_DIR/$VERSION.md"

[[ -f "$TEMPLATE" ]] || fail "Missing template: $TEMPLATE"

mkdir -p "$NOTES_DIR"

if [[ -f "$OUTPUT" ]]; then
  fail "Release notes already exist: $OUTPUT"
fi

cp "$TEMPLATE" "$OUTPUT"

# Portable replacement.
python3 - "$OUTPUT" "$VERSION" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
version = sys.argv[2]

text = path.read_text()
text = text.replace("v0.x.y-beta", version)
path.write_text(text)
PY

echo "Created release notes:"
echo "$OUTPUT"
echo
echo "Next manual checks:"
echo "1. Update app/build.gradle.kts versionName and versionCode."
echo "2. Update releases/changelog.md."
echo "3. Run ./tools/check-project.sh."
echo "4. Run ./gradlew assembleRelease."
echo "5. Use docs/release-checklist.md before upload."