# Droid Mod Loader UI/UX Direction — Quiet Workbench

Last updated: 2026-06-30

Status: **Accepted direction for the planned `v0.9.0-beta` interface.**

This document defines the public UI, interaction, responsive-layout, accessibility, and theme direction for Droid Mod Loader. It describes planned behavior rather than features already present in `v0.7.0-beta`.

When older mockups or notes conflict with this document, this document takes precedence. Exact spacing and color-token values may be calibrated during implementation for accessibility, font scaling, localization, display behavior, window size, touch targets, and measured performance. Those adjustments must not change the locked hierarchy, workflows, state meanings, theme identities, or safety behavior.

## 1. Product direction

Quiet Workbench is the planned Droid Mod Loader v0.9.0 UI system.

It should be:

- Calm, efficient, and purpose-built
- Comfortable in low light
- Readable on OLED and conventional displays
- Moderately dense without becoming cramped
- Distinctive through hierarchy and interaction quality rather than decoration

Avoid card-per-item layouts, gradients, glow, visible textures, oversized headers, decorative motion, and dashboard clutter.

Design in grayscale first. Validate structure, spacing, hierarchy, density, navigation, readability, and responsive behavior before color.

## 2. Responsive structure

### Narrow layouts

Use bottom navigation:

- Home
- Mods
- Plugins
- Deploy

### Wide layouts

Use a left navigation rail with the same destinations.

Wide layouts use a central list and persistent right-side details pane. Narrow layouts use full-screen details. Switch by available width rather than device category.

The active game, active profile, and deployment state use one responsive persistent-context pattern.

### Expanded state

At screen entry, near the top of a list, or when width permits, use an H-style app bar:

- Full game name as the leading context
- Explicit profile control
- Separate deployment-status control
- Distinct tap targets for game, profile, and deployment details

### Collapsed state

After scrolling or when vertical space is constrained, collapse into an E-style sticky context bar:

- Keep the full game name whenever it fits
- Use a recognized abbreviation only when necessary
- Keep profile and deployment state visible
- Preserve distinct accessible tap targets

Return to the expanded state when the user reaches the top.

When deployment is Blocked, Failed, or Interrupted, expand the status area enough to show the issue and one direct action rather than compressing it into an ambiguous indicator.

In reduced-motion mode, switch between expanded and collapsed states without animation.

See [Persistent context reference](assets/persistent-context-final.png).

## 3. Deployment states

Use:

- Current
- Changes pending
- Deploying
- Blocked
- Failed
- Interrupted
- Unknown or unavailable

Current means the deployed filesystem and generated plugin files match the active profile.

Every state uses text plus an icon or shape. Color is reinforcement only.

## 4. Home

Home is a status-and-next-action screen, not a statistics dashboard.

Show:

- Active game/profile context
- Readiness and deployment state
- Needs attention
- Recent activity
- A few useful quick actions
- Small secondary counts only when decision-relevant

Needs attention contains actionable items only and sorts by:

1. Blocking errors
2. Recovery or data-loss risks
3. Dependency/plugin warnings
4. Conflict-review suggestions
5. Informational notices

Recent activity records meaningful actions such as installs, removals, reordering, activation changes, profile switches, deployments, and recovery.

## 5. Mods

### Rows

Use flat, moderately dense rows with subtle dividers.

Order:

1. Drag handle
2. Priority
3. Enable switch
4. Mod name
5. Version and install date
6. Right-aligned indicators

Indicators cover:

- Overwrites
- Overwritten
- INI/configuration presence
- Warning
- Fully overwritten

Only the switch toggles the mod. Row tap opens details. Long-press enters multi-select. Disabling offers Undo.

Optional haptics distinguish switch toggle, drag start, and drop.

### Reordering

Drag is primary. Also support:

- Move top
- Move bottom
- Move before/after
- Enter priority
- Move into section

### Search and filters

Keep search visible. Use one Filter control. Show chips only for active filters.

Do not show Updates until reliable update detection exists.

### Sections

Support MO2-style sections:

- Create
- Rename
- Reorder
- Collapse/expand
- Delete
- Move selected mods into section
- Contained-mod count

Collapsed sections still summarize hidden warnings, fully overwritten mods, disabled mods, and pending changes.

### Phone details

Use:

1. Normal row
2. Compact inline summary
3. Full details screen

