#!/usr/bin/env python3
"""Create an offline exact-text review snapshot; never build, sign or publish."""

import argparse
import difflib
import hashlib
from html import escape
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
from urllib.parse import quote
import zipfile


TEXT_INPUTS = {
    'github/title.txt': 'GitHub release title',
    'github/body.md': 'GitHub release body and upgrade instructions',
    'github/prerelease.txt': 'GitHub prerelease setting (true or false)',
    'nexus/file-description.txt': 'Nexus file description',
    'nexus/changelog.txt': 'Nexus changelog',
    'nexus/description.bbcode': 'Nexus page description',
    'previous/nexus-description.bbcode': 'Previous Nexus description (private comparison input)',
    'maintainer/checklist.txt': 'Pending checks and approvals (private)',
}
EMAIL = 'maintainer/email.txt'
MAX_TEXT = 2 * 1024 * 1024
MAX_FILE = 1024 * 1024 * 1024
MAX_ZIP_ENTRIES = 32
ARTIFACT_ERRORS = (OSError, UnicodeError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError, RecursionError)
PLACEHOLDER = re.compile(r'\{\{[^\n{}]+\}\}|\b(?:TODO|TBD|FIXME|YYYY-MM-DD)\b|v0\.x\.y-beta')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def text(data, label):
    if len(data) > MAX_TEXT:
        raise ValueError(f'{label}: text exceeds 2 MiB')
    return data.decode('utf-8')


def read_bytes(path):
    with path.open('rb') as stream:
        data = stream.read(MAX_TEXT + 1)
    if len(data) > MAX_TEXT:
        raise ValueError(f'{path.name}: text exceeds 2 MiB')
    return data


def copy_snapshot(source, destination, expected):
    copied = 0
    hasher = hashlib.sha256()
    with source.open('rb') as src, destination.open('xb') as dst:
        while chunk := src.read(1024 * 1024):
            copied += len(chunk)
            if copied > MAX_FILE:
                raise ValueError('Input grew beyond snapshot size limit')
            hasher.update(chunk)
            dst.write(chunk)
    if hasher.hexdigest() != expected or digest(source) != expected:
        raise ValueError('Input changed during snapshot; incomplete output must not be reviewed')


def read_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'Duplicate JSON key: {key}')
            result[key] = value
        return result
    def nonfinite(value):
        raise ValueError(f'Nonstandard JSON number: {value}')
    return json.loads(data, object_pairs_hook=unique, parse_constant=nonfinite)


def checksums(data, expected):
    found = {}
    for line in data.decode('utf-8').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64}) [ *]([^/\\]+)', line)
        if not match or match[2] in found:
            raise ValueError('Malformed or duplicate checksum entry')
        found[match[2]] = match[1]
    if found != expected:
        raise ValueError('Checksum names or hashes differ from the supplied files')


def public_document(name):
    return name in ('README.md', 'CONTRIBUTING.md', 'LICENSE') or (
        name.endswith('.md') and name.startswith(('docs/', 'releases/', '.github/'))
    )


