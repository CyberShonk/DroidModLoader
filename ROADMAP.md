# Droid Mod Loader Roadmap

This roadmap defines the major direction and release sequence for Droid Mod Loader.

It is not the active task list and should not be used as a substitute for scoped implementation work.

## Planning Rules

- Use this roadmap for release boundaries, major phases, and long-term direction.
- Use `CURRENT_STATUS.md` for the current repository and release state.
- Use `docs/tasks/current-priorities.md` for the next 3 to 7 focused tasks.
- Use `docs/tasks/backlog.md` for rough, deferred, or unscoped ideas.
- Use GitHub Issues for implementation-ready tasks.
- Use `docs/requirements.md` for testable product requirements.
- Use `docs/decisions.md` for accepted technical and product decisions.
- Convert roadmap items into scoped tasks before implementation.
- Do not delay a release for work that is explicitly outside that release boundary.

## Product Direction

Droid Mod Loader is an Android mod manager for Bethesda games running through GameNative and similar Windows compatibility environments.

The intended experience is profile-first, conflict-transparent, and safe for touch-based use. The app should provide the useful parts of a desktop mod manager without copying a desktop interface or pretending Android can use Mod Organizer 2's virtual filesystem.

Droid Mod Loader uses:

- Android 11 or newer
- user granted all files access
- direct filesystem paths for shared game and mod storage
- indexed physical deployment
- profile specific state
- game aware plugin activation and ordering
- deployment planning, verification, diagnostics, and recovery

The core data flow is:

1. Import and isolate installed mods.
2. Index mod files.
3. Resolve enabled mods and file winners.
4. Build a deployment plan.
5. Deploy physical files to the selected target.
6. Verify the resulting state where practical.
7. Report warnings and diagnostics.
8. Recover safely from interrupted or invalid operations.

## Current Baseline

The public release and the development branch must be described separately.

### Public release: `v0.6.0-beta`

The latest public release is `v0.6.0-beta`.

It introduced the profile specific Archive Library and includes early support for:

- profile creation and switching
- managed mod folders
- archive import
- profile specific archive browsing, search, refresh, and folder switching
- ZIP, 7Z, and supported RAR archive handling
- basic BAIN and FOMOD detection
- mod enable and disable
- mod priority
- plugin scanning
- plugin enable and disable
- plugin output files
- deployment planning
- deployment journal and recovery foundations
- baseline and overwrite foundations
- diagnostics and logs
- experimental second-screen plugin display

These systems are functional foundations, not a claim of stable desktop-mod-manager parity.

### Development branch after `v0.6.0-beta`

The `main` branch contains completed post-release work that is not part of the public `v0.6.0-beta` APK:

- migration to one direct-filesystem storage backend
- Android 11 minimum-version enforcement for direct storage
- profile-specific direct paths for Game Root, `Data`, and Archive Library folders
- game-aware plugin activation and ordering
- timestamp ordering for Fallout: New Vegas, Fallout 3, and Oblivion
- Skyrim Legendary Edition text-file ordering behavior
- direct-storage and disposable-folder validation
- completed `MainActivity` responsibility extraction
- completed `ModEngine` service extraction

This work becomes part of the next release only after release validation, documentation, packaging, and tagging are complete.

See `CURRENT_STATUS.md` for the current branch state and latest recorded validation.

## Stable 1.0 Boundary

`v1.0.0` is the first stable public release.

Stable 1.0 means Droid Mod Loader provides a safe, understandable, and repeatable core mod-management workflow. It does not mean complete Mod Organizer 2, Vortex, LOOT, xEdit, Nexus Mods, or scripted-installer parity.

The following areas must be reliable enough to block stable 1.0:

### Archive installation

- Supported archives install without corrupting managed state.
- Unsupported archive variants fail clearly.
- Partial extraction does not become a successful install.
- Temporary files are cleaned up safely.
- Existing installed mods are not damaged by a failed import.

### Profiles and game setup

