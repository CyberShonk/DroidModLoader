# Contributing to Droid Mod Loader

Focused bug reports, compatibility findings, documentation corrections, tests,
and code changes are welcome.

## Before changing code

- Keep the change narrow.
- Preserve existing behavior outside the stated goal.
- Treat archive extraction, storage paths, profile isolation, deployment, and recovery as areas with higher risk.
- Do not weaken file safety checks to improve compatibility.

## Development setup

Use JDK 21, as CI does, and install the Android SDK required by
`app/build.gradle.kts` (currently API `36.1`). Configure the SDK path locally;
do not commit `local.properties`.

Run commands from the repository root using the included Gradle wrapper.

## Validation

For normal code changes, evaluate and run the relevant checks:

```bash
git diff --check
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./tools/check-project.sh
./tools/check-docs.sh
```

`check-project.sh` checks the local environment and staged files and also runs
JVM tests. `check-docs.sh` checks the public documentation structure.

Use focused tests first when useful. Changes that affect storage, archives,
deployment, Android lifecycle, or behavior that depends on a specific device
should be validated on a real device when practical.

Do not treat warnings as failures unless they represent a new problem introduced
by the change.

## APK build receipts

`assembleDebug` and `assembleRelease` automatically embed
`assets/dml-build-provenance.json` in each APK and write
`<apk-name>.receipt.json` beside it in `app/build/outputs/apk/<variant>/`.
Keep the receipt with any APK used for testing or distribution. CI retains debug
receipts separately; retaining every development APK is not required.

Receipts identify the APK by SHA-256 and size and contain its embedded source,
version, variant, UTC build time, toolchain, and optional GitHub Actions run URL.
They also report signature verification and SHA-256 digests of signing certificates;
unsigned release outputs are explicitly marked unsigned. No keys or credentials
are included. Signing or otherwise changing an APK invalidates its old receipt.

The full commit identifies the source base. `dirty` includes tracked changes and
untracked files that are not ignored; their digests distinguish local edits but do not
preserve those edits. Use clean committed source for distributed releases. Git
history must remain available separately; receipts are provenance, not source
snapshots or guarantees that rebuilds produce identical bytes.

Provenance is generated from build inputs and packaged into the APK. Receipt
identity is read back from that APK, never relabeled from the current checkout.
A build with unchanged inputs preserves its original embedded build time. A receipt does
not imply that unit tests or GUI checks passed: record validation separately,
including the SHA-256 of the exact APK installed for GUI testing.

Check receipt integrity against the APK and Gradle output metadata with:

```bash
python3 tools/check-apk-receipts.py app/build/outputs/apk/debug/*.apk
```

This checks hashes and metadata consistency, not cryptographic trust in the
receipt itself. The build task separately verifies APK signatures.

## Release note drafts

`tools/prepare-release.sh` copies the unreleased changelog into draft release notes,
dates that section, and starts a fresh `Unreleased` section. It does not build,
sign, upload, or publish anything.

```bash
./tools/prepare-release.sh v0.8.0-beta \
  --previous-tag v0.7.0-beta \
  --confirmed-offline-code 3 \
  --offline-evidence 'Published APK code checked; no newer APK distributed'
```

These are example baseline values, not a substitute for checking the release history.
Confirm the highest code distributed for the release application ID, including
privately shared candidates. This command does not query GitHub or Nexus.
It chooses one above both that confirmed code and the current `version.properties`
code. An optional numeric second argument overrides the choice only when greater
than both values. Codes must be between 1 and 2100000000.

Run from a clean `release/<version>` branch in the main working tree, not a linked
worktree. The previous published tag must match its version file and be an ancestor
with commits after it. Python 3, `flock`, full Git history and a GitHub `origin` are
required. Concurrent preparation is refused; separate clones require coordination.
Retain the printed baseline summary with your release records.

Preparation writes the reserved code once. Repeating it refuses rather than bumping
again. A partial write can leave changed files; inspect them before restoring inputs
or continuing. The command does not automatically roll back interrupted preparation.

Each change gets an inline link to the commit that last changed its changelog
line. If that is not the intended implementation commit, add a trailing override
such as `<!-- commits: abc1234, def5678 -->` to the entry's bullet line. References
must resolve to commits in the current history. Overrides are hidden in the
generated notes.
Review the summaries, links, known issues and upgrade guidance before publication.

## Pull requests

Include:

- what changed;
- why it changed;
- behavior visible to users;
- risk and safety implications;
- automated validation performed; and
- any remaining manual checks.

Keep release artifacts, signing material, credentials, local configuration, and
temporary files out of commits.

## Documentation

Update the README, user guide, troubleshooting guide, changelog, or release notes
when a change affects information users need to install, configure, use, or
troubleshoot DML.