def zip_review(path, apk_name, apk_hash, receipt_name, receipt_bytes):
    sections = []
    required_text = ('README-NEXUS.txt', 'LICENSE', 'THIRD-PARTY-NOTICES.txt')
    expected_names = {apk_name, receipt_name, 'SHA256SUMS.txt', *required_text}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ZIP_ENTRIES or {e.filename for e in entries} != expected_names or len(entries) != len(expected_names):
            raise ValueError('Nexus ZIP must contain only the APK, receipt, README-NEXUS.txt, LICENSE, THIRD-PARTY-NOTICES.txt and SHA256SUMS.txt')
        hashes = {}
        for entry in entries:
            mode = entry.external_attr >> 16
            if entry.flag_bits & 1 or stat.S_ISLNK(mode) or entry.is_dir() or entry.file_size > MAX_FILE:
                raise ValueError('Nexus ZIP contains an encrypted, linked, directory or oversized entry')
            if entry.filename == apk_name:
                with archive.open(entry) as stream:
                    hashes[entry.filename] = hashlib.file_digest(stream, 'sha256').hexdigest()
                if hashes[entry.filename] != apk_hash:
                    raise ValueError('Nexus APK differs from the GitHub APK')
            else:
                if entry.file_size > MAX_TEXT:
                    raise ValueError('Nexus ZIP text exceeds 2 MiB')
                data = archive.read(entry)
                if entry.filename == receipt_name and data != receipt_bytes:
                    raise ValueError('Nexus receipt differs from the GitHub receipt')
                hashes[entry.filename] = hashlib.sha256(data).hexdigest()
                content = text(data, entry.filename)
                if entry.filename in required_text and PLACEHOLDER.search(content):
                    raise ValueError(f'Nexus ZIP text contains an unresolved placeholder: {entry.filename}')
                sections.append((f'Nexus ZIP: {entry.filename}', content))
        sums = archive.read('SHA256SUMS.txt')
        checksums(sums, {name: value for name, value in hashes.items() if name != 'SHA256SUMS.txt'})
    listing = '\n'.join(f'{e.filename}: {e.file_size} bytes' for e in entries)
    return [('Nexus ZIP contents', listing), *sections]


