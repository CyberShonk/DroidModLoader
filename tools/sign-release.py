#!/usr/bin/env python3
"""Preflight a release APK; sign only with explicit local --execute. Never build or upload."""

import argparse
import getpass
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location('package_release', Path(__file__).with_name('package-release.py'))
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)
BASELINE = PACKAGE.BASELINE
require = PACKAGE.require


def native(command, phase, secret=None, certificate=False):
    """Never persist or relay output from a process which can receive credentials."""
    env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME') if key in os.environ}
    try:
        result = subprocess.run(command, input=secret, stdout=subprocess.PIPE if certificate else subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, env=env, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError(phase + ' could not complete; native diagnostics suppressed') from None
    require(result.returncode == 0, phase + ' failed; native diagnostics suppressed')
    if certificate:
        require(0 < len(result.stdout) <= 65536, 'Unexpected public certificate size')
        return result.stdout


def password(label):
    require(sys.stdin.isatty() and sys.stderr.isatty(), 'Signing requires a local terminal with masked input')
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', getpass.GetPassWarning)
            value = getpass.getpass(label, stream=sys.stderr)
    except (getpass.GetPassWarning, EOFError, OSError):
        raise ValueError('Masked password entry unavailable; signing stopped') from None
    require(0 < len(value) <= 1024 and not any(ord(c) < 32 or ord(c) == 127 for c in value),
            'Enter a nonempty single line password without control characters')
    return value


def preflight(root, apk, version, code, commit, certificate, tools, logs, label):
    PACKAGE.source_identity(root, version, code, commit, certificate)
    apk = PACKAGE.real_path(apk)
    require(apk.is_file() and 0 < apk.stat().st_size <= PACKAGE.MAX_APK, 'Missing or oversized input APK')
    before = BASELINE.digest(apk)
    build = PACKAGE.provenance(apk, version, code, commit)
    require(BASELINE.metadata(apk, tools, logs, label) == (BASELINE.APPLICATION_ID, code, version, False),
            'Unsigned APK package/version/debuggable metadata differs')
    native([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], 'Alignment check')
    expected = {'schemaVersion': 1, 'apk': {'fileName': apk.name, 'sizeBytes': apk.stat().st_size, 'sha256': before},
                'build': build, 'signing': {'status': 'unsigned', 'certificateSha256': []}}
    receipt_path = apk.with_name(apk.name + '.receipt.json')
    receipt = PACKAGE.PREVIEW.read_json(PACKAGE.file_bytes(receipt_path))
    require(isinstance(receipt, dict) and type(receipt.get('schemaVersion')) is int and receipt == expected,
            'Input requires a matching unsigned build receipt')
    require(BASELINE.digest(apk) == before, 'Input APK changed during preflight')
    # Unsigned is the matching build receipt's status, not a claimed successful signature check.
    return expected


def sign(root, source_apk, version, code, commit, certificate, tools, *, execute=False, keystore=None, alias=None, keytool=None):
    root = root.resolve()
    PACKAGE.source_identity(root, version, code, commit, certificate)
    tools = PACKAGE.real_path(tools)
    require(all((tools / name).is_file() for name in ('apksigner', 'aapt2', 'zipalign')), 'Signing and inspection tools are required')
    require(execute or (keystore is None and alias is None and keytool is None), 'Key options require explicit --execute')
    logs = PACKAGE.inspection_folder(root, version, code, 'sign')
    original = preflight(root, source_apk, version, code, commit, certificate, tools, logs, 'input')
    if not execute:
        return None
    require(keystore is not None and alias is not None, 'Execute requires an explicit external PKCS12 and alias')
    keystore = PACKAGE.real_path(keystore)
    require(not keystore.is_relative_to(root) and keystore.is_file() and 0 < keystore.stat().st_size <= 1024 * 1024,
            'Keystore must be an existing external file no larger than 1 MiB')
    require(isinstance(alias, str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', alias) and alias.lower() != 'androiddebugkey',
            'Supply an explicit release key alias, not a debug alias')
    executable = str(keytool) if keytool else shutil.which('keytool')
    require(executable, 'keytool is required')
    keytool = Path(executable).resolve()
    require(keytool.is_file(), 'keytool is required')
    require(sys.stdin.isatty() and sys.stderr.isatty(), 'Signing requires a local terminal with masked input')
    with PACKAGE.candidate_owner(root, version, code) as parent:
        output, stage = parent / 'signed', parent / '.signing-incomplete'
        for name in ('signed', '.signing-incomplete', 'package', '.package-incomplete'):
            path = parent / name
            require(not path.exists() and not path.is_symlink(), 'Candidate already has signed, packaged or incomplete output; inspect before retry')
        stage.mkdir()
        snapshot = stage / 'unsigned.apk'
        source_apk = PACKAGE.real_path(source_apk)
        PACKAGE.PREVIEW.copy_snapshot(source_apk, snapshot, original['apk']['sha256'])
        require(snapshot.stat().st_size <= PACKAGE.MAX_APK, 'Input APK grew beyond size limit')
        # Recheck the snapshot against source facts without adopting its new filename as input identity.
        require(PACKAGE.provenance(snapshot, version, code, commit) == original['build'], 'Snapshot provenance changed')
        PACKAGE.source_identity(root, version, code, commit, certificate)
        key_hash = BASELINE.digest(keystore)
        store = password('Keystore password: ')
        der = native([str(keytool), '-J-Dfile.encoding=UTF-8', '-exportcert', '-storetype', 'PKCS12',
                      '-keystore', str(keystore), '-alias', alias, '-storepass:file', '/dev/stdin'],
                     'Certificate preflight', (store + '\n').encode('utf-8'), certificate=True)
        require(BASELINE.digest(keystore) == key_hash, 'Keystore changed during certificate preflight')
        require(hashlib.sha256(der).hexdigest() == certificate, 'Key alias certificate differs from the pinned release certificate')
        # A key password is always explicit; no fallback using another key or password.
        key = password('Key password (enter it even if identical): ')
        apk_name, receipt_name, _, _ = PACKAGE.names(version)
        apk = stage / apk_name
        PACKAGE.source_identity(root, version, code, commit, certificate)
        require(BASELINE.digest(source_apk) == original['apk']['sha256']
                and BASELINE.digest(snapshot) == original['apk']['sha256'], 'Input APK changed before signing')
        require(BASELINE.digest(keystore) == key_hash, 'Keystore changed before signing')
        native([str(tools / 'apksigner'), '-J-Dfile.encoding=UTF-8', 'sign', '--ks', str(keystore),
                '--ks-type', 'PKCS12', '--ks-key-alias', alias, '--ks-pass', 'stdin', '--key-pass', 'stdin',
                '--pass-encoding', 'utf-8', '--v4-signing-enabled', 'false', '--out', str(apk), str(snapshot)],
               'APK signing', (store + '\n' + key + '\n').encode('utf-8'))
        del store, key, der  # Drop references; Python/JVM memory erasure is not guaranteed.
        require(BASELINE.digest(keystore) == key_hash, 'Keystore changed during signing')
        require(apk.is_file() and 0 < apk.stat().st_size <= PACKAGE.MAX_APK, 'Missing or oversized signed APK')
        record = PACKAGE.candidate_identity(apk, version, code, commit, certificate, tools, logs, 'signed')
        require(record['build'] == original['build'], 'Signing changed embedded provenance')
        native([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], 'Signed alignment check')
        PACKAGE.write_json(stage / receipt_name, record)
        PACKAGE.source_identity(root, version, code, commit, certificate)
        require(BASELINE.digest(source_apk) == original['apk']['sha256'], 'Input APK changed during signing')
        require(BASELINE.digest(snapshot) == original['apk']['sha256'], 'Snapshot changed during signing')
        require(BASELINE.digest(apk) == record['apk']['sha256'], 'Signed APK changed after verification')
        require({p.name for p in stage.iterdir()} == {'unsigned.apk', apk_name, receipt_name}, 'Unexpected signing sidecar or output')
        (logs / 'signing-result.txt').write_text('Signed APK and final receipt verified.\nCertificate SHA256: ' + certificate + '\n')
        snapshot.unlink()  # Task-owned intermediate removed only after all verification succeeds.
        require(not output.exists() and not output.is_symlink(), 'Signed output appeared during signing')
        stage.rename(output)
        return output / apk_name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--alias')
    parser.add_argument('--keytool', type=Path)
    args = parser.parse_args()
    try:
        output = sign(args.root, args.apk, args.version, args.code, args.source_commit, args.certificate_sha256,
                      args.build_tools, execute=args.execute, keystore=args.keystore, alias=args.alias, keytool=args.keytool)
        print(output if output else 'Input checks passed; no key read or signing performed. Key identity not checked.')
    except (KeyboardInterrupt, EOFError):
        sys.exit('Signing cancelled; inspect any incomplete output before retry')
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, RuntimeError, RecursionError,
            subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'ERROR: {error}')


if __name__ == '__main__':
    main()
