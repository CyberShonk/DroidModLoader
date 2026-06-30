# Droid Mod Loader Versioning

This document defines Droid Mod Loader version rules and the release-version workflow.

## Canonical Version Source

The repository-root `version.properties` file is the single source of truth for Android version metadata:

```properties
VERSION_NAME=v0.6.0-beta
VERSION_CODE=2
```

`app/build.gradle.kts` reads these values during Gradle configuration. Do not duplicate release versions in Gradle source or UI code.

The app displays `BuildConfig.VERSION_NAME`, so the visible version and APK manifest version come from the same source.

## Version Format

Use this format before stable 1.0:

```text
v0.x.y-beta
```

| Version Change                 | Meaning                                    |
|--------------------------------|--------------------------------------------|
| `v0.5.4-beta` to `v0.5.5-beta` | Bug fixes, docs, small improvements        |
| `v0.5.x-beta` to `v0.6.0-beta` | New feature area or larger behavior change |
| `v1.0.0`                       | First stable public release                |
| `v1.0.1`                       | Stable hotfix                              |
| `v1.1.0`                       | Stable feature release                     |

## Version Code Rules

`VERSION_CODE` must:

- be a positive integer;
- increase for every publicly distributed APK;
- never be reused for a different release build;
- remain greater than the version code of every APK users may upgrade from.

## Preparing a Release Version

Run release preparation from the matching release branch:

```bash
./tools/prepare-release.sh v0.7.0-beta 3
```

The branch must be named:

```text
release/v0.7.0-beta
```

The script:

- requires a clean working tree;
- validates the release branch and version format;
- rejects an existing release tag;
- requires a greater version code;
- updates `version.properties`;
- creates dated release notes under `releases/notes/`.

The script does not build, sign, commit, tag, push, or publish a release. Those remain explicit review steps.

## Verifying a Built APK

After building, verify the APK instead of trusting its filename:

```bash
apkanalyzer manifest version-name app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest version-code app/build/outputs/apk/release/app-release.apk
```

The reported values must match `version.properties` and the intended public release.
