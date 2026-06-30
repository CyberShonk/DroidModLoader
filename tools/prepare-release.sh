#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

REPO_ROOT="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." &&
    pwd
)"

cd "$REPO_ROOT"

VERSION="${1:-}"
NEW_VERSION_CODE="${2:-}"

if [[ -z "$VERSION" || -z "$NEW_VERSION_CODE" ]]; then
  fail \
    "Usage: ./tools/prepare-release.sh " \
    "v0.x.y-beta VERSION_CODE"
fi

if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-beta)?$ ]]; then
  fail \
    "Version must look like v0.7.0-beta or v1.0.0."
fi

if [[ ! "$NEW_VERSION_CODE" =~ ^[0-9]+$ ]]; then
  fail "VERSION_CODE must be a positive integer."
fi

if (( NEW_VERSION_CODE <= 0 )); then
  fail "VERSION_CODE must be greater than zero."
fi

VERSION_FILE="version.properties"
NOTES_DIR="releases/notes"
TEMPLATE="releases/templates/release-notes-template.md"
OUTPUT="$NOTES_DIR/$VERSION.md"
EXPECTED_BRANCH="release/$VERSION"

[[ -f "$VERSION_FILE" ]] ||
  fail "Missing version file: $VERSION_FILE"

[[ -f "$TEMPLATE" ]] ||
  fail "Missing release-notes template: $TEMPLATE"

CURRENT_BRANCH="$(
  git symbolic-ref --quiet --short HEAD
)" || fail "Release preparation cannot run from detached HEAD."

if [[ "$CURRENT_BRANCH" != "$EXPECTED_BRANCH" ]]; then
  fail \
    "Release preparation must run from $EXPECTED_BRANCH; " \
    "current branch is $CURRENT_BRANCH."
fi

if [[ -n "$(git status --porcelain)" ]]; then
  fail \
    "The working tree must be clean before release preparation."
fi

if git rev-parse \
    --quiet \
    --verify \
    "refs/tags/$VERSION" \
    >/dev/null
then
  fail "Git tag already exists: $VERSION"
fi

CURRENT_VERSION_NAME="$(
  awk -F= '
    $1 == "VERSION_NAME" {
      print $2
    }
  ' "$VERSION_FILE" |
    tr -d '\r'
)"

CURRENT_VERSION_CODE="$(
  awk -F= '
    $1 == "VERSION_CODE" {
      print $2
    }
  ' "$VERSION_FILE" |
    tr -d '\r'
)"

if [[ -z "$CURRENT_VERSION_NAME" ]]; then
  fail "VERSION_NAME is missing from $VERSION_FILE."
fi

if [[ ! "$CURRENT_VERSION_CODE" =~ ^[0-9]+$ ]]; then
  fail \
    "Current VERSION_CODE in $VERSION_FILE is invalid."
fi

if (( NEW_VERSION_CODE <= CURRENT_VERSION_CODE )); then
  fail \
    "VERSION_CODE must be greater than the current value " \
    "$CURRENT_VERSION_CODE."
fi

if [[ -f "$OUTPUT" ]]; then
  fail "Release notes already exist: $OUTPUT"
fi

RELEASE_DATE="$(date +%F)"

printf '%s\n' \
  "VERSION_NAME=$VERSION" \
  "VERSION_CODE=$NEW_VERSION_CODE" \
  > "$VERSION_FILE"

mkdir -p "$NOTES_DIR"
cp "$TEMPLATE" "$OUTPUT"

sed -i \
  -e "s/v0\\.x\\.y-beta/$VERSION/g" \
  -e "s/YYYY-MM-DD/$RELEASE_DATE/g" \
  "$OUTPUT"

printf '\nRelease preparation created:\n'
printf '  Version: %s\n' "$VERSION"
printf '  Version code: %s\n' "$NEW_VERSION_CODE"
printf '  Previous version: %s\n' "$CURRENT_VERSION_NAME"
printf '  Previous version code: %s\n' "$CURRENT_VERSION_CODE"
printf '  Release notes: %s\n' "$OUTPUT"

printf '\nNext steps:\n'
printf '1. Review version.properties.\n'
printf '2. Complete %s.\n' "$OUTPUT"
printf '3. Update releases/changelog.md.\n'
printf '4. Update current-status and release documentation.\n'
printf '5. Run ./tools/check-project.sh.\n'
printf '6. Commit the release-preparation changes.\n'
printf '7. Build and test the signed release APK.\n'