def create_preview(root, inputs, output, version, code, source_ref, previous_ref, certificate, complete):
    root = root.resolve()
    if inputs.is_symlink() or not inputs.is_dir():
        raise ValueError('Input folder must be an existing real directory')
    inputs = inputs.resolve()
    if output.exists() or output.is_symlink():
        raise ValueError('Output already exists; choose a new preview directory')
    output = output.absolute()
    if output.resolve().is_relative_to(inputs) or inputs.is_relative_to(output.resolve()):
        raise ValueError('Input and output directories must not overlap')
    if output.resolve().is_relative_to(root):
        relative = output.resolve().relative_to(root)
        ignored = subprocess.run(['git', '-C', str(root), 'check-ignore', '--quiet', '--no-index', '--', str(relative)])
        if ignored.returncode != 0:
            raise ValueError('Preview output inside the repository must be ignored')
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta)?', version) or len(version) > 64:
        raise ValueError('Unsupported version name')
    if not 1 <= code <= 2100000000:
        raise ValueError('Version code must be between 1 and 2100000000')
    if certificate and not re.fullmatch(r'[0-9a-f]{64}', certificate):
        raise ValueError('Expected certificate must be a lowercase SHA-256 digest')
    commit = git(root, 'rev-parse', '--verify', '--end-of-options', source_ref + '^{commit}').decode().strip()
    previous = git(root, 'rev-parse', '--verify', '--end-of-options', previous_ref + '^{commit}').decode().strip()
    git(root, 'merge-base', '--is-ancestor', previous, commit)
    sections = []
    problems = []
    public_names = [n.decode() for n in git(root, 'ls-tree', '-r', '--name-only', '-z', commit).split(b'\0') if n and public_document(n.decode())]
    if len(public_names) > 200:
        raise ValueError('Too many public documents to preview')
    blobs = {}
    for required in ('README.md', 'CONTRIBUTING.md', 'docs/user-guide.md', 'docs/troubleshooting.md', 'releases/changelog.md', 'LICENSE'):
        if required not in public_names:
            problems.append(f'Missing source documentation: {required}')
    for name in public_names:
        if any(ord(c) < 32 or ord(c) == 127 for c in name):
            raise ValueError('Control character in documentation filename')
        if int(git(root, 'cat-file', '-s', f'{commit}:{name}')) > MAX_TEXT:
            raise ValueError(f'{name}: source document exceeds text limit')
        data = git(root, 'show', f'{commit}:{name}')
        content = text(data, name)
        blobs['source/' + name] = data
        sections.append((f'Public documentation: {name}', content))
    if sum(map(len, blobs.values())) > 16 * MAX_TEXT:
        raise ValueError('Public documentation exceeds preview size limit')
    version_file = git(root, 'show', f'{commit}:version.properties').decode('utf-8')
    if re.findall(r'^VERSION_NAME=([^\r\n]+)\r?$', version_file, re.M) != [version] or re.findall(r'^VERSION_CODE=(\d+)\r?$', version_file, re.M) != [str(code)]:
        problems.append('Source version.properties differs from the requested candidate identity')
    sections.append(('Source documentation changes since previous release', text(
        git(root, 'diff', '--no-ext-diff', '--no-textconv', previous, commit, '--', *public_names), 'documentation diff')))
    files = {}
    text_hashes = {}
    contents = {}
    optional = {EMAIL}
    for name in (*TEXT_INPUTS, EMAIL):
        path = inputs / name
        if not path.exists() and not path.is_symlink():
            if name not in optional:
                problems.append(f'Missing input: {name}')
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(inputs) or not path.is_file():
            raise ValueError(f'Unsafe input: {name}')
        data = read_bytes(path)
        content = text(data, name)
        text_hashes[name] = hashlib.sha256(data).hexdigest()
        contents[name] = content
        files[name] = path
        sections.append((TEXT_INPUTS.get(name, 'Unsent support email (private; may still need upload details)'), content))
        if name not in optional and PLACEHOLDER.search(content):
            problems.append(f'Unresolved placeholder: {name}')
    if 'github/prerelease.txt' in contents and contents['github/prerelease.txt'].strip() not in ('true', 'false'):
        problems.append('GitHub prerelease setting must be exactly true or false')
    before = 'previous/nexus-description.bbcode'
    after = 'nexus/description.bbcode'
    if before in files and after in files:
        sections.append(('Proposed Nexus description changes', ''.join(difflib.unified_diff(
            contents[before].splitlines(keepends=True), contents[after].splitlines(keepends=True),
            fromfile='previous description', tofile='proposed description'))))
    base = f'DroidModLoader-{version}'
    apk = base + '.apk'
    receipt = apk + '.receipt.json'
    manifest = base + '-manifest.json'
    nexus = base + '-Nexus.zip'
    assets = {f'github/assets/{name}': name for name in (apk, receipt, manifest, 'SHA256SUMS.txt')}
    assets[f'nexus/{nexus}'] = nexus
    asset_dir = inputs / 'github/assets'
    if asset_dir.exists() and (asset_dir.is_symlink() or {p.name for p in asset_dir.iterdir()} - {apk, receipt, manifest, 'SHA256SUMS.txt'}):
        raise ValueError('GitHub asset folder contains unexpected files or is linked')
    for name in assets:
        path = inputs / name
        if not path.exists() and not path.is_symlink():
            problems.append(f'Missing input: {name}')
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(inputs) or not path.is_file() or path.stat().st_size > MAX_FILE:
            raise ValueError(f'Unsafe or oversized asset: {name}')
        files[name] = path
    hashes = {name: digest(path) for name, path in files.items()}
    if any(hashes[name] != value for name, value in text_hashes.items()):
        raise ValueError('Text input changed while preparing the preview')
    sections.insert(0, ('Candidate identity', f'Version: {version}\nVersion code: {code}\nSource commit: {commit}\nPrevious source: {previous}\nExpected certificate SHA-256: {certificate or "NOT SUPPLIED"}'))
    sections.append(('Download inventory', '\n'.join(f'{name}: {files[name].stat().st_size} bytes; SHA-256 {hashes[name]}' for name in assets if name in files) or 'No packaged artifacts supplied.'))
    if not certificate:
        problems.append('Expected release certificate not supplied')
    try:
        receipt_path = 'github/assets/' + receipt
        apk_path = 'github/assets/' + apk
        if receipt_path in files:
            data = read_bytes(files[receipt_path])
            sections.append(('APK receipt (recorded claims; not independent signature verification)', text(data, receipt)))
            if PLACEHOLDER.search(text(data, receipt)):
                problems.append('Unresolved placeholder in APK receipt')
            record = read_json(data)
            if apk_path in files:
                if type(record['schemaVersion']) is not int or record['schemaVersion'] != 1 or record['apk'] != {'fileName': apk, 'sizeBytes': files[apk_path].stat().st_size, 'sha256': hashes[apk_path]}:
                    raise ValueError('APK receipt identity differs from the supplied APK')
                with zipfile.ZipFile(files[apk_path]) as archive:
                    if len([e for e in archive.infolist() if e.filename == 'assets/dml-build-provenance.json']) != 1:
                        raise ValueError('APK must have exactly one embedded provenance entry')
                    entry = archive.getinfo('assets/dml-build-provenance.json')
                    if entry.file_size > MAX_TEXT:
                        raise ValueError('Embedded provenance exceeds text limit')
                    provenance = read_json(archive.read(entry))
                if not isinstance(provenance, dict) or type(provenance.get('schemaVersion')) is not int or type(provenance.get('dirty')) is not bool or type(provenance.get('versionCode')) is not int or record['build'] != provenance or any(provenance.get(k) != v for k, v in {
                    'schemaVersion': 1, 'commit': commit, 'dirty': False, 'applicationId': 'com.shonkware.droidmodloader',
                    'versionName': version, 'versionCode': code, 'variant': 'release',
                }.items()):
                    raise ValueError('Recorded/embedded build identity differs from the requested release')
                if record['signing'] != {'status': 'verified', 'certificateSha256': [certificate]}:
                    raise ValueError('Recorded signer differs from the expected release certificate')
    except ARTIFACT_ERRORS as error:
        problems.append(f'APK consistency: {error}')
    manifest_path = 'github/assets/' + manifest
    if manifest_path in files:
        try:
            data = read_bytes(files[manifest_path])
            sections.append(('Release manifest (raw inventory; schema qualification remains packaging work)', text(data, manifest)))
            if PLACEHOLDER.search(text(data, manifest)):
                problems.append('Unresolved placeholder in release manifest')
            read_json(data)
        except ARTIFACT_ERRORS as error:
            problems.append(f'Manifest JSON: {error}')
    sums_path = 'github/assets/SHA256SUMS.txt'
    if sums_path in files:
        try:
            sums = read_bytes(files[sums_path])
            sections.append(('Outer checksums (includes Nexus-only ZIP)', text(sums, 'SHA256SUMS.txt')))
            if all(name in files for name in assets):
                checksums(sums, {assets[name]: hashes[name] for name in assets if name != sums_path})
        except ARTIFACT_ERRORS as error:
            problems.append(f'Outer checksums: {error}')
    zip_path = f'nexus/{nexus}'
    if all(name in files for name in (zip_path, apk_path, receipt_path)):
        try:
            sections.extend(zip_review(files[zip_path], apk, hashes[apk_path], receipt, read_bytes(files[receipt_path])))
        except ARTIFACT_ERRORS as error:
            problems.append(f'Nexus ZIP consistency: {error}')
    if complete and problems:
        raise ValueError('Complete preview refused:\n' + '\n'.join(problems))
    output.mkdir(parents=True)
    for name, data in blobs.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for name, path in files.items():
        destination = output / 'files' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        copy_snapshot(path, destination, hashes[name])
    limits = ('Exact text, not GitHub/Nexus rendering. No independent APK signature or Android manifest verification; '
              'receipt claims are compared with embedded build provenance. Release manifest schema, runtime/upgrade tests, '
              'external links, platform previews and owner approval remain separate checks. This snapshot does not authorize publication.')
    status = 'COMPLETE INPUT SNAPSHOT - NOT RELEASE APPROVAL' if complete else 'DRAFT PREVIEW - NOT FOR SUBMISSION'
    report = f'{status}\n\n{limits}\n\nMissing or inconsistent inputs:\n' + ('\n'.join(problems) if problems else 'None detected within these checks.')
    (output / 'review.txt').write_text(report + '\n', encoding='utf-8')
    items = [('Review status and limits', report), *sections]
    html = ['<!doctype html><html lang="en"><meta charset="utf-8">',
            '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">',
            '<title>DML release review</title><style>body{max-width:1100px;margin:2em auto;padding:1em;font-family:system-ui}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f1f1;padding:1em}section{margin:3em 0}a{overflow-wrap:anywhere}</style>',
            '<h1>' + escape(status) + '</h1><nav><ul>']
    for i, (label, _) in enumerate(items):
        html.append(f'<li><a href="#section-{i}">{escape(label)}</a></li>')
    html.append('</ul></nav>')
    for i, (label, content) in enumerate(items):
        html.append(f'<section id="section-{i}"><h2>{escape(label)}</h2><pre>{escape(content)}</pre></section>')
    html.append('<h2>Snapshot files</h2><ul>')
    for name in [*blobs, *('files/' + name for name in files)]:
        html.append(f'<li><a href="{escape(quote(name, safe="/"), quote=True)}">{escape(name)}</a></li>')
    html.append('</ul><p>Private review folder. Do not upload it as a release asset.</p></html>')
    pending_index = output / 'index.html.pending'
    pending_index.write_text('\n'.join(html), encoding='utf-8')
    snapshot = sorted(p for p in output.rglob('*') if p.is_file() and p != pending_index)
    sums = ''.join(f'{digest(p)}  {p.relative_to(output).as_posix()}\n' for p in snapshot)
    sums += f'{digest(pending_index)}  index.html\n'
    (output / 'review.sha256').write_text(sums, encoding='utf-8')
    # Publish the entry page last. Interrupted generation has no finished index.
    pending_index.rename(output / 'index.html')
    return problems


