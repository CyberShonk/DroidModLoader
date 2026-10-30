"""Synthetic APKs and SDK executables, never signing keys or real release builds."""

import copy
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('package_release', ROOT / 'tools/package-release.py')
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)
VERSION, CODE, CERT = 'v0.8.0-beta', 4, 'a' * 64


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='dml-package-fixture-')
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.root = self.folder / 'repo'
        self.root.mkdir()
        env = os.environ.copy()
        for key in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            env.pop(key, None)
        env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1', PYTHONDONTWRITEBYTECODE='1')
        environment = patch.dict(os.environ, env, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        self.git('init', '--template=', '--initial-branch=main')
        self.git('config', 'user.name', 'Packaging fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        # Ignore the path itself too, so linked-output tests reach the path guard.
        (self.root / '.gitignore').write_text('/release-artifacts\n')
        (self.root / 'version.properties').write_text(f'VERSION_NAME={VERSION}\nVERSION_CODE={CODE}\n')
        for name in (PACKAGE.NOTICE, PACKAGE.README, 'LICENSE'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, path)
        for name in ('app/build.gradle.kts', 'gradle/libs.versions.toml'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture dependencies\n')
        self.commit()
        self.source_commit = self.git('rev-parse', 'HEAD')
        self.apk = self.folder / 'input.apk'
        self.tools = self.folder / 'sdk'
        self.tools.mkdir()
        fake = '''#!/usr/bin/env python3
import sys,json,zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[-1]) as z:
    value=json.loads(z.read('fixture.json'))
if Path(sys.argv[0]).name=='apksigner':
    if not value['valid']: sys.exit(1)
    print('Signer #1 certificate SHA-256 digest: '+value['certificate'])
    if value.get('multiple'): print('Signer #2 certificate SHA-256 digest: '+'b'*64)
else:
    print("package: name='%s' versionCode='%s' versionName='%s'" % (value['app'],value['code'],value['version']))
    if value['debug']: print('application-debuggable')
'''
        for name in ('apksigner', 'aapt2'):
            path = self.tools / name
            path.write_text(fake)
            path.chmod(0o755)
        self.build = {'schemaVersion': 1, 'commit': self.source_commit, 'dirty': False,
                      'applicationId': PACKAGE.BASELINE.APPLICATION_ID, 'versionName': VERSION,
                      'versionCode': CODE, 'variant': 'release',
                      'trackedDiffSha256': hashlib.sha256(b'').hexdigest(),
                      'untrackedFilesSha256': hashlib.sha256(b'{}').hexdigest(),
                      'gradle': '9.4.1', 'androidGradlePlugin': '9.2.1', 'kotlin': '2.2.10',
                      'javaVersion': '21.0.1', 'javaVendor': 'Fixture JVM', 'compileSdk': '36.0',
                      'minSdk': '28', 'targetSdk': '36', 'buildTools': '36.0.0',
                      'builtAtUtc': '2026-10-30T00:00:00.123456789Z', 'ciRunUrl': ''}
        self.sdk = {'app': PACKAGE.BASELINE.APPLICATION_ID, 'code': CODE, 'version': VERSION,
                    'certificate': CERT, 'debug': False, 'valid': True}
        self.write_apk()
        self.parent = self.root / 'release-artifacts' / VERSION / 'candidates' / f'code-{CODE}'
        self.output = self.parent / 'package'

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-m', 'Fixture source')

    def write_apk(self, *, build=None, sdk=None, missing=False, duplicate=False):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('fixture.json', json.dumps(self.sdk if sdk is None else sdk))
            if not missing:
                archive.writestr('assets/dml-build-provenance.json', json.dumps(self.build if build is None else build))
                if duplicate:
                    archive.writestr('assets/dml-build-provenance.json', '{}')

    def package(self, **kwargs):
        values = dict(root=self.root, source_apk=self.apk, version=VERSION, code=CODE,
                      commit=self.source_commit, certificate=CERT, tools=self.tools)
        values.update(kwargs)
        return PACKAGE.package(**values)

    def verify(self, *, github_only=False):
        with tempfile.TemporaryDirectory(dir=self.folder) as logs:
            PACKAGE.verify_bundle(self.output, VERSION, CODE, self.source_commit, CERT,
                                  self.tools, Path(logs), github_only)

    def reset_failed_output(self):
        if self.parent.exists():
            shutil.rmtree(self.parent)

    def test_package_contains_exact_four_github_assets_and_identical_nexus_bytes(self):
        self.assertEqual(self.output, self.package())
        self.assertFalse((self.parent / '.package-incomplete').exists())
        apk, receipt, manifest, nexus = PACKAGE.names(VERSION)
        assets = self.output / 'github/assets'
        self.assertEqual({apk, receipt, manifest, 'SHA256SUMS.txt'}, {p.name for p in assets.iterdir()})
        self.assertEqual(self.apk.read_bytes(), (assets / apk).read_bytes())
        record = json.loads((assets / receipt).read_text())
        self.assertEqual(self.build, record['build'])
        self.assertEqual('verified', record['signing']['status'])
        with zipfile.ZipFile(self.output / 'nexus' / nexus) as archive:
            self.assertEqual({apk, receipt, 'README-NEXUS.txt', 'LICENSE', 'THIRD-PARTY-NOTICES.txt', 'SHA256SUMS.txt'}, set(archive.namelist()))
            self.assertEqual((assets / apk).read_bytes(), archive.read(apk))
            self.assertEqual((assets / receipt).read_bytes(), archive.read(receipt))
            self.assertIn(VERSION, archive.read('README-NEXUS.txt').decode())
        inventory = json.loads((assets / manifest).read_text())
        self.assertEqual(['github', 'github', 'nexus'], [r['destination'] for r in inventory['artifacts']])
        self.assertEqual(PACKAGE.NEXUS_PAGE, inventory['nexusPageUrl'])
        self.assertNotIn(str(self.folder), (assets / manifest).read_text())
        self.verify()
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_cli_packaging_and_github_only_check_keep_inspection_records(self):
        common = [sys.executable, '-B', str(ROOT / 'tools/package-release.py'), '--root', str(self.root),
                  '--version', VERSION, '--code', str(CODE), '--source-commit', self.source_commit,
                  '--certificate-sha256', CERT, '--build-tools', str(self.tools)]
        result = subprocess.run([*common, '--apk', str(self.apk)], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        shutil.rmtree(self.output / 'nexus')
        result = subprocess.run([*common, '--check-directory', str(self.output), '--github-only'], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Nexus ZIP not checked', result.stdout)
        logs = self.root / 'release-artifacts' / VERSION / 'maintainer'
        self.assertEqual(2, len(list(logs.glob('package-code-*'))))
        result = subprocess.run([*common, '--check-directory', str(self.output)], text=True, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(3, len(list(logs.glob('package-code-*'))))
        self.assertTrue(all(list(p.glob('*signature.txt')) for p in logs.iterdir()))

    def test_unsigned_wrong_signer_debug_package_and_metadata_are_refused(self):
        for change in ({'valid': False}, {'certificate': 'b'*64}, {'multiple': True}, {'debug': True},
                       {'app': PACKAGE.BASELINE.APPLICATION_ID + '.dev'}, {'version': 'wrong'}, {'code': 3}):
            with self.subTest(change=change):
                self.write_apk(sdk=dict(self.sdk, **change))
                with self.assertRaises(ValueError):
                    self.package()
                self.assertFalse(self.output.exists())
                self.reset_failed_output()

    def test_missing_or_private_or_dirty_provenance_is_refused(self):
        for change in ({'dirty': True}, {'commit': 'b'*40}, {'versionName': 'wrong'}, {'versionCode': True},
                       {'variant': 'debug'}, {'privatePath': '/private/key'}, {'schemaVersion': True},
                       {'javaVendor': '/home/user/JDK'}, {'ciRunUrl': 'https://token@github.com/private'},
                       {'trackedDiffSha256': 'b'*64}):
            with self.subTest(change=change):
                self.write_apk(build=dict(self.build, **change))
                with self.assertRaises(ValueError):
                    self.package()
                self.assertFalse(self.output.exists())
                self.reset_failed_output()
        self.write_apk(missing=True)
        with self.assertRaisesRegex(ValueError, 'provenance'):
            self.package()

    def test_stale_input_receipt_cannot_be_relabelled(self):
        path = self.apk.with_name(self.apk.name + '.receipt.json')
        path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.package()
        self.assertFalse(self.output.exists())

    def test_matching_input_receipt_is_regenerated_for_final_name(self):
        logs = self.folder / 'inspection'
        logs.mkdir()
        receipt = PACKAGE.candidate_identity(self.apk, VERSION, CODE, self.source_commit, CERT, self.tools, logs, 'input')
        self.apk.with_name(self.apk.name + '.receipt.json').write_text(json.dumps(receipt))
        self.package()
        self.verify()

    def test_dirty_source_wrong_commit_and_version_fail_before_outputs(self):
        for change in ({'commit': 'b'*40}, {'code': 5}, {'version': 'v0.9.0-beta'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.package(**change)
        (self.root / 'unrelated.txt').write_text('dirty')
        with self.assertRaisesRegex(ValueError, 'clean'):
            self.package()
        self.assertFalse(self.parent.exists())

    def test_dependency_change_requires_notice_review(self):
        (self.root / 'app/build.gradle.kts').write_text('changed dependencies')
        self.commit()
        self.source_commit = self.git('rev-parse', 'HEAD')
        with self.assertRaisesRegex(ValueError, 'notices were reviewed'):
            self.package()

    def test_retry_refuses_existing_success_and_incomplete_staging(self):
        self.package()
        before = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.package()
        self.assertEqual(before, {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()})
        self.reset_failed_output()
        with patch.object(PACKAGE, 'write_json', side_effect=OSError('fixture interruption')):
            with self.assertRaisesRegex(OSError, 'interruption'):
                self.package()
        self.assertFalse(self.output.exists())
        self.assertTrue((self.parent / '.package-incomplete').is_dir())
        with self.assertRaisesRegex(ValueError, 'Incomplete package'):
            self.package()

    def test_concurrent_operation_is_refused(self):
        self.parent.mkdir(parents=True)
        with (self.parent / '.candidate.lock').open('w') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError, 'Another candidate'):
                self.package()
        self.assertFalse(self.output.exists())

    def test_symlinked_apk_parent_output_and_lock_are_refused(self):
        linked = self.folder / 'linked'
        linked.symlink_to(self.folder, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Linked'):
            self.package(source_apk=linked / self.apk.name)
        (self.root / 'release-artifacts').symlink_to(self.folder, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Linked'):
            self.package()
        (self.root / 'release-artifacts').unlink()
        self.parent.mkdir(parents=True)
        (self.parent / '.candidate.lock').symlink_to(self.apk)
        with self.assertRaises(OSError):
            self.package()

    def test_changed_apk_and_manifest_and_extra_files_fail_verification(self):
        self.package()
        assets = self.output / 'github/assets'
        apk, receipt, manifest, nexus = PACKAGE.names(VERSION)
        original = (assets / manifest).read_bytes()
        value = json.loads(original)
        value['artifacts'][0]['sha256'] = 'b'*64
        (assets / manifest).write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'size/hash'):
            self.verify()
        (assets / manifest).write_bytes(original)
        (assets / 'private.txt').write_text('not an asset')
        with self.assertRaisesRegex(ValueError, 'Unexpected'):
            self.verify()
        (assets / 'private.txt').unlink()
        with (assets / apk).open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'receipt'):
            self.verify()

    def test_github_only_verification_does_not_require_nexus_download(self):
        self.package()
        shutil.rmtree(self.output / 'nexus')
        self.verify(github_only=True)
        with self.assertRaises(ValueError):
            self.verify()

    def test_zip_extra_or_changed_payload_fails_even_with_updated_outer_hashes(self):
        self.package()
        assets = self.output / 'github/assets'
        apk, receipt, manifest, nexus = PACKAGE.names(VERSION)
        path = self.output / 'nexus' / nexus
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr('private.txt', 'must not ship')
        PACKAGE.write_json(assets / 'new-manifest', PACKAGE.inventory(assets / apk, assets / receipt, path,
                            VERSION, CODE, self.source_commit, CERT))
        (assets / 'new-manifest').replace(assets / manifest)
        (assets / 'SHA256SUMS.txt').write_text(PACKAGE.sums((assets / apk, assets / receipt, assets / manifest, path)))
        with self.assertRaisesRegex(ValueError, 'ZIP must contain only'):
            self.verify()

    def test_input_change_during_packaging_never_publishes(self):
        verify = PACKAGE.verify_bundle
        def changing(*args, **kwargs):
            verify(*args, **kwargs)
            with self.apk.open('ab') as stream:
                stream.write(b'changed')
        with patch.object(PACKAGE, 'verify_bundle', side_effect=changing):
            with self.assertRaisesRegex(ValueError, 'Input APK changed'):
                self.package()
        self.assertFalse(self.output.exists())

    def test_sdk_mutation_cannot_be_accepted_as_verified_bytes(self):
        inspect = PACKAGE.BASELINE.inspect
        def changing(apk, *args):
            result = inspect(apk, *args)
            with apk.open('ab') as stream:
                stream.write(b'changed during inspection')
            return result
        with patch.object(PACKAGE.BASELINE, 'inspect', side_effect=changing):
            with self.assertRaisesRegex(ValueError, 'changed during inspection'):
                self.package()
        self.assertFalse(self.output.exists())

    def test_oversized_apk_is_rejected_before_sdk(self):
        with patch.object(PACKAGE, 'MAX_APK', 1):
            with self.assertRaisesRegex(ValueError, 'oversized'):
                self.package()
        self.assertFalse(self.parent.exists())


if __name__ == '__main__':
    unittest.main()
