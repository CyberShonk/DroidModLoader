#!/usr/bin/env python3
"""Verify and package an already signed APK. Never build, sign, reserve or upload."""

import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BASELINE = load('collect-release-baseline')
PREVIEW = load('preview-release')
MAX_APK = BASELINE.MAX_APK
MAX_TEXT = PREVIEW.MAX_TEXT
NOTICE = 'releases/templates/THIRD-PARTY-NOTICES.txt'
README = 'releases/templates/README-NEXUS.txt'
NEXUS_PAGE = 'https://www.nexusmods.com/skyrim/mods/121618'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def real_path(path):
    path = path.absolute()
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'Linked paths are not allowed')
    return path


def file_bytes(path, maximum=MAX_TEXT):
    real_path(path)
    require(path.is_file() and path.stat().st_size <= maximum, 'Missing or oversized input file')
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum, 'Input grew beyond size limit')
    return data


def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(value, indent=2, sort_keys=True) + '\n')


def source_identity(root, version, code, commit, certificate):
    require(re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta)?', version) and len(version) <= 64, 'Unsupported version')
    BASELINE.number(code, 'Version code')
    require(re.fullmatch(r'[0-9a-f]{40}', commit), 'Supply the full expected source commit')
    require(re.fullmatch(r'[0-9a-f]{64}', certificate), 'Supply the pinned release certificate SHA256')
    require(BASELINE.git(root, 'rev-parse', 'HEAD') == commit, 'Source commit differs from HEAD')
    require(not BASELINE.git(root, 'status', '--porcelain', '--untracked-files=all'), 'Source working tree must be clean')
    version_data = BASELINE.git(root, 'show', commit + ':version.properties')
    require(re.findall(r'^VERSION_NAME=([^\r\n]+)\r?$', version_data, re.M) == [version]
            and re.findall(r'^VERSION_CODE=(\d+)\r?$', version_data, re.M) == [str(code)],
            'Source version.properties differs from the candidate')
    # Dependency edits invalidate the reviewed distribution notices.
    notice_commit = BASELINE.git(root, 'log', '-1', '--format=%H', commit, '--', NOTICE)
    require(notice_commit, 'Committed distribution notices are required')
    require(not BASELINE.git(root, 'diff', notice_commit, commit, '--', 'app/build.gradle.kts', 'gradle/libs.versions.toml'),
            'Dependencies changed since distribution notices were reviewed')