def verify(output):
    if output.is_symlink() or (output / 'review.sha256').is_symlink():
        raise ValueError('Linked review directory or checksum file')
    output = output.resolve()
    expected = {}
    for line in text(read_bytes(output / 'review.sha256'), 'review.sha256').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
        if not match:
            raise ValueError('Invalid review checksum file')
        name = PurePosixPath(match[2])
        if name.is_absolute() or '..' in name.parts or '\\' in match[2] or name.as_posix() != match[2] or name.as_posix() in expected:
            raise ValueError('Unsafe or duplicate review checksum path')
        expected[name.as_posix()] = match[1]
    actual = {}
    for path in output.rglob('*'):
        if path.is_symlink():
            raise ValueError('Linked file in review snapshot')
        if path.is_file() and path.relative_to(output).as_posix() != 'review.sha256':
            if path.stat().st_size > MAX_FILE:
                raise ValueError('Oversized file in review snapshot')
            if len(actual) >= 256:
                raise ValueError('Too many files in review snapshot')
            actual[path.relative_to(output).as_posix()] = digest(path)
    if actual != expected or 'index.html' not in expected or 'review.txt' not in expected:
        raise ValueError('Review snapshot changed, is missing files or has unexpected files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--input', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--version-code', type=int)
    parser.add_argument('--source-commit')
    parser.add_argument('--previous-ref')
    parser.add_argument('--certificate-sha256')
    parser.add_argument('--complete', action='store_true')
    parser.add_argument('--verify', type=Path, help='Check a previously generated snapshot without regenerating it')
    args = parser.parse_args()
    try:
        if args.verify:
            if any((args.input, args.output, args.version, args.version_code, args.source_commit, args.previous_ref, args.certificate_sha256, args.complete)):
                raise ValueError('--verify cannot be combined with generation options')
            verify(args.verify)
            print('Snapshot bytes match review.sha256. This is integrity, not approval or authenticity.')
        else:
            if not all((args.input, args.output, args.version, args.version_code, args.source_commit, args.previous_ref)):
                raise ValueError('Generation requires input/output, version/code, source commit and previous ref')
            problems = create_preview(args.root, args.input, args.output, args.version, args.version_code,
                                      args.source_commit, args.previous_ref, args.certificate_sha256, args.complete)
            print(f'Preview: {args.output / "index.html"}; {len(problems)} missing/inconsistent input(s). No publication performed.')
        return 0
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'ERROR: {error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