Only one row expands at a time. Inline content is limited to essential conflict state, INI presence, two quick actions at most, and Full details.

### Full details

Use responsive sections or tabs:

- Overview
- Conflicts
- Files
- Metadata
- Actions

Show actionable metadata. Do not show routine total file counts.

Primary actions:

- View files
- Reinstall
- View/edit metadata

Less common or destructive actions remain in overflow.

Reinstall identifies its source and preserves priority, section, and prior installer choices by default.

## 6. Mod inspection

### File tree

Provide a searchable, collapsible relative-path tree.

Mark files:

- Winning
- Overwritten
- Uncontested

Conflicted files show winner, other providers, and priority order.

Load large trees incrementally. Keep absolute staging paths, hashes, and raw extraction metadata out of normal UI.

### Conflict view

Use two levels:

1. Mod-to-mod summary
2. Optional file-level details

File details show path, winner, providers, priority order, and whether reordering changes the winner.

Provide jump-to-mod, reorder, unusual-conflict filter, and return-with-context actions.

### Fully overwritten

Use a distinct non-blocking state with muted but readable styling.

Offer:

- Review conflicts
- Jump to winning mods
- Disable
- Change priority

### INI/configuration

The document icon is informational.

Its summary shows:

- Detected configuration files
- Recognized recipe
- Output target
- Whether review is required
- Detected overlap

### Metadata

Preserve detected values and clearly mark user overrides.

Possible fields:

- Display name
- Version
- Author
- Source site/page or mod ID
- Original archive
- Archive association
- Install date
- Notes

Never silently overwrite custom values.

## 7. Plugins

Plugins remain a separate workspace.

### Rows

Use compact single-line rows:

1. Numeric priority
2. Enable switch
3. Plugin name
4. Right-aligned indicators

Hexadecimal load index is optional secondary metadata only when meaningful.

Use Reorder, not Sort.

### Reordering

Provide only:

- Drag
- Move to position…

Reordering updates the profile, marks generated output Changes pending, and never deploys automatically.

Warn about invalid dependency order without silently correcting it. Handle timestamp-based ordering internally.

### Activation

Use the row switch.

Prevent activation when required masters are missing. Warn when masters are disabled. Apply game-specific activation formats internally.

### Plugin details

Show:

- Enabled state
- Priority
- Source mod
- Required masters
- Missing/disabled masters
- Game-specific ordering information
- Warnings
- Generated-file state
- Optional hex index

### Generated files

`plugins.txt` and `loadorder.txt` use:

- Current
- Changes pending
- Out of sync
- Write failed
- Unavailable

Show exact output location, preview contents, detect external edits, explain replacement, and provide retry.

### Generated plugins

Recognize Bashed Patch and similar outputs.

Show informational status, likely generating tool, stale-state warning, and regeneration guidance. Never regenerate automatically.

## 8. Deploy

Deploy is a focused review-and-execute workspace.

### Ready state

Show:

- Deployment current
- Last successful deployment
- Active target path
- Active profile
- Relevant non-blocking warnings
- Links back to Mods or Plugins

Do not show a disabled primary button or unnecessary totals.

### Pending changes

Summarize:

- Added
- Replaced
- Removed
- Generated plugin/configuration outputs

Detailed plan operations:

- Add
- Replace
- Remove
- Generate

Show destination path, source mod, and winning mod when relevant.

Controls:

- Search path/mod
- Filter operation
- Warnings only

### Prechecks

Use Passed, Warning, and Blocked.

Check:

- Game folder
- Data folder
- Permissions
- Storage
- Profile validity
- Plugin-output writability
- Recovery requirements
- Unexpected target changes

### Warnings

Split into Blocking and Non-blocking.

Show blocking first. Do not provide Ignore all warnings.

### Progress

Use stages:

- Preparing
- Writing mod files
- Generating plugin files
- Applying configuration
- Verifying deployment
- Finalizing

Show reliable progress only. No speculative time estimate.

Allow cancellation only when safe. Mark non-cancellable commit steps. Support background notification.

### Completion

Show deployment complete, profile, completion time, updated outputs, and remaining relevant items.

Actions:

- Done
- View details

### Recovery

Primary actions:

- Resume recovery
- Repair deployment
- View details

Abandon recovery is guarded overflow only.

Recovery blocks new deployment.

Do not claim rollback unless a real verified rollback system exists.

