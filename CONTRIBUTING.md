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
JVM tests. `check-docs.sh` checks required documentation, local Markdown/HTML links and
Markdown heading anchors, and unfinished placeholders outside examples. It does
not fetch external links or prove that instructions match the app. Review version
scope, visible button names, user steps, limitations, and upgrade advice against
the source and the release being described. Templates can contain placeholders;
finished release documents cannot.

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
privately shared candidates. This example uses explicit offline confirmation and
does not query GitHub or Nexus. It chooses one above both that confirmed code and the current `version.properties`
code. An optional numeric second argument overrides the choice only when greater
than both values. Codes must be between 1 and 2100000000.

Run from a clean `release/<version>` branch in the main working tree, not a linked
worktree. The previous published tag must match its version file and be an ancestor
with commits after it. Python 3.11 or newer, `flock`, full Git history and a GitHub `origin` are
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

### Published APK lookup

For a read only check without preparing a release:

```bash
python3 tools/collect-release-baseline.py \
  --version v0.8.0-beta --previous-tag v0.7.0-beta \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --certificate-sha256 YOUR_CONFIRMED_CERTIFICATE_SHA256 \
  --confirmed-other-code 3 --other-evidence 'Confirmed Nexus and private distributions' \
  --output release-artifacts/v0.8.0-beta/maintainer/github-lookup-01
```

Use the existing release certificate confirmed from a published APK. The lookup
pages through public releases and prereleases, downloads their APKs, verifies
signatures with `apksigner`, and reads package/version codes with `aapt2`. It checks
the selected previous release against its Git version file. Other application IDs,
including DML Dev, are listed but excluded from the release code sequence. A different
certificate is allowed only for an APK published before the selected previous release
with a code below that release's code. Historical certificates are recorded, not
adopted as the release signing identity. The selected previous release, newer
publications and any code at or above its code require the confirmed certificate.
A debuggable APK using the release package stops the lookup.

`--confirmed-other-code` is the highest code distributed outside GitHub; use `0`
only when none exist. The evidence line records that confirmation. The lookup prints
the combined distributed floor, not a reserved next code. It saves raw API responses,
Android inspection output and `baseline.txt` beneath the selected output directory.
APK bytes are cached by hash in the release's `maintainer/github-apks/` directory;
reuse requires the current published digest to match, and inspection runs again.
Without a published digest, the APK is downloaded again. Existing outputs and corrupt
cache entries are never silently replaced.

To use this lookup during preparation, pass `--confirmed-other-code`,
`--other-evidence`, `--build-tools`, `--certificate-sha256` and `--baseline-output`
to `tools/prepare-release.sh` instead of the two offline options. The output must be
a new directory under `release-artifacts/<version>/maintainer/`. Preparation chooses
above the canonical current code and the collected distributed floor. Offline and
lookup options cannot be mixed.

API failures, incomplete or changing publication data, signature/identity failures
and resource limits stop the operation. No automatic offline fallback occurs.
If needed, review the evidence and explicitly supply a complete confirmed offline
baseline. The lookup uses no API token and may encounter GitHub rate limits.

## Packaging a signed APK

`tools/package-release.py` checks and packages an already signed release APK. It
does not build, sign, allocate a version code or upload files. Use a clean committed
checkout whose `version.properties` matches the APK, and the confirmed release
certificate. The APK must contain clean release build provenance for that commit.

```bash
python3 tools/package-release.py \
  --apk /path/to/signed.apk --version v0.8.0-beta --code 4 \
  --source-commit "$(git rev-parse HEAD)" \
  --certificate-sha256 YOUR_CONFIRMED_CERTIFICATE_SHA256 \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"
```

The example version/code are not a reservation. The tool independently verifies the
APK signature, certificate, package, version and debuggable flag. It copies the APK
to its final name and generates a receipt for those exact bytes. If an input receipt
exists, it must already match the signed input. Distribution notices must be reviewed
again after dependency declarations change.

Output is under `release-artifacts/<version>/candidates/code-<code>/package/`:

