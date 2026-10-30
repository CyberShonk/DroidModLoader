#!/usr/bin/env python3
"""Inspect published GitHub APK version codes without preparing or publishing a release."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


APPLICATION_ID = 'com.shonkware.droidmodloader'
MAX_CODE = 2100000000
PAGE_SIZE = 100
MAX_PAGES = 20
MAX_METADATA = 8 * 1024 * 1024
MAX_METADATA_TOTAL = 64 * 1024 * 1024
MAX_REQUESTS = 100
MAX_RELEASES = 100
MAX_ASSETS = 2000
MAX_APK = 256 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024
MAX_APKS = 100
MAX_TOOL_OUTPUT = 2 * 1024 * 1024
HOSTS = {'api.github.com', 'github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'}


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True, stderr=subprocess.PIPE).strip()


def number(value, label, minimum=1, maximum=MAX_CODE):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f'{label} must be between {minimum} and {maximum}')
    return value


def short_text(value, label, maximum=500):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError(f'{label} must be a short line without control characters')
    return value


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def json_data(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field')
            result[key] = value
        return result
    def nonfinite(value):
        raise ValueError('Nonstandard JSON number')
    return json.loads(data, object_pairs_hook=unique, parse_constant=nonfinite)


def endpoint(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in HOSTS or parsed.username or parsed.password or parsed.port not in (None, 443) or parsed.fragment:
        raise ValueError('Unsupported HTTPS endpoint or redirect host')
    return url


class Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5
    max_repeats = 2

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return super().redirect_request(request, fp, code, msg, headers, endpoint(newurl))


class GitHub:
    def __init__(self):
        self.opener = urllib.request.build_opener(Redirects())

    def fetch(self, url, destination, limit):
        """Bounded streaming GET; keep signed URLs and HTTP bodies out of errors."""
        headers = {'User-Agent': 'DroidModLoader-release-tools'}
        if urllib.parse.urlsplit(endpoint(url)).hostname == 'api.github.com':
            headers.update(Accept='application/vnd.github+json', **{'X-GitHub-Api-Version': '2022-11-28'})
        else:
            headers['Accept'] = 'application/octet-stream'
        request = urllib.request.Request(url, headers=headers)
        deadline = time.monotonic() + 180
        try:
            with self.opener.open(request, timeout=15) as response, destination.open('xb') as output:
                endpoint(response.geturl())
                if response.status != 200:
                    raise ValueError('Unexpected HTTP status; refusing partial response')
                length = response.headers.get('Content-Length')
                if length is not None and (not length.isdigit() or int(length) > limit):
                    raise ValueError('HTTP content length exceeds the request limit')
                total = 0
                while True:
                    if time.monotonic() > deadline:
                        raise ValueError('GitHub request exceeded its time budget')
                    chunk = response.read1(65536)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > limit:
                        raise ValueError('GitHub response exceeds the request limit')
                    output.write(chunk)
                if length is not None and total != int(length):
                    raise ValueError('Truncated GitHub response')
        except urllib.error.HTTPError as error:
            raise ValueError(f'GitHub request failed (HTTP {error.code}); no offline fallback was selected') from None
        except (urllib.error.URLError, TimeoutError) as error:
            raise ValueError('GitHub request failed; inspect connectivity or explicitly confirm an offline baseline') from None


def repository(root):
    remote = git(root, 'remote', 'get-url', 'origin')
    match = re.fullmatch(r'(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*?)(?:\.git)?/?', remote)
    if not match:
        raise ValueError('origin must be a GitHub repository without credentials')
    return match[1]


def previous_release(root, tag):
    if git(root, 'rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('Full Git history is required for the previous release')
    ref = 'refs/tags/' + tag
    git(root, 'check-ref-format', ref)
    commit = git(root, 'rev-parse', '--verify', ref + '^{commit}')
    git(root, 'merge-base', '--is-ancestor', commit, 'HEAD')
    version = git(root, 'show', ref + ':version.properties')
    names = re.findall(r'^VERSION_NAME=([^\r\n]+)\r?$', version, re.M)
    codes = re.findall(r'^VERSION_CODE=([0-9]{1,10})\r?$', version, re.M)
    if names != [tag] or len(codes) != 1:
        raise ValueError('Previous tag differs from its version.properties identity')
    return commit, number(int(codes[0]), 'Previous release code')


def pages(client, url, folder, prefix, budget):
    result = []
    for page in range(1, MAX_PAGES + 1):
        path = folder / f'{prefix}-{page:02d}.json'
        if budget['requests'] >= MAX_REQUESTS:
            raise ValueError('Metadata request limit reached; history is incomplete')
        client.fetch(url + f'?per_page={PAGE_SIZE}&page={page}', path, MAX_METADATA)
        budget['requests'] += 1
        budget['bytes'] += path.stat().st_size
        if path.stat().st_size > MAX_METADATA or budget['bytes'] > MAX_METADATA_TOTAL:
            raise ValueError('Metadata size limit reached; history is incomplete')
        data = json_data(path.read_bytes())
        if not isinstance(data, list) or len(data) > PAGE_SIZE:
            raise ValueError('Unexpected GitHub pagination response')
        result.extend(data)
        if len(data) < PAGE_SIZE:
            return result
    raise ValueError('GitHub pagination limit reached; history is incomplete')


def inventory(client, repo, folder):
    folder.mkdir()
    budget = {'requests': 0, 'bytes': 0}
    releases = pages(client, f'https://api.github.com/repos/{repo}/releases', folder, 'releases', budget)
    if len(releases) > MAX_RELEASES:
        raise ValueError('Release count limit reached; history is incomplete')
    seen = set()
    tags = set()
    asset_ids = set()
    result = []
    for release in releases:
        if not isinstance(release, dict):
            raise ValueError('Malformed GitHub release')
        release_id = number(release.get('id'), 'Release ID', maximum=2**63-1)
        if release_id in seen:
            raise ValueError('Duplicate release ID; publication inventory may have changed')
        seen.add(release_id)
        if type(release.get('draft')) is not bool or type(release.get('prerelease')) is not bool:
            raise ValueError('Release publication flags unavailable')
        if release['draft']:
            continue
        tag = short_text(release.get('tag_name'), 'Release tag', 255)
        published_at = short_text(release.get('published_at'), 'Release publication time')
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', published_at):
            raise ValueError('Invalid release publication time')
        try:
            datetime.strptime(published_at, '%Y-%m-%dT%H:%M:%SZ')
        except ValueError:
            raise ValueError('Invalid release publication time') from None
        if tag in tags:
            raise ValueError('Ambiguous published release tag')
        tags.add(tag)
        assets = pages(client, f'https://api.github.com/repos/{repo}/releases/{release_id}/assets', folder, f'assets-{release_id}', budget)
        apks = []
        for asset in assets:
            if not isinstance(asset, dict):
                raise ValueError('Malformed release asset')
            asset_id = number(asset.get('id'), 'Asset ID', maximum=2**63-1)
            if asset_id in asset_ids:
                raise ValueError('Duplicate asset ID; publication inventory may have changed')
            asset_ids.add(asset_id)
            if len(asset_ids) > MAX_ASSETS:
                raise ValueError('Asset count limit reached; history is incomplete')
            name = short_text(asset.get('name'), 'Asset name', 255)
            if not name.lower().endswith('.apk'):
                continue
            if asset.get('state') != 'uploaded':
                raise ValueError('Published APK upload is incomplete')
            size = number(asset.get('size'), 'APK size', maximum=MAX_APK)
            url = endpoint(short_text(asset.get('browser_download_url'), 'APK download URL', 2000))
            published_hash = asset.get('digest')
            if published_hash is not None and (not isinstance(published_hash, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', published_hash)):
                raise ValueError('Unsupported published asset digest')
            apks.append((asset_id, name, size, url, published_hash))
        if not apks:
            raise ValueError(f'Published release {tag} has no inspectable APK; history needs explicit review')
        result.append((release_id, tag, release['prerelease'], published_at, tuple(sorted(apks))))
    return sorted(result)


def tool(command, log):
    with log.open('xb') as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=60)
        except subprocess.TimeoutExpired:
            raise ValueError(f'Android inspection timed out; see {log.name}') from None
    if log.stat().st_size > MAX_TOOL_OUTPUT:
        raise ValueError(f'Android tool output exceeds limit; see {log.name}')
    if result.returncode:
        raise ValueError(f'Android inspection failed; see {log.name}')
    return log.read_text(encoding='utf-8')


def inspect(apk, build_tools, folder, asset_id):
    signature = tool([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], folder / f'{asset_id}-signature.txt')
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', signature, re.M)
    if len(signers) != 1:
        raise ValueError('Exactly one verified APK signer is required')
    badging = tool([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)], folder / f'{asset_id}-badging.txt')
    lines = re.findall(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'.*$", badging, re.M)
    if len(lines) != 1:
        raise ValueError('APK package/version information unavailable')
    app, code, version = lines[0]
    short_text(app, 'APK application ID')
    short_text(version, 'APK version name')
    return app, number(int(code), 'APK version code'), version, signers[0].lower(), 'application-debuggable' in badging.splitlines()


def collect(root, version, previous_tag, certificate, other_code, other_evidence, build_tools, output, client=None, progress=None):
    root = root.resolve()
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta)?', version) or len(version) > 64:
        raise ValueError('Unsupported target version')
    if not re.fullmatch(r'[0-9a-f]{64}', certificate):
        raise ValueError('Supply the confirmed release certificate SHA-256')
    number(other_code, 'Confirmed other distributed code', minimum=0)
    short_text(other_evidence, 'Other distribution evidence')
    build_tools = build_tools.resolve()
    if not all((build_tools / name).is_file() for name in ('apksigner', 'aapt2')):
        raise ValueError('Build tools directory must contain apksigner and aapt2')
    repo = repository(root)
    previous_commit, previous_code = previous_release(root, previous_tag)
    parent = root / 'release-artifacts' / version / 'maintainer'
    if not parent.resolve().is_relative_to(root):
        raise ValueError('Release artifact root leaves the repository')
    for component in (parent, *parent.parents):
        if component == root:
            break
        if component.is_symlink():
            raise ValueError('Release artifact path cannot contain symlinks')
    output = output.absolute()
    if not output.resolve().is_relative_to(parent.resolve()) or output.resolve() == parent.resolve():
        raise ValueError('Baseline output must be a new directory under release-artifacts/<version>/maintainer/')
    if output.exists() or output.is_symlink():
        raise ValueError('Baseline output exists; choose a new evidence directory')
    for component in output.parents:
        if component == parent:
            break
        if component.is_symlink():
            raise ValueError('Baseline output path cannot contain symlinks')
    if subprocess.run(['git', '-C', str(root), 'check-ignore', '--quiet', '--no-index', '--', str(output.relative_to(root))]).returncode != 0:
        raise ValueError('Baseline output must be Git-ignored')
    cache = parent / 'github-apks'
    if cache.is_symlink():
        raise ValueError('APK cache cannot be a symlink')
    output.mkdir(parents=True)
    cache.mkdir(parents=True, exist_ok=True)
    client = client or GitHub()
    if progress:
        progress('Reading published releases and APK assets')
    before = inventory(client, repo, output / 'api-before')
    previous_times = [published_at for _, tag, _, published_at, _ in before if tag == previous_tag]
    if len(previous_times) != 1:
        raise ValueError('Selected previous published release APK was not verified')
    previous_time = previous_times[0]
    records = []
    total = 0
    previous_seen = False
    highest = 0
    for release_id, tag, prerelease, published_at, apks in before:
        for asset_id, name, size, url, published_hash in apks:
            if progress:
                progress(f'Inspecting {tag}, APK asset {asset_id}')
            if len(records) >= MAX_APKS or total + size > MAX_TOTAL:
                raise ValueError('Published APK resource limit reached; history is incomplete')
            total += size
            expected = published_hash[7:] if published_hash else None
            apk = cache / (expected + '.apk') if expected else None
            if apk is not None and apk.is_symlink():
                raise ValueError('Linked APK cache entry')
            if apk is None or not apk.exists():
                partial = output / f'{asset_id}.part'
                client.fetch(url, partial, min(size, MAX_APK))
                if partial.stat().st_size != size:
                    raise ValueError('Downloaded APK size differs from GitHub metadata')
                actual = digest(partial)
                if expected is not None and actual != expected:
                    raise ValueError('Downloaded APK digest differs from GitHub metadata')
                apk = cache / (actual + '.apk')
                if apk.is_symlink():
                    raise ValueError('Linked APK cache entry')
                if apk.exists():
                    if digest(apk) != actual:
                        raise ValueError('Existing cached APK hash mismatch; refusing replacement')
                else:
                    # Same filesystem; hard-link promotion cannot overwrite another entry.
                    try:
                        os.link(partial, apk)
                    except FileExistsError:
                        if apk.is_symlink() or digest(apk) != actual:
                            raise ValueError('Conflicting APK cache entry') from None
                partial.unlink()
            if not apk.is_file() or apk.stat().st_size != size or expected is not None and digest(apk) != expected:
                raise ValueError('Cached APK identity mismatch; refusing reuse')
            actual = digest(apk)
            app, code, apk_version, signer, debuggable = inspect(apk, build_tools, output, asset_id)
            if digest(apk) != actual:
                raise ValueError('Cached APK changed during inspection')
            included = app == APPLICATION_ID
            if included:
                historical = tag != previous_tag and published_at < previous_time and code < previous_code
                if debuggable:
                    raise ValueError(f'Release-package APK is debuggable: asset {asset_id}')
                if signer != certificate and not historical:
                    raise ValueError(f'Release-package APK has unexpected signer: asset {asset_id}')
                highest = max(highest, code)
                if tag == previous_tag:
                    if code != previous_code or apk_version != previous_tag:
                        raise ValueError('Published previous APK differs from the selected Git tag version')
                    previous_seen = True
            records.append((tag, prerelease, published_at, asset_id, name, actual, app, code, apk_version, signer, debuggable, included))
    if not previous_seen or not highest:
        raise ValueError('Selected previous published release APK was not verified')
    if progress:
        progress('Rechecking publication inventory for changes')
    after = inventory(client, repo, output / 'api-after')
    if after != before:
        raise ValueError('Published releases/assets changed during collection; no complete baseline produced')
    floor = max(highest, other_code)
    report = [
        'Verified published APK baseline', 'Collected at: ' + datetime.now(timezone.utc).isoformat(),
        'Repository: https://github.com/' + repo, 'Application ID: ' + APPLICATION_ID,
        'Previous tag: ' + previous_tag, 'Previous local commit: ' + previous_commit,
        'Expected certificate SHA256: ' + certificate,
        f'Trusted previous publication: {previous_time}; code: {previous_code}',
        'Different historical certificates allowed only before that publication and below that code; not adopted as signing authority.',
        f'Highest verified GitHub code: {highest}',
        f'Confirmed other distributed code: {other_code}', 'Other evidence: ' + other_evidence,
        f'Combined distributed floor: {floor}',
        'No version reserved or modified. Canonical current code must also be considered during preparation.',
        'Public published releases/prereleases inspected; GitHub draft releases are excluded.',
        'Non-release application IDs are listed but excluded from the release code sequence.', '',
    ]
    for tag, prerelease, published_at, asset_id, name, sha, app, code, apk_version, signer, debuggable, included in records:
        report.extend([f'Asset {asset_id}: {name}', f'  tag={tag}; prerelease={prerelease}; published_at={published_at}; package={app}; version={apk_version}; code={code}',
                       f'  sha256={sha}; certificate={signer}; debuggable={debuggable}; included={included}'])
    (output / 'baseline.txt').write_text('\n'.join(report) + '\n', encoding='utf-8')
    return floor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--version', required=True)
    parser.add_argument('--previous-tag', required=True)
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--confirmed-other-code', required=True, type=int)
    parser.add_argument('--other-evidence', required=True)
    parser.add_argument('--build-tools', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        floor = collect(args.root, args.version, args.previous_tag, args.certificate_sha256,
                        args.confirmed_other_code, args.other_evidence, args.build_tools, args.output,
                        progress=lambda message: print(message, file=sys.stderr))
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, subprocess.CalledProcessError, RecursionError) as error:
        parser.exit(1, f'ERROR: {error}\n')
    print(floor)


if __name__ == '__main__':
    main()
