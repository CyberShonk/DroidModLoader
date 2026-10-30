#!/usr/bin/env python3
"""Plan a prepared candidate; build/sign/package only with explicit execution flags."""

import argparse
from contextlib import contextmanager
import fcntl
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


SPEC = importlib.util.spec_from_file_location('sign_release', Path(__file__).with_name('sign-release.py'))
SIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIGN)
PACKAGE, BASELINE = SIGN.PACKAGE, SIGN.BASELINE
require = PACKAGE.require


def prepared(root, version, code, commit, certificate, tools):
    PACKAGE.source_identity(root, version, code, commit, certificate)
    require(BASELINE.git(root, 'symbolic-ref', '--quiet', '--short', 'HEAD') == 'release/' + version,
            'Use the prepared release/<version> branch')
    require(not BASELINE.git(root, 'tag', '--list', version), 'Release tag already exists')
    notes = BASELINE.git(root, 'show', commit + ':releases/notes/' + version + '.md')
    require(notes.startswith('# Droid Mod Loader ' + version + '\n') and not PACKAGE.PREVIEW.PLACEHOLDER.search(notes),
            'Committed release notes must match the version and contain no placeholders')
    require(all(PACKAGE.real_path(root / path).is_file() for path in
                ('gradlew', 'tools/check-docs.sh', 'tools/tests/test_prepare_release.py')), 'Required check/build inputs are missing')
    require(all((PACKAGE.real_path(tools) / name).is_file() for name in ('apksigner', 'aapt2', 'zipalign')),
            'Signing/inspection tools are required')
    git_dir = BASELINE.git(root, 'rev-parse', '--path-format=absolute', '--git-dir')
    common = BASELINE.git(root, 'rev-parse', '--path-format=absolute', '--git-common-dir')
    require(git_dir == common, 'Candidate workflow cannot run from a linked worktree')
    return Path(common)


@contextmanager
def build_owner(common):
    # Same ownership boundary as version preparation; leave the lock inode in place.
    lock = common / 'dml-release-prepare.lock'
    with os.fdopen(os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600), 'r+') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another preparation or candidate workflow is active') from None
        yield


def run_step(root, command, log):
    with log.open('xb') as output:
        try:
            result = subprocess.run(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, timeout=1200,
                                    env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        except subprocess.TimeoutExpired:
            raise ValueError('Check/build timed out; inspect ' + log.name) from None
    require(result.returncode == 0, 'Check/build failed; inspect ' + log.name)


def output_apk(root, version, code):
    folder = PACKAGE.real_path(root / 'app/build/outputs/apk/release')
    metadata_path = folder / 'output-metadata.json'
    metadata = PACKAGE.PREVIEW.read_json(PACKAGE.file_bytes(metadata_path))
    require(isinstance(metadata, dict) and type(metadata.get('version')) is int and metadata['version'] == 3
            and metadata.get('applicationId') == BASELINE.APPLICATION_ID
            and metadata.get('variantName') == 'release'
            and metadata.get('artifactType') == {'type': 'APK', 'kind': 'Directory'}, 'Unexpected AGP release metadata')
    elements = metadata.get('elements')
    require(isinstance(elements, list) and len(elements) == 1 and isinstance(elements[0], dict),
            'Exactly one release APK output is required')
    item = elements[0]
    name = item.get('outputFile')
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,199}\.apk', name)
            and item.get('type') == 'SINGLE' and item.get('filters') == []
            and type(item.get('versionCode')) is int and item['versionCode'] == code
            and item.get('versionName') == version, 'AGP output identity differs from the candidate')
    require({p.name for p in folder.iterdir() if p.suffix.lower() == '.apk'} == {name}, 'Ambiguous release APK files')
    return PACKAGE.real_path(folder / name), metadata_path


def workflow(root, version, code, commit, certificate, tools, *, mode='plan', keystore=None, alias=None):
    root, tools = root.resolve(), tools.absolute()
    require(mode in ('plan', 'build', 'execute'), 'Unknown execution mode')
    require(mode == 'execute' or (keystore is None and alias is None), 'Key options require explicit --execute')
    common = prepared(root, version, code, commit, certificate, tools)
    if mode == 'plan':
        return None
    if mode == 'execute':
        require(keystore is not None and alias is not None, 'Execute requires an explicit external PKCS12 and alias')
        require(sys.stdin.isatty() and sys.stderr.isatty(), 'Signing requires a local terminal with masked input')
    with build_owner(common):
        prepared(root, version, code, commit, certificate, tools)
        with PACKAGE.candidate_owner(root, version, code) as parent:
            for name in ('built', '.build-incomplete', 'signed', '.signing-incomplete', 'package', '.package-incomplete'):
                path = parent / name
                require(not path.exists() and not path.is_symlink(), 'Candidate output already exists; inspect it before another attempt')
            stage = parent / '.build-incomplete'
            stage.mkdir()
            logs = PACKAGE.inspection_folder(root, version, code, 'workflow')
            commands = [
                ['bash', 'tools/check-docs.sh'],
                [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', 'tools/tests'],
                ['./gradlew', ':app:testDebugUnitTest', '--offline', '--console=plain'],
                ['./gradlew', ':app:assembleRelease', '--offline', '--console=plain'],
            ]
            for index, command in enumerate(commands):
                run_step(root, command, logs / f'step-{index+1}.log')
                prepared(root, version, code, commit, certificate, tools)
            apk, metadata_path = output_apk(root, version, code)
            # Reuse signing preflight; no key options/passwords are involved here.
            SIGN.preflight(root, apk, version, code, commit, certificate, tools, logs, 'build')
            receipt = apk.with_name(apk.name + '.receipt.json')
            for path in (apk, receipt, metadata_path):
                PACKAGE.PREVIEW.copy_snapshot(path, stage / path.name, BASELINE.digest(path))
            SIGN.preflight(root, stage / apk.name, version, code, commit, certificate, tools, logs, 'snapshot')
            prepared(root, version, code, commit, certificate, tools)
            built = parent / 'built'
            require(not built.exists() and not built.is_symlink(), 'Built output appeared during workflow')
            stage.rename(built)
        if mode == 'build':
            return built / apk.name
        signed = SIGN.sign(root, built / apk.name, version, code, commit, certificate, tools,
                           execute=True, keystore=keystore, alias=alias)
        prepared(root, version, code, commit, certificate, tools)
        return PACKAGE.package(root, signed, version, code, commit, certificate, tools)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--build-only', action='store_true')
    modes.add_argument('--execute', action='store_true')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--alias')
    args = parser.parse_args()
    try:
        result = workflow(args.root, args.version, args.code, args.source_commit, args.certificate_sha256,
                          args.build_tools, mode='execute' if args.execute else 'build' if args.build_only else 'plan',
                          keystore=args.keystore, alias=args.alias)
        print(result if result else 'Prepared source checks passed. Plan: docs, tooling fixtures, JVM tests, release build, explicit signing, package. No execution performed.')
    except (KeyboardInterrupt, EOFError):
        sys.exit('Workflow cancelled; inspect retained outputs before retry')
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, RuntimeError, RecursionError,
            subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'ERROR: {error}')


if __name__ == '__main__':
    main()
