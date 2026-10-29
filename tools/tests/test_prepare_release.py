"""Exercise release preparation only in disposable local Git repositories."""

import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VERSION = "v0.8.0-beta"
HISTORY = "## v0.7.0-beta - 2026-06-29\n\n### Changed\n\n- Historical sentinel.\n"
CHANGELOG = (
    "# Changelog\n\n## v0.8.0-beta - Unreleased\n\n"
    "### Added\n\n- feat: A clear change.\n\n"
    "### Fixed\n\n- fix: Another change.\n\n" + HISTORY
)


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="dml-release-test-")
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.env = os.environ.copy()
        for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"):
            self.env.pop(key, None)
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        for name in ("tools/prepare-release.sh", "releases/templates/release-notes-template.md"):
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
        self.changelog = self.repo / "releases/changelog.md"
        self.changelog.write_text(CHANGELOG)
        self.version = self.repo / "version.properties"
        self.version.write_text("VERSION_NAME=v0.7.0-beta\nVERSION_CODE=3\n")
        self.notes = self.repo / f"releases/notes/{VERSION}.md"
        self.git("init", "--template=", "--initial-branch=release/" + VERSION)
        self.git("config", "user.name", "Release fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("remote", "add", "origin", "git@github.com:example/Dml.git")
        self.initial = self.commit()
        self.git("tag", "v0.7.0-beta")
        self.git("commit", "--allow-empty", "-m", "Changes after published baseline")

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.repo, env=self.env, text=True, stderr=subprocess.PIPE
        ).strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "Fixture input")
        return self.git("rev-parse", "HEAD")

    def prepare_command(self, version=VERSION, code=None, previous="v0.7.0-beta",
                        highest="3", evidence="Owner inspected fixture release", baseline=True,
                        extra=()):
        command = ["bash", "tools/prepare-release.sh", version]
        if code is not None:
            command.append(code)
        if baseline:
            command.extend(["--previous-tag", previous, "--confirmed-offline-code", highest,
                            "--offline-evidence", evidence])
        return command + list(extra)

    def run_prepare(self, **kwargs):
        return subprocess.run(
            self.prepare_command(**kwargs),
            cwd=self.repo, env=self.env, text=True, capture_output=True,
        )

    def assert_refused(self, message=None, **kwargs):
        paths = (self.version, self.changelog, self.notes)
        before = {p: p.read_bytes() if p.is_file() else None for p in paths}
        result = self.run_prepare(**kwargs)
        self.assertNotEqual(0, result.returncode, result.stdout)
        if message:
            self.assertIn(message, result.stderr)
        self.assertEqual(before, {p: p.read_bytes() if p.is_file() else None for p in paths})

    def change_changelog(self, text):
        self.changelog.write_text(text)
        return self.commit()

    def test_rollover_copies_categories_and_links_entries_without_commit_dump(self):
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        notes = self.notes.read_text()
        self.assertIn("## Changes\n\n### Added", notes)
        self.assertIn("### Fixed", notes)
        link = f"([`{self.initial[:7]}`](https://github.com/example/Dml/commit/{self.initial}))"
        self.assertIn("- feat: A clear change. " + link, notes)
        self.assertIn("- fix: Another change. " + link, notes)
        self.assertNotIn("## Commits", notes)
        self.assertNotIn("Historical sentinel", notes)
        self.assertIn("## Upgrade Notes", notes)
        rolled = self.changelog.read_text()
        self.assertIn("## Unreleased\n\n### Added\n", rolled)
        self.assertRegex(rolled, r"## v0\.8\.0-beta - \d{4}-\d{2}-\d{2}")
        self.assertTrue(rolled.endswith(HISTORY))
        self.assertNotIn("/commit/", rolled)
        self.assertEqual(f"VERSION_NAME={VERSION}\nVERSION_CODE=4\n", self.version.read_text())
        self.assert_refused("working tree must be clean")

    def test_generic_unreleased_and_https_origin(self):
        self.change_changelog(CHANGELOG.replace(VERSION + " - Unreleased", "Unreleased"))
        self.git("remote", "set-url", "origin", "https://github.com/example/Dml.git")
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("https://github.com/example/Dml/commit/", self.notes.read_text())

    def test_explicit_overrides_resolve_short_hashes_and_deduplicate(self):
        first = self.initial
        (self.repo / "implementation.txt").write_text("second implementation")
        second = self.commit()
        self.change_changelog(CHANGELOG.replace(
            "- feat: A clear change.",
            f"- feat: A clearer change. <!-- commits: {first[:8]}, {second}, {first} -->",
        ))
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        entry = next(line for line in self.notes.read_text().splitlines() if "A clearer change" in line)
        self.assertEqual(2, entry.count("/commit/"))
        self.assertIn("/commit/" + first, entry)
        self.assertIn("/commit/" + second, entry)
        self.assertNotIn("<!--", entry)
        self.assertIn("<!-- commits:", self.changelog.read_text())

    def test_fenced_examples_are_not_sections_or_entries(self):
        example = "\n```markdown\n## Unreleased\n- Example, not a change.\n```\n"
        self.change_changelog(CHANGELOG.replace("### Fixed", example + "\n### Fixed"))
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(example, self.notes.read_text())

    def test_multiline_entry_preserves_continuation_and_unicode(self):
        self.change_changelog(CHANGELOG.replace("A clear change.", "A clear Ω change.\n  Keep this detail."))
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("  Keep this detail.", self.notes.read_text())
        self.assertIn("A clear Ω change.", self.notes.read_text())

    def test_next_release_uses_fresh_section_and_preserves_previous_release(self):
        self.assertEqual(0, self.run_prepare().returncode)
        previous = self.changelog.read_text().split("## v0.8.0-beta - ", 1)[1]
        self.change_changelog(self.changelog.read_text().replace("### Added\n", "### Added\n\n- Next change.\n", 1))
        self.git("branch", "-m", "release/v0.9.0-beta")
        result = self.run_prepare(version="v0.9.0-beta", code="5")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.changelog.read_text().endswith(previous))
        notes = (self.repo / "releases/notes/v0.9.0-beta.md").read_text()
        self.assertIn("- Next change.", notes)
        self.assertNotIn("A clear change", notes)

    def test_missing_duplicate_mismatched_or_empty_unreleased_is_refused(self):
        cases = (
            (HISTORY, "exactly one unreleased"),
            (CHANGELOG + "\n## Unreleased\n", "exactly one unreleased"),
            (CHANGELOG.replace(VERSION + " - Unreleased", "v0.9.0-beta - Unreleased"), "does not match"),
            ("# Changelog\n\n## Unreleased\n\n### Added\n\n-\n<!-- TODO -->\n", "no changes"),
            (CHANGELOG + "\n```\n", "unclosed code fence"),
            (CHANGELOG + "\n## v0.8.0-beta - 2026-01-01\n", "already contains"),
        )
        for content, message in cases:
            with self.subTest(message=message):
                self.change_changelog(content)
                self.assert_refused(message)

    def test_invalid_override_is_refused_before_any_write(self):
        for ref in ("", "not-a-sha", "f" * 40):
            with self.subTest(ref=ref):
                self.change_changelog(CHANGELOG.replace("A clear change.", f"A clear change. <!-- commits: {ref} -->"))
                self.assert_refused()

    def test_misplaced_or_malformed_override_is_refused(self):
        for suffix in ("\n  <!-- commits: abc1234 -->", " <!-- commits: abc1234 --> more text"):
            with self.subTest(suffix=suffix):
                self.change_changelog(CHANGELOG.replace("A clear change.", "A clear change." + suffix))
                self.assert_refused("entry's bullet line")

    def test_unrelated_commit_override_is_refused(self):
        unrelated = self.git("commit-tree", "HEAD^{tree}", "-m", "Unrelated fixture")
        self.change_changelog(CHANGELOG.replace("A clear change.", f"A clear change. <!-- commits: {unrelated} -->"))
        self.assert_refused()

    def test_missing_or_credential_bearing_origin_is_refused(self):
        self.git("remote", "remove", "origin")
        self.assert_refused()
        self.git("remote", "add", "origin", "https://token@github.com/example/Dml.git")
        self.assert_refused("without credentials")

    def test_shallow_history_is_refused(self):
        source = self.repo
        clone = source / "clone"
        self.git("clone", "--depth=1", source.as_uri(), str(clone))
        self.repo = clone
        self.version = clone / "version.properties"
        self.changelog = clone / "releases/changelog.md"
        self.notes = clone / f"releases/notes/{VERSION}.md"
        self.assert_refused("Full Git history")

    def test_existing_branch_tree_tag_code_and_note_guards(self):
        self.assert_refused("greater than the current", code="3")
        self.assert_refused("Version must look", version="../bad")
        self.git("branch", "-m", "main")
        self.assert_refused("must run from")
        self.git("branch", "-m", "release/" + VERSION)
        (self.repo / "unrelated.txt").write_text("keep")
        self.assert_refused("working tree must be clean")
        self.commit()
        self.git("tag", VERSION)
        self.assert_refused("tag already exists")
        self.git("tag", "-d", VERSION)
        self.notes.parent.mkdir()
        self.notes.write_text("existing notes")
        self.commit()
        self.assert_refused("Release notes already exist")

    def test_automatic_code_exceeds_local_and_confirmed_distribution(self):
        result = self.run_prepare(highest="12")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("VERSION_CODE=13\n", self.version.read_text())
        self.assertIn("No publication lookup was performed", result.stdout)
        self.assertIn("Confirmed distributed code (offline): 12", result.stdout)
        self.assertIn("Baseline evidence: Owner inspected fixture release", result.stdout)

    def test_explicit_override_must_exceed_both_floors(self):
        self.assert_refused("greater than confirmed", highest="12", code="12")
        result = self.run_prepare(highest="12", code="20")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("VERSION_CODE=20\n", self.version.read_text())

    def test_android_code_range_and_shell_overflow_are_refused(self):
        for value in ("0", "-1", "1.5", "2100000001", "999999999999999999999999999", "x"):
            with self.subTest(value=value):
                self.assert_refused(code=value)
                self.assert_refused(highest=value)
        self.assert_refused("Next VERSION_CODE", highest="2100000000")
        result = self.run_prepare(highest="2099999999")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("VERSION_CODE=2100000000\n", self.version.read_text())

    def test_missing_or_ambiguous_baseline_input_is_refused(self):
        self.assert_refused("Require --previous-tag", baseline=False)
        self.assert_refused(evidence=" ")
        self.assert_refused(evidence="one\ntwo")
        self.assert_refused(evidence="x" * 501)
        self.assert_refused(extra=("--previous-tag", "v0.6.0-beta"))
        self.assert_refused(extra=("--unknown", "value"))
        self.assert_refused(extra=("--offline-evidence",))

    def test_missing_nonancestor_or_mismatched_previous_tag_is_refused(self):
        self.assert_refused(previous="absent")
        self.assert_refused(previous="../HEAD")
        self.assert_refused(previous="v0.7.0-beta^{commit}")
        other = self.git("commit-tree", "HEAD^{tree}", "-m", "Unrelated release")
        self.git("tag", "unrelated", other)
        self.assert_refused(previous="unrelated")
        self.git("tag", "mismatched", self.initial)
        self.assert_refused("release identity", previous="mismatched")

    def test_empty_range_is_refused(self):
        self.git("tag", "-f", "v0.7.0-beta")
        self.assert_refused("empty commit range")

    def test_stale_offline_floor_below_previous_release_is_refused(self):
        self.assert_refused("baseline is stale", highest="2")

    def test_annotated_baseline_tag_is_supported(self):
        self.git("tag", "-d", "v0.7.0-beta")
        self.git("tag", "-a", "v0.7.0-beta", self.initial, "-m", "Published baseline")
        result = self.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_maximum_local_code_prevents_overflow(self):
        self.version.write_text("VERSION_NAME=v0.7.0-beta\nVERSION_CODE=2100000000\n")
        self.commit()
        self.assert_refused("Next VERSION_CODE")

    def test_live_lock_refuses_without_changing_files(self):
        lock = self.repo / ".git/dml-release-prepare.lock"
        with lock.open("w") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assert_refused("Another release preparation")
        # The permanent lock file does not prevent a later invocation.
        self.assertEqual(0, self.run_prepare().returncode)

    def test_simultaneous_preparations_reserve_only_once(self):
        first = subprocess.Popen(self.prepare_command(), cwd=self.repo, env=self.env,
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        second = subprocess.Popen(self.prepare_command(), cwd=self.repo, env=self.env,
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        first_output = first.communicate(timeout=20)
        second_output = second.communicate(timeout=20)
        self.assertEqual([0, 1], sorted([first.returncode, second.returncode]),
                         (first_output, second_output))
        self.assertIn("VERSION_CODE=4\n", self.version.read_text())
        self.assertEqual(1, self.changelog.read_text().count("## Unreleased"))

    def test_linked_worktree_is_refused(self):
        linked = self.repo / "linked"
        self.git("worktree", "add", "--detach", str(linked), "HEAD")
        self.repo = linked
        self.version = linked / "version.properties"
        self.changelog = linked / "releases/changelog.md"
        self.notes = linked / f"releases/notes/{VERSION}.md"
        self.assert_refused("linked worktree")

    def test_committed_preparation_retry_is_refused_without_another_bump(self):
        self.assertEqual(0, self.run_prepare().returncode)
        self.commit()
        self.assert_refused("Release notes already exist")
        self.assertIn("VERSION_CODE=4\n", self.version.read_text())

    def test_partial_preparation_is_refused_without_another_bump(self):
        # An interrupted run may leave only the exclusive notes output written.
        self.notes.parent.mkdir()
        self.notes.write_text("partial notes from interrupted preparation")
        self.assert_refused("working tree must be clean")
        self.assertIn("VERSION_CODE=3\n", self.version.read_text())

    def test_output_symlink_outside_repository_is_refused(self):
        outside = tempfile.TemporaryDirectory(prefix="dml-release-outside-")
        self.addCleanup(outside.cleanup)
        self.notes.parent.symlink_to(outside.name, target_is_directory=True)
        self.commit()
        self.assert_refused("must stay inside")
        self.assertEqual([], list(Path(outside.name).iterdir()))

    def test_malformed_template_and_unavailable_output_refuse_without_mutation(self):
        template = self.repo / "releases/templates/release-notes-template.md"
        original = template.read_text()
        template.write_text("Malformed template")
        self.commit()
        self.assert_refused()
        template.write_text(original)
        self.commit()
        self.notes.mkdir(parents=True)
        self.assert_refused()


if __name__ == "__main__":
    unittest.main()
