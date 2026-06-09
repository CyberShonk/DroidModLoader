# Droid Mod Loader Changelog

This file tracks user-facing release history.

## Format

Use this format for each release:

## Version - Date

### Added

- New features.

### Changed

- Behavior changes.

### Fixed

- Bug fixes.

### Known Issues

- Problems users should know about.

### Upgrade Notes

- Anything users should do before or after updating.

## v0.5.4-beta - Current

### Added

- Project vision documentation.
- Requirements documentation.
- Brand asset documentation.
- Handmade icon source tracking.
- Android launcher icon assets.
- Project documentation index.
- Architecture documentation.
- Decision log.
- Testing documentation.
- Release checklist.
- Development loop documentation.
- Pull request checklist template.
- Expanded decision log rules and core accepted decisions.
- Source map documentation for major app files and engine areas.
- JVM unit tests for path normalization, deploy file classification, mod display name cleanup, and plugin discovery.
- Starter user guide.
- Troubleshooting documentation.
- Glossary for DML and modding terms.
- Versioning documentation.
- Public release notes template.
- APK upload checklist template.
- Git workflow documentation.
- Local project check scripts.
- Documentation structure check script.
- Release notes preparation script.
- GitHub Actions CI for unit tests, debug builds, and documentation checks.
- Added unit coverage for archive metadata tracking, Nexus URL parsing, and archive metadata summaries.
- Added a Developer Tools action to print the saved archive library metadata summary without changing files.
- Added a pre-push documentation gate so project docs stay current before GitHub pushes.
- Continued release-readiness cleanup for the archive import/download metadata path.

### Changed

- README now links to project documentation.
- Expanded architecture documentation maintenance rules.
- Clarified versionName, versionCode, APK filename, and changelog expectations.
- Expanded release checklist links to release templates and versioning documentation.
- Documented Bazzite terminal setup notes for Gradle, Java/JBR, and executable Gradle wrapper usage.
- Added repository automation to catch missing docs, committed artifacts, and failing unit tests earlier.

### Fixed

- Removed accidental documentation backup file.

### Known Issues

- Release checklist is still being formalized.
- Recovery tools and unfinished deploy warnings still need more polish.

### Upgrade Notes

- Back up important game folders before testing deployment features.