"""Exercise preview snapshots using local fixture Git history and fake APK bytes."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location('preview_release', Path(__file__).resolve().parents[1] / 'preview-release.py')
PREVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREVIEW)
VERSION = 'v0.8.0-beta'
CERT = 'a' * 64
BASE = 'DroidModLoader-' + VERSION
APK = BASE + '.apk'
RECEIPT = APK + '.receipt.json'
MANIFEST = BASE + '-manifest.json'
NEXUS = BASE + '-Nexus.zip'


class PreviewReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='dml-preview-test-')
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.root = self.folder / 'repo'
        self.root.mkdir()
        self.inputs = self.folder / 'inputs'
        self.inputs.mkdir()
        self.output = self.folder / 'preview'
        self.env = os.environ.copy()
        for key in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            self.env.pop(key, None)
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        self.environment = patch.dict(os.environ, self.env, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.git('init', '--template=', '--initial-branch=main')
        self.git('config', 'user.name', 'Preview fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        for name in ('README.md', 'CONTRIBUTING.md', 'docs/user-guide.md', 'docs/troubleshooting.md', 'releases/changelog.md', 'LICENSE'):
            self.write(self.root / name, '# Published fixture\n')
        self.write(self.root / 'version.properties', 'VERSION_NAME=v0.7.0-beta\nVERSION_CODE=3\n')
        self.commit()
        self.git('tag', 'v0.7.0-beta')
        self.write(self.root / 'README.md', '# Candidate fixture\n')
        self.write(self.root / 'version.properties', f'VERSION_NAME={VERSION}\nVERSION_CODE=4\n')
        self.source = self.commit()
        for name in PREVIEW.TEXT_INPUTS:
            self.write(self.inputs / name, 'Reviewed candidate text\n')
        self.write(self.inputs / 'github/prerelease.txt', 'true\n')
        self.write(self.inputs / 'previous/nexus-description.bbcode', 'Old description\n')
        self.write(self.inputs / 'nexus/description.bbcode', '[b]New description[/b]\n')
        self.write(self.inputs / PREVIEW.EMAIL, 'Private unsent draft: {{ACTUAL_UPLOAD_URL}}\n')
        self.provenance = {'schemaVersion': 1, 'commit': self.source, 'dirty': False,
                           'applicationId': 'com.shonkware.droidmodloader', 'versionName': VERSION,
                           'versionCode': 4, 'variant': 'release'}
        self.make_artifacts()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], env=self.env, text=True, stderr=subprocess.PIPE).strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-m', 'Fixture source')
        return self.git('rev-parse', 'HEAD')

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def make_artifacts(self):
        assets = self.inputs / 'github/assets'
        assets.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(assets / APK, 'w') as archive:
            archive.writestr('assets/dml-build-provenance.json', json.dumps(self.provenance))
        record = {'schemaVersion': 1, 'apk': {'fileName': APK, 'sizeBytes': (assets / APK).stat().st_size,
                  'sha256': PREVIEW.digest(assets / APK)}, 'build': self.provenance,
                  'signing': {'status': 'verified', 'certificateSha256': [CERT]}}
        self.write(assets / RECEIPT, json.dumps(record))
        # A preview checks JSON syntax/display, not an as-yet undefined packaging schema.
        self.write(assets / MANIFEST, '{"fixtureOnly": "manifest schema is not qualified here"}')
        self.make_zip()
        self.outer_sums()

    def make_zip(self, apk_data=None, extra=None, linked=False, readme=b'Install this APK.\n'):
        assets = self.inputs / 'github/assets'
        payloads = {APK: (assets / APK).read_bytes() if apk_data is None else apk_data,
                    RECEIPT: (assets / RECEIPT).read_bytes(), 'README-NEXUS.txt': readme,
                    'LICENSE': b'Fixture license\n', 'THIRD-PARTY-NOTICES.txt': b'Fixture notices\n'}
        payloads['SHA256SUMS.txt'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in payloads.items()).encode()
        with zipfile.ZipFile(self.inputs / 'nexus' / NEXUS, 'w') as archive:
            for name, data in payloads.items():
                if linked and name == 'LICENSE':
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = 0o120777 << 16
                    archive.writestr(entry, data)
                else:
                    archive.writestr(name, data)
            if extra:
                archive.writestr(extra, 'not allowed')

    def outer_sums(self):
        assets = self.inputs / 'github/assets'
        files = [(name, assets / name) for name in (APK, RECEIPT, MANIFEST)]
        files.append((NEXUS, self.inputs / 'nexus' / NEXUS))
        self.write(assets / 'SHA256SUMS.txt', ''.join(f'{PREVIEW.digest(path)}  {name}\n' for name, path in files))

    def preview(self, complete=False, **kwargs):
        options = dict(root=self.root, inputs=self.inputs, output=self.output, version=VERSION, code=4,
                       source_ref=self.source, previous_ref='v0.7.0-beta', certificate=CERT, complete=complete)
        options.update(kwargs)
        return PREVIEW.create_preview(**options)

    def test_complete_snapshot_shows_every_surface_and_copies_exact_files(self):
        self.assertEqual([], self.preview(complete=True))
        page = (self.output / 'index.html').read_text()
        for label in ('GitHub release title', 'Nexus changelog', 'Nexus page description', 'Public documentation: README.md',
                      'Source documentation changes', 'Proposed Nexus description changes', 'APK receipt', 'Release manifest',
                      'Nexus ZIP contents', 'THIRD-PARTY-NOTICES.txt', 'Unsent support email', 'NOT RELEASE APPROVAL'):
            self.assertIn(label, page)
        self.assertIn('not independent signature verification', page)
        self.assertIn('schema qualification remains packaging work', page)
        self.assertEqual((self.inputs / 'github/assets' / APK).read_bytes(), (self.output / 'files/github/assets' / APK).read_bytes())
        self.assertFalse((self.output / 'files/github/assets' / NEXUS).exists())
        PREVIEW.verify(self.output)

    def test_draft_missing_text_and_artifacts_is_honest(self):
        (self.inputs / 'github/body.md').unlink()
        (self.inputs / 'github/assets' / APK).unlink()
        issues = self.preview()
        self.assertTrue(any('github/body.md' in issue for issue in issues))
        self.assertIn('DRAFT PREVIEW - NOT FOR SUBMISSION', (self.output / 'index.html').read_text())
        self.assertIn('Missing input', (self.output / 'review.txt').read_text())

    def test_complete_missing_inputs_or_placeholders_is_refused(self):
        self.write(self.inputs / 'nexus/description.bbcode', '{{VERSION}}\n')
        with self.assertRaisesRegex(ValueError, 'Unresolved placeholder'):
            self.preview(complete=True)
        self.assertFalse(self.output.exists())

    def test_html_and_network_content_is_escaped_not_executed(self):
        self.write(self.inputs / 'github/body.md', '<script>alert(1)</script><img src="https://example.invalid/track">\n')
        self.preview()
        page = (self.output / 'index.html').read_text()
        self.assertNotIn('<script>', page)
        self.assertNotIn('<img ', page)
        self.assertIn('&lt;script&gt;', page)
        self.assertIn("default-src 'none'", page)

    def test_docs_are_from_exact_commit_not_dirty_working_tree(self):
        self.write(self.root / 'README.md', 'Uncommitted sentinel\n')
        self.preview(complete=True)
        page = (self.output / 'index.html').read_text()
        self.assertIn('Candidate fixture', page)
        self.assertNotIn('Uncommitted sentinel', page)
        self.assertIn('Published fixture', page)  # Baseline diff.

    def test_source_version_or_signer_disagreement_is_not_complete(self):
        with self.assertRaisesRegex(ValueError, 'version.properties'):
            self.preview(complete=True, code=5)
        with self.assertRaisesRegex(ValueError, 'Recorded signer'):
            self.preview(complete=True, certificate='b' * 64)
        with self.assertRaisesRegex(ValueError, 'certificate not supplied'):
            self.preview(complete=True, certificate=None)

    def test_receipt_and_embedded_provenance_disagreement_is_refused(self):
        record_path = self.inputs / 'github/assets' / RECEIPT
        record = json.loads(record_path.read_text())
        record['build']['commit'] = '0' * 40
        self.write(record_path, json.dumps(record))
        self.make_zip()
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'build identity'):
            self.preview(complete=True)

    def test_wrong_package_dirty_or_development_variant_is_refused(self):
        for field, value in (('applicationId', 'com.shonkware.droidmodloader.dev'), ('dirty', True), ('variant', 'debug'), ('schemaVersion', True)):
            with self.subTest(field=field):
                original = self.provenance[field]
                self.provenance[field] = value
                self.make_artifacts()
                with self.assertRaisesRegex(ValueError, 'build identity'):
                    self.preview(complete=True)
                self.provenance[field] = original

    def test_nexus_must_contain_identical_apk_and_only_allowed_entries(self):
        self.make_zip(apk_data=b'different APK')
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'differs from the GitHub APK'):
            self.preview(complete=True)
        for extra in ('../outside', 'maintainer-email.txt', 'private-key.pem'):
            self.make_zip(extra=extra)
            self.outer_sums()
            with self.assertRaisesRegex(ValueError, 'ZIP must contain only'):
                self.preview(complete=True)
        self.make_zip(linked=True)
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'linked'):
            self.preview(complete=True)

    def test_outer_checksum_mismatch_is_refused(self):
        self.write(self.inputs / 'github/assets/SHA256SUMS.txt', '0' * 64 + '  ' + APK + '\n')
        with self.assertRaisesRegex(ValueError, 'Checksum names or hashes'):
            self.preview(complete=True)

    def test_bad_receipt_does_not_hide_other_draft_metadata(self):
        self.write(self.inputs / 'github/assets' / RECEIPT, '{"schemaVersion":1,"schemaVersion":2}')
        issues = self.preview()
        self.assertTrue(any('Duplicate JSON key' in issue for issue in issues))
        page = (self.output / 'index.html').read_text()
        self.assertIn('Release manifest', page)
        self.assertIn('Outer checksums', page)

    def test_overwrite_and_overlapping_output_are_refused(self):
        self.preview()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.preview()
        with self.assertRaisesRegex(ValueError, 'must not overlap'):
            self.preview(output=self.inputs / 'preview')

    def test_repository_output_must_be_ignored(self):
        with self.assertRaisesRegex(ValueError, 'must be ignored'):
            self.preview(output=self.root / 'public-preview')
        self.write(self.root / '.git/info/exclude', 'local-previews/\n')
        self.assertEqual([], self.preview(output=self.root / 'local-previews/01', complete=True))

    def test_unexpected_assets_and_symlinked_input_are_refused(self):
        unexpected = self.inputs / 'github/assets/debug.apk'
        self.write(unexpected, 'not allowed')
        with self.assertRaisesRegex(ValueError, 'unexpected files'):
            self.preview()
        unexpected.unlink()
        target = self.inputs / 'github/body.md'
        target.unlink()
        target.symlink_to(self.root / 'README.md')
        with self.assertRaisesRegex(ValueError, 'Unsafe input'):
            self.preview()

    def test_large_text_is_refused_before_rendering(self):
        self.write(self.inputs / 'github/body.md', 'x' * (PREVIEW.MAX_TEXT + 1))
        with self.assertRaisesRegex(ValueError, 'text exceeds'):
            self.preview()

    def test_prerelease_flag_and_candidate_range_are_validated(self):
        self.write(self.inputs / 'github/prerelease.txt', 'yes')
        with self.assertRaisesRegex(ValueError, 'must be exactly true or false'):
            self.preview(complete=True)
        with self.assertRaisesRegex(ValueError, 'Version code'):
            self.preview(code=2100000001)
        with self.assertRaisesRegex(ValueError, 'Unsupported version'):
            self.preview(version='../v0.8.0-beta')

    def test_input_change_during_copy_leaves_no_finished_preview(self):
        copy = PREVIEW.copy_snapshot
        def change(source, destination, expected):
            if source == self.inputs / 'github/body.md':
                source.write_text('changed after review capture')
            return copy(source, destination, expected)
        with patch.object(PREVIEW, 'copy_snapshot', side_effect=change):
            with self.assertRaisesRegex(ValueError, 'Input changed'):
                self.preview()
        self.assertFalse((self.output / 'index.html').exists())

    def test_checksum_write_failure_does_not_publish_entry_page(self):
        write = Path.write_text
        def fail(path, *args, **kwargs):
            if path.name == 'review.sha256':
                raise OSError('Injected checksum write failure')
            return write(path, *args, **kwargs)
        with patch.object(Path, 'write_text', fail):
            with self.assertRaisesRegex(OSError, 'Injected checksum'):
                self.preview(complete=True)
        self.assertFalse((self.output / 'index.html').exists())
        self.assertTrue((self.output / 'index.html.pending').exists())

    def test_zip_and_metadata_placeholders_are_not_complete(self):
        self.make_zip(readme=b'{{UPGRADE_INSTRUCTIONS}}')
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'unresolved placeholder'):
            self.preview(complete=True)
        self.make_zip()
        self.write(self.inputs / 'github/assets' / MANIFEST, '{"pending":"{{VALUE}}"}')
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'Unresolved placeholder in release manifest'):
            self.preview(complete=True)

    def test_nonstandard_manifest_json_is_refused(self):
        self.write(self.inputs / 'github/assets' / MANIFEST, '{"invalid":NaN}')
        self.outer_sums()
        with self.assertRaisesRegex(ValueError, 'Nonstandard JSON number'):
            self.preview(complete=True)

    def test_verify_detects_changes_missing_and_extra_files(self):
        self.preview(complete=True)
        index = self.output / 'index.html'
        original = index.read_bytes()
        index.write_text('changed')
        with self.assertRaisesRegex(ValueError, 'snapshot changed'):
            PREVIEW.verify(self.output)
        index.write_bytes(original)
        extra = self.output / 'nested/review.sha256'
        self.write(extra, 'cannot hide an extra file under the checksum filename')
        with self.assertRaisesRegex(ValueError, 'snapshot changed'):
            PREVIEW.verify(self.output)
        extra.unlink()
        index.unlink()
        with self.assertRaisesRegex(ValueError, 'snapshot changed'):
            PREVIEW.verify(self.output)

    def test_verify_rejects_unsafe_paths_and_symlinks(self):
        self.preview()
        sums = self.output / 'review.sha256'
        original = sums.read_text()
        sums.write_text('a' * 64 + '  ../outside\n')
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            PREVIEW.verify(self.output)
        sums.write_text(original)
        (self.output / 'linked').symlink_to(self.root / 'README.md')
        with self.assertRaisesRegex(ValueError, 'Linked file'):
            PREVIEW.verify(self.output)

    def test_cli_generation_and_verification(self):
        tool = Path(PREVIEW.__file__).resolve()
        command = ['python3', '-B', str(tool), '--root', str(self.root), '--input', str(self.inputs),
                   '--output', str(self.output), '--version', VERSION, '--version-code', '4',
                   '--source-commit', self.source, '--previous-ref', 'v0.7.0-beta',
                   '--certificate-sha256', CERT, '--complete']
        result = subprocess.run(command, env=self.env, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        verify = subprocess.run(['python3', '-B', str(tool), '--verify', str(self.output)], text=True, capture_output=True)
        self.assertEqual(0, verify.returncode, verify.stderr)


if __name__ == '__main__':
    unittest.main()
