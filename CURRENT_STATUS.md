# Current Status

- **Latest public release:** `v0.7.0-beta`
- **Minimum Android version:** Android 11 / API 30
- **Development state:** Released; post-release monitoring
- **Storage model:** Direct filesystem access for shared-storage workflows

## Release state

`v0.7.0-beta` is the current public release. It is the Reliable Foundation
release and establishes the baseline for later setup, deployment, conflict, and
Nexus-related work.

Broad `v0.8.0-beta` implementation has not started. Immediate work after this
release is limited to monitoring reports, correcting release-blocking defects,
and turning the next roadmap items into scoped tasks.

## Included in `v0.7.0-beta`

- Tale of Two Wastelands as a selectable profile with the correct legacy
  timestamp-based plugin-order policy;
- one direct-filesystem backend for Game Root, `Data`, Archive Library,
  deployment, scanning, imports, and plugin timestamp ordering;
- extracted `MainActivity` workflow ownership and focused services behind the
  `ModEngine` facade;
- content-signature detection for ZIP, 7Z, RAR4, and RAR5 archives;
- signature-based Archive Library scanning and stored format metadata;
- precise errors for unsupported, encrypted, multipart, corrupt, or unsafe
  archives where the reader can classify the failure;
- bounded extraction with path, duplicate, case-collision, entry-count,
  per-file size, total-size, path-length, and storage-headroom checks;
- staged and transactional installed-mod replacement;
- recovery of interrupted replacement transactions at startup and profile
  activation; and
- cooperative cancellation for archive copying, preparation, extraction, and
  automatic or user-selected installation.

RAR5 is detected accurately but is not currently installable.

## Release validation

The release candidate passed:

- `git diff --check`;
- the full JVM unit-test suite;
- `./tools/check-project.sh`;
- debug and release APK assembly;
- version-name and version-code verification;
- the real-archive compatibility matrix;
- fresh-install and in-place upgrade checks from `v0.6.0-beta`;
- profile, path, archive-history, mod, plugin, and deployment-state retention;
- large-import cancellation and cleanup;
- interrupted replacement recovery after restart; and
- safe-folder deployment and restart smoke checks.

The published APK must remain signed with the same Android signing identity used
for prior public releases.

## Known limitations

- RAR5, password-protected or encrypted archives, and multipart RAR archives are
  not supported for installation.
- Unsupported 7Z compression or encryption variants may still be rejected.
- Android continues to protect other applications' private `Android/data`
  directories even when all-files access is granted.
- Game Root and `Data` validation still needs stronger game-specific guidance in
  a later release.
- Droid Mod Loader remains beta software. Back up important game folders before
  testing deployment.

## Locked future UI direction

The UI, responsive-layout, interaction, accessibility, and theme direction for the planned `v0.9.0-beta` overhaul is finalized as **Quiet Workbench**.

The accepted direction uses Home, Mods, Plugins, and Deploy as the primary workspaces; a responsive H-to-E game/profile/deployment context; Adaptive Neutral as the first-launch theme; and Red Carbon as the Developer's Choice theme. See [`docs/ui-ux.md`](docs/ui-ux.md).

This is design documentation only. It does not describe behavior included in `v0.7.0-beta`, and broad `v0.9.0-beta` implementation has not started.

## Next priorities

1. Monitor `v0.7.0-beta` reports and fix release-blocking regressions only.
2. Define the scoped `v0.8.0-beta` setup, validation, deployment, and recovery
   work.
3. Continue defining the stable 1.0 acceptance boundary.

## Last updated

2026-06-30