## 9. First-run setup

### Welcome

Use one minimal screen with:

- Restrained branding
- One short explanation
- Choose a game
- About setup and storage access

No multi-page tour, forced scrolling, or bypass of required setup.

### Storage permission

Explain storage access before Android settings.

Automatically continue after grant.

Provide clear denial/revocation recovery.

Explain that protected Android/data locations may remain inaccessible.

### Choose a game

Show supported titles only, grouped by franchise and oldest-to-newest:

#### Fallout

1. Fallout 3
2. Fallout: New Vegas
3. Tale of Two Wastelands

#### The Elder Scrolls

1. Oblivion
2. Skyrim Legendary Edition

TTW is its own option.

### Game-folder validation

Validate:

- Expected executable
- Data folder
- Base masters
- Base archives
- Game-specific requirements
- Required read/write access

Results:

- Valid
- Valid with warnings
- Invalid

### Initial profile

Create Default automatically. Allow optional rename. Defer Archive Library setup. Finish on Home.

## 10. Profiles and game switching

### Profile switcher

Compact list with:

- Active marker
- Deployment state
- Attention indicator
- Create action
- Overflow management
- Search only when many profiles exist

Never auto-deploy while switching.

### Create profile

Offer:

- Blank
- Clone current
- Clone another

Clone profile configuration, not deployment history, recovery state, or temporary errors.

### Manage profiles

Rename, Duplicate, Delete live in overflow.

Prevent blank/duplicate names.

Do not delete the only profile or active profile.

Deleting a profile does not delete shared archives.

### Switch with pending changes

Offer exactly:

- Switch without deploying
- Deploy, then switch
- Cancel

Never discard edits. Do not remember the previous choice automatically.

### Game switching

Keep each game’s profiles, orders, archive scope, and deployment state isolated.

Restore the selected game’s last active profile. Never auto-deploy. Return to Home.

## 11. Archive installation

### Entry points

Allow:

- Install archive from Mods
- Archive Library install
- Android Open with/Share
- File-manager launch
- Recent archives after prior use

All use one workflow.

### Inspection

Use signature-based format detection.

Check readability, structure, likely root, installer layout, recognizable content, path safety, duplicates, case collisions, sizes, path length, and storage headroom.

Provide precise failure reasons and clean cancellation.

### Destination review

Detect install root automatically. Preview final Data destination. Suggest wrapper-folder correction. Allow manual correction.

States:

- Ready
- Needs review
- Invalid

### Installer choices

Use grouped controls:

- Required components
- Choose-one variants
- Optional features
- Compatibility patches
- Recommended defaults

Prevent invalid combinations immediately. Allow backtracking. Restore previous choices on reinstall.

### Conflict review

Show:

- Mods overwritten by the new mod
- Higher-priority mods that overwrite it
- Fully overwritten result
- Unusual conflicts
- Suggested priority

Provide optional file details.

### Progress

Use:

- Preparing archive
- Extracting files
- Validating installation
- Registering mod
- Finalizing

Support safe cancellation, background continuation, notification, recovery, and cleanup.

### Completion

Confirm placement and enable state, preserve choices, highlight the mod, and do not auto-deploy.

### Interrupted install

Use transactional isolated staging.

Never register a partial install.

Resume only from valid state; otherwise clean safely.

Preserve the previous installed version until a replacement commits.

## 12. Archive Library

Use a compact searchable list, not thumbnails.

Rows may show archive name, detected mod, version, installed state, game, warning/missing state, and overflow.

Controls:

- Search
- Filter
- Add archive folder
- Rescan

Archive folders are game-scoped and shared by profiles.

Allow multiple folders, disable, incremental rescan, preserved unavailable metadata, and permission recovery.

Primary actions:

- Install
- Reinstall associated mod
- Associate with installed mod

Overflow:

- Edit metadata
- Remove from library
- Delete from storage

Removing from library does not delete the file.

Large libraries use incremental background scanning, stable results, targeted updates, and duplicate detection without unnecessary hashing.

## 13. Themes and appearance

Ship five bundled themes:

1. Capital Wasteland
2. Deep Ink
3. Warm Workshop
4. Adaptive Neutral
5. Red Carbon

Red Carbon is labeled **Developer’s Choice**. It uses near-black and dark carbon-gray surfaces with restrained red accents while remaining functionally identical to every other theme.

