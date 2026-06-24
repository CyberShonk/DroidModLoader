#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $1"
  exit 1
}

check_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "Missing required file: $path"
}

check_contains() {
  local file="$1"
  local text="$2"

  grep -Fq "$text" "$file" || fail "$file does not contain required text: $text"
}

echo "Checking required documentation files..."

check_file "README.md"
check_file "ROADMAP.md"

check_file "docs/index.md"
check_file "docs/vision.md"
check_file "docs/requirements.md"
check_file "docs/architecture.md"
check_file "docs/architecture/source-map.md"
check_file "docs/decisions.md"
check_file "docs/testing.md"
check_file "docs/release-checklist.md"
check_file "docs/user-guide.md"
check_file "docs/troubleshooting.md"
check_file "docs/glossary.md"

check_file "docs/process/development-loop.md"
check_file "docs/process/git-workflow.md"
check_file "docs/process/roadmap-vs-tasks.md"
check_file "docs/process/versioning.md"

check_file "docs/tasks/task-template.md"
check_file "docs/tasks/current-priorities.md"
check_file "docs/tasks/backlog.md"

check_file "docs/assets/brand-assets.md"

check_file "releases/changelog.md"
check_file "releases/templates/release-notes-template.md"
check_file "releases/templates/apk-upload-checklist.md"

echo "Checking README documentation links..."
check_contains "README.md" "docs/index.md"
check_contains "README.md" "docs/user-guide.md"
check_contains "README.md" "docs/troubleshooting.md"
check_contains "README.md" "docs/glossary.md"
check_contains "README.md" "CURRENT_STATUS.md"
check_contains "README.md" "ROADMAP.md"
check_contains "README.md" "releases/changelog.md"

echo "Checking docs index links..."

check_contains "docs/index.md" "vision.md"
check_contains "docs/index.md" "requirements.md"
check_contains "docs/index.md" "architecture.md"
check_contains "docs/index.md" "architecture/source-map.md"
check_contains "docs/index.md" "decisions.md"
check_contains "docs/index.md" "testing.md"
check_contains "docs/index.md" "release-checklist.md"
check_contains "docs/index.md" "process/development-loop.md"
check_contains "docs/index.md" "process/git-workflow.md"
check_contains "docs/index.md" "process/roadmap-vs-tasks.md"
check_contains "docs/index.md" "process/versioning.md"
check_contains "docs/index.md" "tasks/task-template.md"
check_contains "docs/index.md" "tasks/current-priorities.md"
check_contains "docs/index.md" "tasks/backlog.md"
check_contains "docs/index.md" "assets/brand-assets.md"
check_contains "docs/index.md" "../releases/changelog.md"

echo "Documentation structure check passed."