- Profile state remains isolated.
- The selected game, Game Root, `Data` folder, and output state are clear.
- Invalid or suspicious targets are detected before deployment.
- Target changes do not silently reuse incompatible deployment state.
- Supported games apply the correct plugin activation and ordering rules.

### Deployment and recovery

- Deployment plans are based on the active profile and target.
- Deployment manifests and baselines are scoped to the correct target.
- Interrupted deployment is detected.
- Recovery actions are understandable and safe.
- Existing unmanaged files are not blindly removed.
- Full redeployment is available when required by target or state changes.

### Conflict and overwrite visibility

- The app can determine file winners from enabled mods and priority.
- Users can identify which mod wins or loses a conflict.
- Identical duplicates can be distinguished from meaningful conflicts where practical.
- Generated or changed files outside the managed mod folders are not silently treated as normal managed input.

### Plugin correctness

- Plugins can be discovered, enabled, disabled, and ordered.
- Output follows the active game's rules.
- Basic high-value warnings are available, including missing masters, duplicate names, disabled source mods, missing files, and plugin/archive mismatches where supported.
- Diagnostics describe likely fixes in normal language.

### Diagnostics and support

- Diagnostics include the active profile, game, target identity, plugin output state, unfinished deployment state, and useful archive failure details.
- A support report can be exported without exposing unrelated private files.
- Errors identify the failed operation and the safest next action.

### Usable Android workflow

- The normal path is understandable on portrait and landscape screens.
- Routine navigation is centered on Home, Mods, Plugins, and Deploy.
- Game and profile context remain visible.
- Advanced and developer tools do not dominate the normal workflow.
- Recovery remains accessible as a user safety feature.
- Accessibility labels and touch targets are adequate for the stable workflow.

### Verification and release readiness

- Risky file behavior has focused automated tests.
- Upgrade behavior from the previous public beta is tested.
- A debug build passes repository checks before release work.
- The release candidate is tested on representative Android storage and game-folder layouts.
- Release notes, user documentation, GitHub, and Nexus Mods information agree.

## Release Sequence

The remaining beta releases should narrow the gap to stable 1.0 in clear stages.

## `v0.7.0-beta` — Reliable Foundation

The purpose of `v0.7.0-beta` is to turn the current development branch into a dependable baseline for later feature work.

Primary scope:

- improve ZIP, 7Z, and RAR extraction compatibility
- provide precise errors for unsupported or malformed archives
- prevent partial extraction from being accepted as a successful install
- clean temporary extraction state safely
- preserve existing managed mods when import fails
- validate upgrade behavior from `v0.6.0-beta`
- retain the completed direct-filesystem migration
- retain game-aware plugin activation and ordering
- retain the extracted `MainActivity` and `ModEngine` architecture
- improve archive-related diagnostics and support information
- complete release documentation, packaging, and device checks

Work already completed on `main` should be validated and released, not described as unfinished feature scope.

Exit conditions:

- archive-install failure paths are covered by focused tests
- supported sample archives install successfully
- unsupported samples fail without partial managed state
- direct-path profile state survives restart and upgrade checks
- game-aware plugin-order tests remain green
- repository checks, JVM tests, and debug APK assembly pass
- public documentation accurately separates current and planned behavior

## `v0.8.0-beta` — Safe Setup, Deployment, and Recovery

The purpose of `v0.8.0-beta` is to make game setup and physical deployment trustworthy.

Primary scope:

- make **Choose a game** the normal setup entry
- make Game Folder selection the normal path
- detect and validate Game Root and `Data` where practical
- keep a separate advanced `Data` folder override
- improve beginner-safe folder and permission explanations
- add stronger validation for Skyrim Legendary Edition, Fallout: New Vegas, Fallout 3, and Oblivion
- add a dedicated Tale of Two Wastelands definition that reuses the Fallout: New Vegas engine family without presenting TTW as a generic FNV profile
- scope deployment manifests and baselines to profile, game, and target identity
- require full redeployment when target identity changes
- detect unfinished or stale deployment state
- improve recovery and post-deploy verification
- protect unmanaged files found in existing installations
- expose a visible, profile-aware `DML_output` handoff for the active profile
- expand deployment and target information in diagnostics and support reports