def provenance(apk, version, code, commit):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        require(len(entries) <= 50000, 'APK entry count exceeds limit')
        matches = [e for e in entries if e.filename == 'assets/dml-build-provenance.json']
        require(len(matches) == 1 and matches[0].file_size <= MAX_TEXT, 'Exactly one bounded embedded provenance record is required')
        record = PREVIEW.read_json(archive.read(matches[0]))
    expected = {'schemaVersion': 1, 'commit': commit, 'dirty': False,
                'applicationId': BASELINE.APPLICATION_ID, 'versionName': version,
                'versionCode': code, 'variant': 'release',
                'trackedDiffSha256': hashlib.sha256(b'').hexdigest(),
                'untrackedFilesSha256': hashlib.sha256(b'{}').hexdigest()}
    text_fields = {'gradle', 'androidGradlePlugin', 'kotlin', 'javaVersion', 'javaVendor',
                   'compileSdk', 'minSdk', 'targetSdk', 'buildTools'}
    require(isinstance(record, dict) and set(record) == set(expected) | text_fields | {'builtAtUtc', 'ciRunUrl'},
            'Embedded provenance has unknown or missing public fields')
    require(type(record['schemaVersion']) is int and type(record['versionCode']) is int
            and type(record['dirty']) is bool and all(record[k] == v for k, v in expected.items()),
            'Embedded provenance differs from the clean release source identity')
    for field in text_fields:
        require(isinstance(record[field], str) and re.fullmatch(r'[A-Za-z0-9 .()+_-]{1,160}', record[field]),
                'Invalid public toolchain field')
    stamp = record['builtAtUtc']
    require(isinstance(stamp, str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', stamp),
            'Invalid public build timestamp')
    try:
        BASELINE.datetime.strptime(stamp[:19], '%Y-%m-%dT%H:%M:%S')
    except ValueError:
        raise ValueError('Invalid public build timestamp') from None
    ci = record['ciRunUrl']
    require(isinstance(ci, str) and (ci == '' or re.fullmatch(r'https://github\.com/CyberShonk/DroidModLoader/actions/runs/[0-9]+', ci)),
            'Invalid public CI URL')
    return record


def candidate_identity(apk, version, code, commit, certificate, tools, inspection, label):
    before = BASELINE.digest(apk)
    actual = BASELINE.inspect(apk, tools, inspection, label)
    require(actual == (BASELINE.APPLICATION_ID, code, version, certificate, False),
            'APK signature/package/version/debuggable identity differs from the pinned release')
    build = provenance(apk, version, code, commit)
    require(BASELINE.digest(apk) == before, 'APK changed during inspection')
    return {'schemaVersion': 1,
            'apk': {'fileName': apk.name, 'sizeBytes': apk.stat().st_size, 'sha256': before},
            'build': build, 'signing': {'status': 'verified', 'certificateSha256': [certificate]}}


def names(version):
    base = 'DroidModLoader-' + version
    return base + '.apk', base + '.apk.receipt.json', base + '-manifest.json', base + '-Nexus.zip'


def sums(files):
    return ''.join(f'{BASELINE.digest(path)}  {path.name}\n' for path in sorted(files, key=lambda p: p.name))


def inventory(apk, receipt, nexus, version, code, commit, certificate):
    return {'schemaVersion': 1, 'applicationId': BASELINE.APPLICATION_ID,
            'versionName': version, 'versionCode': code, 'sourceCommit': commit,
            'certificateSha256': certificate, 'nexusPageUrl': NEXUS_PAGE,
            'artifacts': [{'role': role, 'fileName': path.name, 'sizeBytes': path.stat().st_size,
                           'sha256': BASELINE.digest(path), 'destination': destination}
                          for role, path, destination in (('apk', apk, 'github'), ('receipt', receipt, 'github'), ('nexusZip', nexus, 'nexus'))]}


def verify_bundle(folder, version, code, commit, certificate, tools, inspection, github_only=False):
    """Check present GitHub payloads independently; optionally require the Nexus ZIP."""
    apk_name, receipt_name, manifest_name, nexus_name = names(version)
    real_path(folder)
    require(folder.is_dir() and {p.name for p in folder.iterdir()} <= {'github', 'nexus'}, 'Unexpected package files')
    github = real_path(folder / 'github')
    require(github.is_dir() and {p.name for p in github.iterdir()} == {'assets'}, 'Unexpected GitHub folders')
    assets = folder / 'github/assets'
    require(real_path(assets).is_dir() and {p.name for p in assets.iterdir()} ==
            {apk_name, receipt_name, manifest_name, 'SHA256SUMS.txt'}, 'Unexpected or missing GitHub assets')
    apk, receipt = assets / apk_name, assets / receipt_name
    require(real_path(apk).is_file() and 0 < apk.stat().st_size <= MAX_APK, 'Missing or oversized APK')
    recorded = PREVIEW.read_json(file_bytes(receipt))
    require(isinstance(recorded, dict) and type(recorded.get('schemaVersion')) is int
            and recorded == candidate_identity(apk, version, code, commit, certificate, tools, inspection, 'recheck'),
            'Final receipt differs from the actual APK')
    manifest = PREVIEW.read_json(file_bytes(assets / manifest_name))
    expected_keys = {'schemaVersion', 'applicationId', 'versionName', 'versionCode', 'sourceCommit',
                     'certificateSha256', 'nexusPageUrl', 'artifacts'}
    require(isinstance(manifest, dict) and set(manifest) == expected_keys, 'Unexpected manifest fields')
    require(type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
            and type(manifest['versionCode']) is int and manifest['versionCode'] == code
            and all(manifest[k] == v for k, v in {'applicationId': BASELINE.APPLICATION_ID,
                'versionName': version, 'sourceCommit': commit, 'certificateSha256': certificate,
                'nexusPageUrl': NEXUS_PAGE}.items()), 'Manifest release identity differs')
    rows = manifest['artifacts']
    require(isinstance(rows, list) and len(rows) == 3, 'Manifest must list APK, receipt and Nexus ZIP')
    expected_rows = (('apk', apk_name, 'github'), ('receipt', receipt_name, 'github'), ('nexusZip', nexus_name, 'nexus'))
    for row, (role, name, destination) in zip(rows, expected_rows):
        require(isinstance(row, dict) and set(row) == {'role', 'fileName', 'sizeBytes', 'sha256', 'destination'}
                and row['role'] == role and row['fileName'] == name and row['destination'] == destination
                and type(row['sizeBytes']) is int and 0 < row['sizeBytes'] <= PREVIEW.MAX_FILE
                and isinstance(row['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', row['sha256']),
                'Invalid manifest artifact inventory')
        if role != 'nexusZip' or not github_only:
            path = assets / name if destination == 'github' else folder / 'nexus' / name
            real_path(path)
            require(path.is_file() and path.stat().st_size == row['sizeBytes'] and BASELINE.digest(path) == row['sha256'],
                    'Manifest artifact size/hash differs from bytes')
    hashes = {row['fileName']: row['sha256'] for row in rows}
    hashes[manifest_name] = BASELINE.digest(assets / manifest_name)
    PREVIEW.checksums(file_bytes(assets / 'SHA256SUMS.txt'), hashes)
    if not github_only:
        nexus_dir = real_path(folder / 'nexus')
        require(nexus_dir.is_dir() and {p.name for p in nexus_dir.iterdir()} == {nexus_name}, 'Unexpected Nexus files')
        PREVIEW.zip_review(nexus_dir / nexus_name, apk_name, BASELINE.digest(apk), receipt_name, file_bytes(receipt))


def inspection_folder(root, version, code):
    root = root.resolve()
    folder = real_path(root / 'release-artifacts' / version / 'maintainer')
    require(subprocess.run(['git', '-C', str(root), 'check-ignore', '--quiet', '--no-index', '--', str(folder.relative_to(root))]).returncode == 0,
            'Inspection output must be ignored')
    folder.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix=f'package-code-{code}-', dir=folder))