- `github/assets/`: APK, APK receipt, release manifest and `SHA256SUMS.txt`.
- `nexus/`: Nexus ZIP with the same APK and receipt, install instructions, licenses,
  notices and checksums for its contents.

The manifest records package/version/code, source commit, certificate and each
payload's name, size, SHA256 and destination. The ZIP is listed as a Nexus download,
not a GitHub asset. Outer checksums cover APK, receipt, manifest and Nexus ZIP;
they do not include the checksum file itself.

Existing packages are never regenerated. A failed attempt retains
`.package-incomplete/` for inspection, and another attempt refuses it. Inspection
logs are kept separately under the release's `maintainer/` directory. Review failed
outputs before deliberately clearing task outputs or selecting another candidate.

To check an existing package, replace `--apk` with `--check-directory` pointing to
its `package/` folder and keep the expected identity and SDK options. Add
`--github-only` to verify the GitHub payloads without downloading the Nexus ZIP.
That mode checks its recorded identity but cannot verify absent ZIP bytes. Full
verification requires the Nexus ZIP. Package checks do not replace testing the
final APK's installation and update behavior.

## Local release preview

`tools/preview-release.py` creates an exact text review page and copies the supplied
files into a new snapshot. It does not render Markdown or BBCode, build an APK,
access signing keys, fetch links, upload, or approve a release. Python 3.11 or newer
and local Git history are required.

Generated release files belong under the ignored `release-artifacts/<version>/`
directory. Keep text drafts in `drafts/` and each candidate's files, metadata and
review output in `candidates/code-<version-code>/`. Keep distributed candidates
separate rather than overwriting them. Source templates, the changelog and committed
release notes stay in Git.

```bash
python3 tools/preview-release.py \
  --input release-artifacts/v0.8.0-beta/candidates/code-4/input \
  --output release-artifacts/v0.8.0-beta/candidates/code-4/review \
  --version v0.8.0-beta --version-code 4 \
  --source-commit HEAD --previous-ref v0.7.0-beta \
  --certificate-sha256 YOUR_CONFIRMED_CERTIFICATE_SHA256
```

The values above are examples. Open the resulting `index.html` locally. Documentation
comes from the specified commit, not uncommitted edits; the page includes its changes
from the previous ref. Raw text is escaped rather than executed or loaded from the web.

Supply these text files beneath the input folder:

- `github/title.txt`, `github/body.md` and `github/prerelease.txt` (`true` or `false`);
- `nexus/file-description.txt`, `nexus/changelog.txt` and `nexus/description.bbcode`;
- `previous/nexus-description.bbcode` for the description comparison;
- `maintainer/checklist.txt` for pending checks and approvals; and
- optionally `maintainer/email.txt`, clearly shown as an unsent draft.

Put the canonical APK, receipt, release manifest and `SHA256SUMS.txt` in
`github/assets/`. Put `DroidModLoader-<version>-Nexus.zip` directly in `nexus/`,
not in the GitHub assets. The ZIP contains the same APK/receipt, `README-NEXUS.txt`,
`LICENSE`, `THIRD-PARTY-NOTICES.txt` and its own `SHA256SUMS.txt`. Outer checksums
cover APK, receipt, manifest and Nexus ZIP; inner checksums cover the ZIP payloads,
not the checksum file itself. The preview displays the ZIP text and file listing
without extracting it.

Default mode shows an incomplete draft with missing inputs and placeholders.
Add `--complete` to require the defined inputs and consistency checks. A complete
snapshot is not release approval: APK signatures, Android metadata, manifest schema,
runtime behavior and actual GitHub/Nexus formatting still need their own checks.
An email draft may still need the eventual upload URL or status.

Use `python3 tools/preview-release.py --verify release-artifacts/v0.8.0-beta/candidates/code-4/review`
to compare snapshot files against `review.sha256`. This checks integrity, not authenticity or approval.
Keep the whole review folder out of uploaded assets. Existing output directories
are refused; failed writes can leave an incomplete directory, so choose a new one.
Outputs inside the repository must be ignored. No new version code is reserved.

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
