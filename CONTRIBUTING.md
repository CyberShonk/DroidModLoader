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
