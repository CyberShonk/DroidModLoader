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
They also report signature verification and signing-certificate SHA-256 digests;
unsigned release outputs are explicitly marked unsigned. No keys or credentials
are included. Signing or otherwise changing an APK invalidates its old receipt.

The full commit identifies the source base. `dirty` includes tracked changes and
non-ignored untracked files; their digests distinguish local edits but do not
preserve those edits. Use clean committed source for distributed releases. Git
history must remain available separately; receipts are provenance, not source
snapshots or guarantees of byte-identical rebuilds.

Provenance is generated from build inputs and packaged into the APK. Receipt
identity is read back from that APK, never relabeled from the current checkout.
An up-to-date build preserves its original embedded build time. A receipt does
not imply that unit tests or GUI checks passed: record validation separately,
including the SHA-256 of the exact APK installed for GUI testing.

Check receipt integrity against the APK and Gradle output metadata with:

```bash
python3 tools/check-apk-receipts.py app/build/outputs/apk/debug/*.apk
```

This checks hashes and metadata consistency, not cryptographic trust in the
receipt itself. The build task separately verifies APK signatures.

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
