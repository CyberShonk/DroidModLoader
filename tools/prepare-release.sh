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

command -v python3 >/dev/null 2>&1 || fail "python3 is required to prepare release notes."

python3 - "$VERSION" "$NEW_VERSION_CODE" "$RELEASE_DATE" "$OUTPUT" "$TEMPLATE" <<'PY'
from pathlib import Path
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.PIPE).strip()


def next_fence(line, fence):
    if fence:
        closing = r" {0,3}" + fence[0] + "{" + str(len(fence)) + r",}\s*"
        return None if re.fullmatch(closing, line) else fence
    opening = re.match(r" {0,3}(`{3,}|~{3,})", line)
    return opening[1] if opening else None


version, code, date, output, template_path = sys.argv[1:]
changelog_path = Path("releases/changelog.md")
try:
    for path in (changelog_path, Path("version.properties"), Path(template_path), Path(output)):
        if not path.resolve().is_relative_to(Path.cwd().resolve()):
            raise ValueError("Release input and output paths must stay inside the repository.")
    changelog = changelog_path.read_bytes().decode("utf-8")
    # Ignore headings inside fenced examples when finding release boundaries.
    headings = []
    offset = 0
    fence = None
    for raw in changelog.splitlines(keepends=True):
        line = raw.rstrip("\r\n")
        if not fence:
            heading = re.fullmatch(r"## (.+)", line)
            if heading:
                headings.append((offset, offset + len(line), heading[1]))
        fence = next_fence(line, fence)
        offset += len(raw)
    if fence:
        raise ValueError("Changelog contains an unclosed code fence.")
    pending = [h for h in headings if h[2].lower() == "unreleased" or h[2].lower().endswith(" - unreleased")]
    if len(pending) != 1 or pending[0] != headings[0]:
        raise ValueError("Changelog must begin with exactly one unreleased section.")
    start, header_end, title = pending[0]
    if title.lower() != "unreleased" and title != f"{version} - Unreleased":
        raise ValueError("Unreleased changelog version does not match the requested release.")
    if any(h[2] == version or h[2].startswith(version + " - ") for h in headings[1:]):
        raise ValueError("Changelog already contains this release version.")
    end = headings[1][0] if len(headings) > 1 else len(changelog)
    body = changelog[header_end:end].strip("\r\n")
    content = re.sub(r"<!--.*?-->", "", body, flags=re.S)
    if not any(line.strip() and not line.lstrip().startswith("#") and line.strip() not in ("-", "*", "+")
               for line in content.splitlines()):
        raise ValueError("Unreleased changelog has no changes to release.")

    if git("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Full Git history is required to link changelog entries.")
    remote = git("remote", "get-url", "origin")
    repository = re.fullmatch(
        r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)"
        r"([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*?)(?:\.git)?/?", remote
    )
    if not repository:
        raise ValueError("origin must identify a public GitHub repository without credentials.")
    base = "https://github.com/" + repository[1]
    blame = {}
    for line in git("blame", "--line-porcelain", "HEAD", "--", str(changelog_path)).splitlines():
        match = re.fullmatch(r"([0-9a-f]{40}|[0-9a-f]{64}) \d+ (\d+)(?: \d+)?", line)
        if match:
            blame[int(match[2])] = match[1]
    linked = []
    fence = None
    first_line = changelog[:header_end].count("\n") + 1
    for number, raw in enumerate(changelog[header_end:end].splitlines(keepends=True), first_line):
        line = raw.rstrip("\r\n")
        entry = re.match(r"^[-*+] \S", line)
        override = re.search(r"\s*<!-- commits:\s*(.*?)\s*-->\s*$", line)
        if not fence and "<!-- commits:" in line and not (entry and override):
            raise ValueError("Place commit overrides at the end of the entry's bullet line.")
        if not fence and entry:
            refs = re.split(r"[\s,]+", override[1].strip()) if override else [blame[number]]
            hashes = []
            for ref in refs:
                if not re.fullmatch(r"[0-9a-fA-F]{7,64}", ref):
                    raise ValueError(f"Invalid commit reference on changelog line {number}.")
                sha = git("rev-parse", "--verify", ref + "^{commit}")
                git("merge-base", "--is-ancestor", sha, "HEAD")
                if sha not in hashes:
                    hashes.append(sha)
            if override:
                line = line[:override.start()].rstrip()
            links = ", ".join(f"[`{sha[:7]}`]({base}/commit/{sha})" for sha in hashes)
            linked.append(f"{line} ({links})" + raw[len(raw.rstrip("\r\n")):])
        else:
            linked.append(raw)
        fence = next_fence(line, fence)
    linked_body = "".join(linked).strip("\r\n")
    template = Path(template_path).read_text(encoding="utf-8")
    changes_start = template.index("\n## Added\n")
    changes_end = template.index("\n## Upgrade Notes\n", changes_start)
    prelude = template[:changes_start].replace("v0.x.y-beta", version).replace("YYYY-MM-DD", date)
    notes = prelude + "\n## Changes\n\n" + linked_body + "\n" + template[changes_end:]
    fresh = "## Unreleased\n\n### Added\n\n### Changed\n\n### Fixed\n\n### Known Issues\n\n"
    rolled = changelog[:start] + fresh + f"## {version} - {date}" + changelog[header_end:]

    # Validate every input before changing release files. A partial I/O failure
    # leaves a dirty tree that the existing preparation guard refuses to retry.
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    with Path(output).open("x", encoding="utf-8") as stream:
        stream.write(notes)
    changelog_path.write_bytes(rolled.encode("utf-8"))
    Path("version.properties").write_text(f"VERSION_NAME={version}\nVERSION_CODE={code}\n", encoding="utf-8")
except (OSError, UnicodeError, ValueError, KeyError, subprocess.CalledProcessError) as error:
    sys.exit(f"ERROR: {error}")
PY

printf '\nRelease preparation created:\n'
printf '  Version: %s\n' "$VERSION"
printf '  Version code: %s\n' "$NEW_VERSION_CODE"
printf '  Previous version: %s\n' "$CURRENT_VERSION_NAME"
printf '  Previous version code: %s\n' "$CURRENT_VERSION_CODE"
printf '  Release notes: %s\n' "$OUTPUT"

printf '\nNext steps:\n'
printf '1. Review version.properties.\n'
printf '2. Complete %s.\n' "$OUTPUT"
printf '3. Review releases/changelog.md and its fresh Unreleased section.\n'
printf '4. Update README or user documentation only if released behavior or limitations changed.\n'
printf '5. Run ./tools/check-project.sh.\n'
printf '6. Commit the release-preparation changes.\n'
printf '7. Build and test the signed release APK.\n'
