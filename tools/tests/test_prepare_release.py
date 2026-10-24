"""Exercise release preparation only in disposable local Git repositories."""

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

    def git(self, *args):
        return subprocess.check_output(
            ["git", *args], cwd=self.repo, env=self.env, text=True, stderr=subprocess.PIPE
        ).strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "Fixture input")
        return self.git("rev-parse", "HEAD")

    def run_prepare(self, version=VERSION, code="4"):
        return subprocess.run(
            ["bash", "tools/prepare-release.sh", version, code],
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