The app should not move or delete existing manual files unless the user explicitly chooses a separately designed adoption or cleanup action.

Exit conditions:

- invalid targets block dangerous deployment
- suspicious targets produce clear warnings
- target changes cannot silently reuse old deployment state
- interrupted deployment can be detected and handled
- unmanaged baseline files survive normal deployment and removal tests
- active-profile output cannot be confused with another profile
- supported game definitions pass folder and plugin-output checks

## `v0.9.0-beta` — Complete Core User Workflow

The purpose of `v0.9.0-beta` is to connect the reliable engine work to a complete daily-use workflow.

Primary scope:

- expand the resolved data graph
- track file winners and overwritten providers
- distinguish identical duplicates where practical
- provide understandable mod-level and file-level conflict details
- show clear overwrite status, including fully overwritten mods
- track generated output separately from normal installed mods
- support the common practical subset of BAIN and FOMOD installers
- preview installer results before committing files where practical
- fail safely when installer logic is unsupported
- add basic plugin intelligence required by the stable boundary
- add minimum useful local metadata, such as version and source-link editing
- provide a basic Settings surface for normal configuration
- apply the profile-first Home, Mods, Plugins, and Deploy navigation model
- improve portrait, landscape, and larger-screen behavior
- apply progressive disclosure so routine actions stay clear
- prepare the Red Carbon default visual direction without delaying core correctness for decorative polish

The installer goal is common-case support with safe failure. Complete scripted FOMOD compatibility is not required for stable 1.0.

Exit conditions:

- users can understand which mod wins a conflict and why
- normal install, enable, order, deploy, diagnose, and recover flows connect cleanly
- unsupported installer behavior cannot silently install the wrong file set
- required plugin warnings appear in the main workflow and support report
- routine tasks work in portrait and landscape layouts
- advanced tools remain available without overwhelming the main interface

## `v0.9.x-beta` — Stabilization

After the core `v0.9.0-beta` workflow is present, `v0.9.x-beta` releases should focus on stabilization rather than new feature areas.

Primary scope:

- fix regressions and data-migration problems
- improve performance on larger modlists
- harden cancellation and interruption behavior
- expand file-operation and upgrade tests
- verify representative GameNative and similar shared-storage workflows
- improve accessibility and confusing wording
- finish support-report privacy review
- update user documentation and troubleshooting
- complete release-candidate checks

New large integrations should not be added during stabilization unless they fix a stable-release blocker.

## `v1.0.0` — First Stable Release

Release `v1.0.0` when the stable boundary is met and the stabilization cycle finds no unresolved high-risk file-safety, profile-isolation, deployment, recovery, or plugin-correctness problem.

The stable release should provide:

- safe supported archive import
- isolated profiles
- validated game targets
- reliable deployment and recovery
- understandable conflict and overwrite information
- correct game-aware plugin output
- basic plugin diagnostics
- exportable support information
- a coherent Android workflow
- documented upgrade and release procedures

## Game Scope Through 1.0

Core pre-1.0 validation and plugin work should focus on:

- Skyrim Legendary Edition
- Fallout: New Vegas
- Fallout 3
- Oblivion
- Tale of Two Wastelands through its own game definition

Skyrim Special Edition, Fallout 4, and additional games remain later expansion targets unless a separate scoped decision changes their priority.

## Work That Does Not Block Stable 1.0

The following work may be researched or developed independently, but it should not delay `v1.0.0` unless it becomes necessary to fix a core workflow:

### Nexus Mods integration

Basic local source links and manual metadata may be included before 1.0.

The following are later features:

- Nexus account authentication
- direct Nexus downloads
- automatic update checks
- rich remote metadata synchronization
- collection download automation