**Adaptive Neutral** is the first-launch default. Treat it as a neutral charcoal foundation with a restrained accent rather than a fixed blue theme.

Do not force a theme chooser during setup. Store the theme choice globally, apply changes immediately without restart, restore an existing saved choice with settings, and keep OLED-black disabled initially.

Theme previews apply immediately. No restart.

OLED-black is an option inside compatible themes.

Accessibility settings:

- Haptic feedback
- Reduced motion
- Higher contrast
- Larger controls
- Enhanced status explanations

Silly Goofy Mode appears in Appearance only after Developer options are enabled.

It may add goofy animations, playful messages, novelty sounds, and expressive haptics.

It never changes behavior, hides warnings, affects safety, reduces readability, or bypasses accessibility/mute settings.

## 14. Diagnostics, support, and logs

### Diagnostics

Plain-language status for:

- Storage access
- Game/folder validation
- Data access
- Archive Library
- Plugin output
- Deployment
- Recovery
- Available storage
- App version

Each item shows status, explanation, and direct fix/details.

### Support report

Generate locally. Never upload automatically.

Include relevant app, device, game, profile, deployment, folder, plugin-output, error, storage, and developer-option information.

Redact paths where practical. Preview before export.

Actions:

- Copy
- Save
- Share

Include one optional short comment field.

### Error details

Show plain-language summary first:

- What failed
- What DML was doing
- Whether data is safe
- What to do next

Technical expansion may show type, path, stage, time, message, Copy, and Add to support report.

Every significant error creates a preserved local snapshot with surrounding context.

### Logs

Separate:

- Recent activity
- Error history
- Technical logs

Error snapshots retain longer. Technical logs rotate. Recovery logs remain until resolved.

Support export and manual clearing. Never upload automatically.

## 15. Developer and test tools

### Developer options

Enable by repeatedly tapping app version.

Show confirmation, active indicator, Settings section, and obvious master disable toggle.

Unsafe internal controls remain debug-build-only.

### Developer test profile

Create explicitly.

Keep isolated from real profiles, game data, and archives.

Populate from a versioned, integrity-checked, deterministic ZIP containing sample mods, plugins, INI files, known conflicts, sections, archive associations, expected metadata, and deployment fixtures.

### Sample archives

Maintain versioned fixtures for valid and invalid:

- ZIP
- 7Z
- RAR4
- RAR5
- Damaged
- Unsupported
- Password-protected
- Path traversal
- Duplicate paths
- Case collisions
- Excessive path length
- Oversized file
- Excessive total size
- Low storage
- Multiple roots
- Wrapper folder
- Installer structure

### Debug actions

Provide:

- Force rescan
- Rebuild conflict map
- Reset developer test profile
- Load sample archive fixture
- Simulate deployment state
- Simulate interrupted install
- Simulate interrupted deployment
- Clear developer test data

All operate only on isolated test data. Simulations are labeled and easy to reset. Destructive reset requires confirmation.

### Safety boundaries

Developer testing and real user data remain strictly isolated.

Leaving Developer options must not leave hidden simulations active.

Reset restores the packaged baseline.

Any action capable of touching real data must identify the target explicitly.

## 16. Cross-cutting requirements

### Touch and text

- Approximately 48 dp touch targets
- No ambiguous overlap
- Restrained typography hierarchy
- Android font scaling support
- Long names available in details
- Middle-truncated paths
- Localization support

### Status accessibility

Every status indicator needs:

- Consistent symbol
- Consistent placement
- Accessible label
- Tap-to-explain behavior
- Text description in details

### States

Every workspace needs:

- Empty
- Loading
- Error
- Unavailable
- Blocked
- Interrupted

Each state explains what happened and provides one clear next action.

### Undo and destruction

Use Undo for routine reversible actions.

Require confirmation for permanent deletion, clearing recovery data, resetting profiles, or removing unrecoverable metadata.

### Performance

Use:

- Lazy rendering
- Stable row keys
- Targeted updates
- Background analysis
- Responsive search/filter
- Stable status indicators
- Clear progress
- No routine full-screen blocking

### Motion

Normal mode uses functional motion only:

- Row reorder settling
- Section collapse
- Inline-summary expansion
- Details-pane update
- Deployment progress
- Status transition

Avoid pulsing, glow, continuous animation, decorative transitions, and excessive bounce.

Respect reduced motion.
