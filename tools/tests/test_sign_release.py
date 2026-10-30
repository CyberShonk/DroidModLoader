"""Signing orchestration fixtures use public strings and dummy handles, never keys."""

from contextlib import ExitStack
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch
import warnings

import test_package_release as fixtures

ROOT, VERSION, CODE = fixtures.ROOT, fixtures.VERSION, fixtures.CODE


SPEC = importlib.util.spec_from_file_location('sign_release', ROOT / 'tools/sign-release.py')
SIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIGN)
PUBLIC_CERT = b'PUBLIC_DUMMY_CERTIFICATE_BYTES_NOT_A_REAL_CERTIFICATE'
CERT = hashlib.sha256(PUBLIC_CERT).hexdigest()
STORE, KEY = 'PUBLIC_STORE_SAMPLE_é', 'PUBLIC_KEY_SAMPLE_水'


class SignReleaseTest(unittest.TestCase):
    git = fixtures.PackageReleaseTest.git
    commit = fixtures.PackageReleaseTest.commit
    write_apk = fixtures.PackageReleaseTest.write_apk

    def setUp(self):
        fixtures.PackageReleaseTest.setUp(self)
        self.sdk['certificate'] = CERT
        self.write_apk()
        self.receipt = self.apk.with_name(self.apk.name + '.receipt.json')
        self.write_receipt()
        (self.tools / 'zipalign').write_text('#!/usr/bin/env python3\nimport sys\nsys.exit(0)\n')
        (self.tools / 'zipalign').chmod(0o755)
        self.handle = self.folder / 'dummy-handle-not-a-keystore'
        self.handle.write_text('PUBLIC_DUMMY_HANDLE_NO_PRIVATE_KEY')
        self.events = []

    def write_receipt(self):
        self.receipt.write_text(json.dumps({'schemaVersion': 1, 'apk': {'fileName': self.apk.name,
            'sizeBytes': self.apk.stat().st_size, 'sha256': SIGN.BASELINE.digest(self.apk)},
            'build': self.build, 'signing': {'status': 'unsigned', 'certificateSha256': []}}))

    def native(self, command, phase, secret=None, certificate=False):
        self.events.append((command, phase, secret))
        if certificate:
            return PUBLIC_CERT
        if 'sign' in command:
            self.assertEqual((STORE + '\n' + KEY + '\n').encode(), secret)
            self.assertIn('--ks-pass', command)
            self.assertIn('--key-pass', command)
            self.assertEqual('false', command[command.index('--v4-signing-enabled')+1])
            shutil.copyfile(command[-1], command[command.index('--out')+1])

    def options(self, **kwargs):
        options = dict(root=self.root, source_apk=self.apk, version=VERSION, code=CODE,
                       commit=self.source_commit, certificate=CERT, tools=self.tools)
        options.update(kwargs)
        return options

    def execute(self, *, native=None, **kwargs):
        with ExitStack() as stack:
            stack.enter_context(patch.object(sys.stdin, 'isatty', return_value=True))
            stack.enter_context(patch.object(sys.stderr, 'isatty', return_value=True))
            stack.enter_context(patch.object(SIGN, 'password', side_effect=[STORE, KEY]))
            stack.enter_context(patch.object(SIGN, 'native', side_effect=native or self.native))
            return SIGN.sign(**self.options(execute=True, keystore=self.handle, alias='key0',
                                            keytool=self.tools / 'aapt2', **kwargs))

    def test_default_preflight_never_prompts_reads_key_or_signs(self):
        with patch.object(SIGN, 'password', side_effect=AssertionError('No prompt')):
            self.assertIsNone(SIGN.sign(**self.options()))
        self.assertFalse(self.parent.exists())
        with self.assertRaisesRegex(ValueError, 'explicit --execute'):
            SIGN.sign(**self.options(keystore=self.handle, alias='key0'))
        result = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/sign-release.py'),
            '--root', str(self.root), '--apk', str(self.apk), '--version', VERSION, '--code', str(CODE),
            '--source-commit', self.source_commit, '--certificate-sha256', CERT, '--build-tools', str(self.tools)],
            text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Key identity not checked', result.stdout)
        self.assertFalse(self.parent.exists())

    def test_mock_signing_receipt_is_accepted_by_existing_packager(self):
        original = self.apk.read_bytes()
        signed = self.execute()
        self.assertEqual(original, self.apk.read_bytes())
        self.assertEqual({'DroidModLoader-' + VERSION + '.apk', 'DroidModLoader-' + VERSION + '.apk.receipt.json'},
                         {p.name for p in signed.parent.iterdir()})
        self.assertFalse((self.parent / '.signing-incomplete').exists())
        for command, phase, secret in self.events:
            self.assertNotIn(STORE, ' '.join(command))
            self.assertNotIn(KEY, ' '.join(command))
        certificate_call = next(e for e in self.events if e[1] == 'Certificate preflight')
        self.assertIn('/dev/stdin', certificate_call[0])
        self.assertEqual((STORE + '\n').encode(), certificate_call[2])
        with self.assertRaisesRegex(ValueError, 'already has'):
            self.execute()
        package = SIGN.PACKAGE.package(self.root, signed, VERSION, CODE, self.source_commit, CERT, self.tools)
        self.assertTrue(package.is_dir())
        for path in (self.root / 'release-artifacts').rglob('*'):
            if path.is_file():
                self.assertNotIn(STORE.encode(), path.read_bytes())
                self.assertNotIn(KEY.encode(), path.read_bytes())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_wrong_certificate_stops_before_key_prompt_and_signing(self):
        def wrong(*args, **kwargs):
            self.events.append(args)
            return b'wrong certificate' if kwargs.get('certificate') else None
        with self.assertRaisesRegex(ValueError, 'pinned'):
            self.execute(native=wrong)
        self.assertFalse(any('sign' in args[0] for args in self.events))
        self.assertFalse((self.parent / 'signed').exists())
        self.assertTrue((self.parent / '.signing-incomplete').exists())
        with self.assertRaisesRegex(ValueError, 'already has'):
            self.execute()

    def test_stale_receipt_and_wrong_metadata_stop_before_prompt(self):
        self.receipt.write_text('{}')
        with patch.object(SIGN, 'password', side_effect=AssertionError('No prompt')):
            with self.assertRaisesRegex(ValueError, 'unsigned build receipt'):
                self.execute()
        self.write_receipt()
        self.sdk['debug'] = True
        self.write_apk()
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'metadata differs'):
            self.execute()

    def test_nonterminal_and_internal_or_missing_key_fail_without_reading_credentials(self):
        with patch.object(sys.stdin, 'isatty', return_value=False):
            with self.assertRaisesRegex(ValueError, 'local terminal'):
                SIGN.sign(**self.options(execute=True, keystore=self.handle, alias='key0', keytool=self.tools/'aapt2'))
        internal = self.root / 'release-artifacts/dummy-handle'
        internal.parent.mkdir(exist_ok=True)
        internal.write_text('PUBLIC_DUMMY_HANDLE')
        with self.assertRaisesRegex(ValueError, 'external file'):
            SIGN.sign(**self.options(execute=True, keystore=internal, alias='key0'))
        with self.assertRaisesRegex(ValueError, 'external file'):
            SIGN.sign(**self.options(execute=True, keystore=self.folder/'missing', alias='key0'))

    def test_native_transport_discards_raw_errors_and_reduces_environment(self):
        with patch.dict(os.environ, JAVA_TOOL_OPTIONS='PUBLIC_INJECTION', BASH_ENV='PUBLIC_INJECTION',
                        UNRELATED_CREDENTIAL='PUBLIC_UNRELATED_SAMPLE'):
            with patch.object(SIGN.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'ignored', STORE.encode())) as run:
                with self.assertRaisesRegex(ValueError, 'diagnostics suppressed') as caught:
                    SIGN.native(['fake-tool', '--ks-pass', 'stdin'], 'Signing', STORE.encode())
                self.assertNotIn(STORE, str(caught.exception))
                options = run.call_args.kwargs
                self.assertEqual(STORE.encode(), options['input'])
                self.assertEqual(subprocess.DEVNULL, options['stdout'])
                self.assertEqual(subprocess.DEVNULL, options['stderr'])
                self.assertFalse({'JAVA_TOOL_OPTIONS', 'BASH_ENV', 'UNRELATED_CREDENTIAL'} & set(options['env']))
            with patch.object(SIGN.subprocess, 'run', side_effect=subprocess.TimeoutExpired('tool', 60, output=KEY.encode())):
                with self.assertRaisesRegex(ValueError, 'suppressed') as caught:
                    SIGN.native(['fake-tool'], 'Signing', STORE.encode())
                self.assertNotIn(KEY, str(caught.exception))

    def test_masked_prompt_never_falls_back_and_rejects_control_input(self):
        with patch.object(sys.stdin, 'isatty', return_value=True), patch.object(sys.stderr, 'isatty', return_value=True):
            def fallback(*args, **kwargs):
                warnings.warn('public fixture fallback', SIGN.getpass.GetPassWarning)
                raise AssertionError('Must not reach echo input')
            with patch.object(SIGN.getpass, 'getpass', side_effect=fallback):
                with self.assertRaisesRegex(ValueError, 'Masked password'):
                    SIGN.password('Fixture: ')
            for value in ('', 'bad\nline', 'bad\rline', 'bad\0line'):
                with self.subTest(value=value), patch.object(SIGN.getpass, 'getpass', return_value=value):
                    with self.assertRaises(ValueError):
                        SIGN.password('Fixture: ')
            with patch.object(SIGN.getpass, 'getpass', return_value=KEY):
                self.assertEqual(KEY, SIGN.password('Fixture: '))

    def test_shared_candidate_lock_blocks_signing_and_packaging(self):
        with SIGN.PACKAGE.candidate_owner(self.root, VERSION, CODE):
            with self.assertRaisesRegex(ValueError, 'Another candidate'):
                self.execute()
            with self.assertRaisesRegex(ValueError, 'Another candidate'):
                SIGN.PACKAGE.package(self.root, self.apk, VERSION, CODE, self.source_commit, CERT, self.tools)
        (self.parent / '.signing-incomplete').mkdir()
        with self.assertRaisesRegex(ValueError, 'Incomplete signing'):
            SIGN.PACKAGE.package(self.root, self.apk, VERSION, CODE, self.source_commit, CERT, self.tools)

    def test_changed_key_and_native_signing_interruption_never_publish(self):
        def changing(command, phase, secret=None, certificate=False):
            result = self.native(command, phase, secret, certificate)
            if certificate:
                self.handle.write_text('PUBLIC_CHANGED_HANDLE')
            return result
        with self.assertRaisesRegex(ValueError, 'Keystore changed'):
            self.execute(native=changing)
        self.assertFalse((self.parent / 'signed').exists())
        shutil.rmtree(self.parent)
        def interrupted(command, phase, *args, **kwargs):
            if phase == 'APK signing':
                raise ValueError('Fixture signing interrupted')
            return self.native(command, phase, *args, **kwargs)
        with self.assertRaisesRegex(ValueError, 'interrupted'):
            self.execute(native=interrupted)
        self.assertTrue((self.parent / '.signing-incomplete/unsigned.apk').exists())
        self.assertFalse((self.parent / 'signed').exists())

    def test_changed_input_or_unexpected_sidecar_never_publishes(self):
        for change in ('input', 'sidecar', 'source'):
            with self.subTest(change=change):
                def changed(command, phase, *args, **kwargs):
                    result = self.native(command, phase, *args, **kwargs)
                    if phase == 'APK signing':
                        if change == 'input':
                            with self.apk.open('ab') as stream:
                                stream.write(b'PUBLIC_CHANGED_INPUT')
                        elif change == 'sidecar':
                            Path(command[command.index('--out')+1] + '.idsig').write_text('PUBLIC_UNEXPECTED_SIDECAR')
                        else:
                            (self.root / 'source-change.txt').write_text('PUBLIC_CHANGED_SOURCE')
                    return result
                with self.assertRaises(ValueError):
                    self.execute(native=changed)
                self.assertFalse((self.parent / 'signed').exists())
                self.assertTrue((self.parent / '.signing-incomplete').is_dir())
                shutil.rmtree(self.parent)
                self.write_apk()
                self.write_receipt()
                (self.root / 'source-change.txt').unlink(missing_ok=True)

    def test_source_change_during_prompt_stops_before_signing(self):
        def prompt(label):
            if label.startswith('Key password'):
                (self.root / 'source-change.txt').write_text('PUBLIC_CHANGED_SOURCE')
                return KEY
            return STORE
        with patch.object(sys.stdin, 'isatty', return_value=True), patch.object(sys.stderr, 'isatty', return_value=True), \
                patch.object(SIGN, 'password', side_effect=prompt), patch.object(SIGN, 'native', side_effect=self.native):
            with self.assertRaisesRegex(ValueError, 'clean'):
                SIGN.sign(**self.options(execute=True, keystore=self.handle, alias='key0', keytool=self.tools/'aapt2'))
        self.assertFalse(any('sign' in command for command, _, _ in self.events))
        self.assertFalse((self.parent / 'signed').exists())

    def test_final_signature_or_alignment_failure_preserves_incomplete_stage(self):
        self.sdk['valid'] = False
        self.write_apk()
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, 'Android inspection failed'):
            self.execute()
        self.assertFalse((self.parent / 'signed').exists())
        shutil.rmtree(self.parent)
        self.sdk['valid'] = True
        self.write_apk()
        self.write_receipt()
        def bad_alignment(command, phase, *args, **kwargs):
            if phase == 'Signed alignment check':
                raise ValueError('Signed alignment failed')
            return self.native(command, phase, *args, **kwargs)
        with self.assertRaisesRegex(ValueError, 'alignment failed'):
            self.execute(native=bad_alignment)
        self.assertFalse((self.parent / 'signed').exists())


if __name__ == '__main__':
    unittest.main()
