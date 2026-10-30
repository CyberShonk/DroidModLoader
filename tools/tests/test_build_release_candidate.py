"""Candidate workflow fixtures stub every build/signing operation; no real builds or keys."""

from contextlib import ExitStack
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch

import test_sign_release as fixtures

ROOT, VERSION, CODE, CERT = fixtures.ROOT, fixtures.VERSION, fixtures.CODE, fixtures.CERT
SPEC = importlib.util.spec_from_file_location('candidate_workflow', ROOT / 'tools/build-release-candidate.py')
FLOW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FLOW)


class CandidateWorkflowTest(unittest.TestCase):
    git = fixtures.SignReleaseTest.git
    commit = fixtures.SignReleaseTest.commit
    write_apk = fixtures.SignReleaseTest.write_apk
    write_receipt = fixtures.SignReleaseTest.write_receipt
    native = fixtures.SignReleaseTest.native

    def setUp(self):
        fixtures.SignReleaseTest.setUp(self)
        (self.root / '.gitignore').write_text('/release-artifacts\n/app/build/\n')
        for name in ('gradlew', 'tools/check-docs.sh', 'tools/tests/test_prepare_release.py'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture only\n')
        notes = self.root / 'releases/notes' / (VERSION + '.md')
        notes.parent.mkdir(parents=True)
        notes.write_text('# Droid Mod Loader ' + VERSION + '\n\nReviewed fixture release.\n')
        self.git('checkout', '-b', 'release/' + VERSION)
        self.commit()
        self.source_commit = self.git('rev-parse', 'HEAD')
        self.build['commit'] = self.source_commit
        self.write_apk()
        self.write_receipt()
        self.steps = []

    def options(self, **kwargs):
        values = dict(root=self.root, version=VERSION, code=CODE, commit=self.source_commit,
                      certificate=CERT, tools=self.tools)
        values.update(kwargs)
        return values

    def runner(self, root, command, log):
        self.steps.append(command)
        log.write_text('Fixture step completed; no real command executed\n')
        if ':app:assembleRelease' in command:
            folder = self.root / 'app/build/outputs/apk/release'
            folder.mkdir(parents=True)
            apk = folder / 'app-release-unsigned.apk'
            shutil.copyfile(self.apk, apk)
            receipt = json.loads(self.receipt.read_text())
            receipt['apk']['fileName'] = apk.name
            apk.with_name(apk.name + '.receipt.json').write_text(json.dumps(receipt))
            (folder / 'output-metadata.json').write_text(json.dumps({'version': 3,
                'applicationId': FLOW.BASELINE.APPLICATION_ID, 'variantName': 'release',
                'artifactType': {'type': 'APK', 'kind': 'Directory'},
                'elements': [{'type': 'SINGLE', 'filters': [], 'versionName': VERSION,
                              'versionCode': CODE, 'outputFile': apk.name}]}))

    def execute(self, *, mode='build', runner=None):
        with ExitStack() as stack:
            stack.enter_context(patch.object(FLOW, 'run_step', side_effect=runner or self.runner))
            stack.enter_context(patch.object(FLOW.SIGN, 'native', side_effect=self.native))
            stack.enter_context(patch.object(FLOW.SIGN, 'password', side_effect=[fixtures.STORE, fixtures.KEY]))
            stack.enter_context(patch.object(sys.stdin, 'isatty', return_value=True))
            stack.enter_context(patch.object(sys.stderr, 'isatty', return_value=True))
            key = {'keystore': self.handle, 'alias': 'key0'} if mode == 'execute' else {}
            return FLOW.workflow(**self.options(mode=mode, **key))

    def test_default_plan_does_not_execute_create_outputs_or_read_keys(self):
        with patch.object(FLOW, 'run_step', side_effect=AssertionError('No build')), \
                patch.object(FLOW.SIGN, 'password', side_effect=AssertionError('No password')):
            self.assertIsNone(FLOW.workflow(**self.options()))
        self.assertFalse(self.parent.exists())
        with self.assertRaisesRegex(ValueError, 'explicit --execute'):
            FLOW.workflow(**self.options(keystore=self.handle, alias='key0'))

    def test_build_only_snapshots_correct_apk_and_never_signs(self):
        built = self.execute()
        self.assertEqual(self.parent / 'built/app-release-unsigned.apk', built)
        self.assertEqual(self.apk.read_bytes(), built.read_bytes())
        self.assertFalse((self.parent / 'signed').exists())
        self.assertFalse((self.parent / 'package').exists())
        self.assertFalse((self.parent / '.build-incomplete').exists())
        self.assertFalse(any('sign' in command for command, _, _ in self.events))
        self.assertEqual(4, len(self.steps))
        self.assertIn(':app:testDebugUnitTest', self.steps[2])
        self.assertIn(':app:assembleRelease', self.steps[3])
        self.assertTrue(all('--offline' in cmd for cmd in self.steps[2:]))
        self.assertEqual('', self.git('status', '--porcelain'))
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.execute()

    def test_mock_full_workflow_produces_existing_package_contract(self):
        package = self.execute(mode='execute')
        self.assertEqual(self.parent / 'package', package)
        self.assertTrue((self.parent / 'signed').is_dir())
        apk_name, receipt, manifest, nexus = FLOW.PACKAGE.names(VERSION)
        self.assertEqual({apk_name, receipt, manifest, 'SHA256SUMS.txt'},
                         {p.name for p in (package / 'github/assets').iterdir()})
        self.assertEqual((self.parent / 'signed' / apk_name).read_bytes(), (package / 'github/assets' / apk_name).read_bytes())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_wrong_branch_tag_or_unreviewed_notes_fail_before_execution(self):
        self.git('checkout', 'main')
        with self.assertRaises(ValueError):
            FLOW.workflow(**self.options())
        self.git('checkout', 'release/' + VERSION)
        self.git('tag', VERSION)
        with self.assertRaisesRegex(ValueError, 'tag already exists'):
            FLOW.workflow(**self.options())
        self.git('tag', '-d', VERSION)
        (self.root / 'releases/notes' / (VERSION + '.md')).write_text('# Droid Mod Loader ' + VERSION + '\nTODO\n')
        self.commit()
        self.source_commit = self.git('rev-parse', 'HEAD')
        with self.assertRaisesRegex(ValueError, 'placeholders'):
            FLOW.workflow(**self.options())
        self.assertFalse(self.parent.exists())

    def test_failed_checks_leave_stage_and_block_retry_and_standalone_tools(self):
        def failed(root, command, log):
            raise ValueError('Fixture check failure')
        with self.assertRaisesRegex(ValueError, 'Fixture check'):
            self.execute(runner=failed)
        self.assertTrue((self.parent / '.build-incomplete').is_dir())
        self.assertFalse((self.parent / 'built').exists())
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.execute()
        with self.assertRaisesRegex(ValueError, 'Incomplete build'):
            FLOW.PACKAGE.package(self.root, self.apk, VERSION, CODE, self.source_commit, CERT, self.tools)

    def test_output_metadata_traversal_refuses_snapshot(self):
        def unsafe(root, command, log):
            self.runner(root, command, log)
            if ':app:assembleRelease' in command:
                path = self.root / 'app/build/outputs/apk/release/output-metadata.json'
                data = json.loads(path.read_text())
                data['elements'][0]['outputFile'] = '../escape.apk'
                path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'AGP output identity'):
            self.execute(runner=unsafe)
        self.assertFalse((self.parent / 'built').exists())
        self.assertTrue((self.parent / '.build-incomplete').exists())

    def test_extra_release_apk_is_not_arbitrarily_selected(self):
        def extra(root, command, log):
            self.runner(root, command, log)
            if ':app:assembleRelease' in command:
                (self.root / 'app/build/outputs/apk/release/stale.apk').write_bytes(b'PUBLIC_STALE_APK')
        with self.assertRaisesRegex(ValueError, 'Ambiguous release APK'):
            self.execute(runner=extra)
        self.assertFalse((self.parent / 'built').exists())

    def test_source_mutation_stops_before_later_steps(self):
        def changed(root, command, log):
            self.runner(root, command, log)
            (self.root / 'changed.txt').write_text('PUBLIC_CHANGED_SOURCE')
        with self.assertRaisesRegex(ValueError, 'clean'):
            self.execute(runner=changed)
        self.assertEqual(1, len(self.steps))
        self.assertFalse((self.parent / 'built').exists())

    def test_preparation_ownership_conflict_stops_all_execution(self):
        common = Path(self.git('rev-parse', '--absolute-git-dir'))
        with FLOW.build_owner(common):
            with self.assertRaisesRegex(ValueError, 'Another preparation'):
                self.execute()
        self.assertEqual([], self.steps)

    def test_check_commands_propagate_no_bytecode_to_child_python(self):
        with patch.object(FLOW.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            FLOW.run_step(self.root, ['fixture-only'], self.folder / 'step.log')
        self.assertEqual('1', run.call_args.kwargs['env']['PYTHONDONTWRITEBYTECODE'])

    def test_execute_requires_terminal_before_build(self):
        with patch.object(sys.stdin, 'isatty', return_value=False):
            with self.assertRaisesRegex(ValueError, 'local terminal'):
                FLOW.workflow(**self.options(mode='execute', keystore=self.handle, alias='key0'))
        self.assertFalse(self.parent.exists())


if __name__ == '__main__':
    unittest.main()