### LOOT integration

LOOT metadata, suggested sorting, dirty-plugin metadata, and automatic order application are post-1.0 integration work.

Droid Mod Loader should first provide correct game-specific ordering and basic local diagnostics.

### xEdit integration

An embedded xEdit replacement or an in-app xEdit-like runner is not a stable-1.0 requirement.

Later work may include:

- importing structured reports
- presenting selected findings
- adding findings to support reports
- coordinating with external or companion tools

### Advanced installer parity

Stable 1.0 requires common installer workflows and safe unsupported behavior.

It does not require:

- complete scripted FOMOD parity
- arbitrary installer script execution
- support for every historical BAIN convention
- automatic decisions for ambiguous installer options

### Guides and collections

Guide mode, collection automation, shared setup manifests, and automatic missing-mod workflows are later product layers.

They depend on reliable metadata, installers, conflicts, diagnostics, and deployment first.

### Game configuration presets

INI presets, configuration recipes, and archive invalidation remain separate work.

They should be added only after the active-profile output workflow is safe and each preset has documented sources, prerequisites, and deterministic behavior.

These features are not stable-1.0 blockers.

### Storage expansion

A full storage manager and dedicated external-SD workflow are later features.

Pre-1.0 work should provide clear free-space errors, safe temporary-file cleanup, and understandable missing-storage warnings.

### Deeper GameNative helpers

Droid Mod Loader should continue to work through user-selected shared folders and generated output.

It should not depend on private GameNative internals. Container-specific automation, executable patching, launch helpers, and deeper environment inspection require separate scope and validation.

### Second-screen expansion

Second-screen support remains optional and experimental.

Possible later uses include:

- operation monitoring
- plugin monitoring
- selected-mod details
- support and tester reports
- environment information
- touch shortcuts

The normal app must remain fully usable without a second screen.

### Compatibility scoring and texture budgets

Texture-budget warnings, device profiles, GameNative compatibility scoring, and large-modlist recommendation systems are post-1.0 features.

## Existing Manual Installations

Droid Mod Loader must remain conservative around files it did not install.

The pre-1.0 requirement is to:

- detect existing target files
- protect them from blind deletion
- identify unmanaged plugins and loose files where practical
- keep later DML-managed deployment separate from protected baseline state

A full adoption wizard that moves, splits, or converts an existing manual installation into managed mods is later work and requires explicit user review.

## Architecture Direction

The completed responsibility extractions establish the expected structure for future work:

- `MainActivity` remains the Android composition root.
- Workflow controllers and supporting classes own UI coordination.
- `ModEngine` remains the stable engine facade.
- Focused services own mod library, plugin, deployment, inspection, and archive-history behavior.
- New behavior should be added to the focused owner instead of growing the composition root or engine facade back into multi-domain implementations.
- GRIT remains a separate project and is not a DML dependency.

Architecture cleanup should support product work. It should not become an open-ended release phase by itself.

## Current Priority

The immediate implementation priority is `v0.7.0-beta` archive-install safety:

1. improve archive extraction compatibility and diagnostics
2. prevent partial or failed extraction from becoming installed state
3. clean temporary files safely
4. add focused archive regression tests
5. validate the completed post-release architecture and storage changes
6. prepare and verify the `v0.7.0-beta` release

Do not begin broad `v0.8.0-beta` setup or deployment work until the reliable-foundation release is complete or the roadmap is deliberately revised.

## Documentation Alignment

After this roadmap boundary is accepted:

- update `docs/decisions.md` with the stable-1.0 boundary and release sequence
- remove the completed roadmap-boundary task from `docs/tasks/current-priorities.md`
- keep `CURRENT_STATUS.md` focused on actual repository state
- reconcile requirement statuses without marking planned work as working
- update the README so it does not list post-1.0 integrations as stable-release blockers
- keep release notes limited to behavior present in the tagged APK
- update the Nexus Mods page whenever the public DML version changes