def package(root, source_apk, version, code, commit, certificate, tools):
    root = root.resolve()
    source_identity(root, version, code, commit, certificate)
    source_apk = real_path(source_apk)
    require(source_apk.is_file() and 0 < source_apk.stat().st_size <= MAX_APK, 'Missing or oversized APK')
    tools = real_path(tools)
    require(all((tools / name).is_file() for name in ('apksigner', 'aapt2')), 'Android inspection tools are required')
    parent = real_path(root / 'release-artifacts' / version / 'candidates' / f'code-{code}')
    require(subprocess.run(['git', '-C', str(root), 'check-ignore', '--quiet', '--no-index', '--', str(parent.relative_to(root))]).returncode == 0,
            'Candidate output must be ignored')
    require(not source_apk.is_relative_to(parent / 'package') and not source_apk.is_relative_to(parent / '.package-incomplete'),
            'APK input cannot be a packaging output')
    parent.mkdir(parents=True, exist_ok=True)
    lock = parent / '.package.lock'
    with os.fdopen(os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600), 'r+') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another packaging operation owns this candidate') from None
        output, stage = parent / 'package', parent / '.package-incomplete'
        require(not output.exists() and not output.is_symlink(), 'Package already exists; check it without regenerating')
        require(not stage.exists() and not stage.is_symlink(), 'Incomplete package exists; review evidence before retry')
        inspection = inspection_folder(root, version, code)
        stage.mkdir()
        assets, nexus_dir = stage / 'github/assets', stage / 'nexus'
        assets.mkdir(parents=True)
        nexus_dir.mkdir()
        apk_name, receipt_name, manifest_name, nexus_name = names(version)
        apk, receipt = assets / apk_name, assets / receipt_name
        source_hash = BASELINE.digest(source_apk)
        PREVIEW.copy_snapshot(source_apk, apk, source_hash)
        require(apk.stat().st_size <= MAX_APK, 'APK grew beyond size limit')
        record = candidate_identity(apk, version, code, commit, certificate, tools, inspection, 'final')
        prior_receipt = source_apk.with_name(source_apk.name + '.receipt.json')
        if prior_receipt.exists() or prior_receipt.is_symlink():
            old = PREVIEW.read_json(file_bytes(prior_receipt))
            original = dict(record, apk=dict(record['apk'], fileName=source_apk.name))
            require(isinstance(old, dict) and type(old.get('schemaVersion')) is int and old == original,
                    'Input receipt is stale or differs from the signed APK')
        write_json(receipt, record)
        payload = {apk_name: apk, receipt_name: receipt}
        for name, source in (('LICENSE', 'LICENSE'), ('THIRD-PARTY-NOTICES.txt', NOTICE), ('README-NEXUS.txt', README)):
            local = real_path(root / source)
            data = file_bytes(local)
            require(data == subprocess.check_output(['git', '-C', str(root), 'show', commit + ':' + source], stderr=subprocess.PIPE),
                    'Distribution text differs from committed source')
            if name == 'README-NEXUS.txt':
                data = data.decode('utf-8').format(version=version, apk=apk_name).encode()
            require(data.strip() and not PREVIEW.PLACEHOLDER.search(data.decode('utf-8')), 'Unfinished distribution text')
            target = stage / name
            with target.open('xb') as stream:
                stream.write(data)
            payload[name] = target
        inner_sums = stage / 'SHA256SUMS.txt'
        inner_sums.write_text(sums(payload.values()), encoding='utf-8')
        nexus = nexus_dir / nexus_name
        with zipfile.ZipFile(nexus, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, path in sorted({**payload, 'SHA256SUMS.txt': inner_sums}.items()):
                archive.write(path, name)
        # Temporary payload copies are not extra public assets.
        for path in (*[p for p in payload.values() if p.parent == stage], inner_sums):
            path.unlink()
        manifest_path = assets / manifest_name
        write_json(manifest_path, inventory(apk, receipt, nexus, version, code, commit, certificate))
        (assets / 'SHA256SUMS.txt').write_text(sums((apk, receipt, nexus, manifest_path)), encoding='utf-8')
        verify_bundle(stage, version, code, commit, certificate, tools, inspection)
        source_identity(root, version, code, commit, certificate)
        require(BASELINE.digest(source_apk) == source_hash, 'Input APK changed during packaging')
        require(not output.exists() and not output.is_symlink(), 'Package appeared during preparation')
        stage.rename(output)
        return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--code', required=True, type=int)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--check-directory', type=Path)
    parser.add_argument('--github-only', action='store_true')
    args = parser.parse_args()
    try:
        if args.check_directory:
            require(args.apk is None, 'Do not mix package and check modes')
            # Checking downloaded files does not require a clean source checkout.
            require(re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta)?', args.version) and len(args.version) <= 64, 'Unsupported version')
            BASELINE.number(args.code, 'Version code')
            require(re.fullmatch(r'[0-9a-f]{40}', args.source_commit) and re.fullmatch(r'[0-9a-f]{64}', args.certificate_sha256), 'Invalid expected public identity')
            inspection = inspection_folder(args.root, args.version, args.code)
            verify_bundle(real_path(args.check_directory), args.version, args.code, args.source_commit,
                          args.certificate_sha256, real_path(args.build_tools), inspection, args.github_only)
            print('GitHub payloads verified; Nexus ZIP not checked' if args.github_only else 'Full package verified')
        else:
            require(args.apk is not None and not args.github_only, 'Packaging requires --apk; --github-only requires check mode')
            print(package(args.root, args.apk, args.version, args.code, args.source_commit,
                          args.certificate_sha256, args.build_tools))
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, RuntimeError, RecursionError,
            subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        sys.exit(f'ERROR: {error}')


if __name__ == '__main__':
    main()
