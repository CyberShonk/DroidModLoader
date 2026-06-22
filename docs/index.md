# Droid Mod Loader Documentation

This folder contains the project documentation for Droid Mod Loader.

## Core Docs

| Document                                                                  | Purpose                                                                         |
|---------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| [Vision](vision.md)                                                       | Defines what Droid Mod Loader is trying to become                               |
| [Requirements](requirements.md)                                           | Defines what the app must do                                                    |
| [Architecture](architecture.md)                                           | Explains the current project structure                                          |
| [Decision Log](decisions.md)                                              | Records major technical and product decisions                                   |
| [Testing](testing.md)                                                     | Tracks what needs to be tested                                                  |
| [Direct Storage Benchmark](benchmarks/direct-storage.md)                   | Defines the repeatable SAF-baseline versus direct-path deployment benchmark     |
| [Release Checklist](release-checklist.md)                                 | Lists checks before publishing builds                                           |
| [Brand Assets](assets/brand-assets.md)                                    | Tracks icon and branding asset rules                                            |
| [Changelog](../releases/changelog.md)                                     | Tracks release history                                                          |
| [Development Loop](process/development-loop.md)                           | Defines the task to code to test to commit process                              |
| [Roadmap vs Tasks](process/roadmap-vs-tasks.md)                           | Explains how roadmap items, backlog items, priorities, and issues are separated |
| [Source Map](architecture/source-map.md)                                  | Maps source folders/files to responsibilities                                   |
| [User Guide](user-guide.md)                                               | Explains normal app use                                                         |
| [Troubleshooting](troubleshooting.md)                                     | Lists common problems and fixes                                                 |
| [Glossary](glossary.md)                                                   | Defines app and modding terms                                                   |
| [Versioning](process/versioning.md)                                       | Defines app version, versionCode, APK filename, and changelog rules             |
| [Release Notes Template](../releases/templates/release-notes-template.md) | Template for public release notes                                               |
| [APK Upload Checklist](../releases/templates/apk-upload-checklist.md)     | Checklist for public APK uploads                                                |
| [Git Workflow](process/git-workflow.md)                                   | Defines branch, commit, push, and local environment rules                       |
| [Task Template](tasks/task-template.md)                                   | Template for scoped implementation tasks                                        |
| [Current Priorities](tasks/current-priorities.md)                         | Short active task list                                                          |
| [Backlog](tasks/backlog.md)                                               | Rough future work and deferred ideas                                            |

## Development Rule

Before adding a new feature:

1. Check the vision.
2. Check the requirements.
3. Decide whether the feature belongs now or later.
4. Update the docs if the project direction changes.
5. Test risky file behavior before release.