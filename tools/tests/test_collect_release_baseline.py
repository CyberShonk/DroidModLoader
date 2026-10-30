"""Offline GitHub/SDK fixtures; real published-byte verification is separate evidence."""

import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch, Mock
import urllib.error
from urllib.parse import urlsplit, parse_qs


SPEC = importlib.util.spec_from_file_location('release_baseline', Path(__file__).resolve().parents[1] / 'collect-release-baseline.py')
BASELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASELINE)
VERSION = 'v0.8.0-beta'
CERT = 'a' * 64


class FixtureGitHub:
    def __init__(self, releases, assets, blobs):
        self.releases, self.assets, self.blobs = releases, assets, blobs
        self.downloads = 0
        self.change_after = False
        self.failure = False
        self.change_time_after = False

    def fetch(self, url, destination, limit):
        if self.failure:
            raise ValueError('Injected network failure')
        parsed = urlsplit(url)
        if parsed.hostname == 'github.com':
            self.downloads += 1
            data = self.blobs[url]
        else:
            rows = copy.deepcopy(self.releases if parsed.path.endswith('/releases') else self.assets[int(parsed.path.split('/')[-2])])
            if self.change_after and 'api-after' in destination.parts and parsed.path.endswith('/releases'):
                rows[0]['prerelease'] = not rows[0]['prerelease']
            if self.change_time_after and 'api-after' in destination.parts and parsed.path.endswith('/releases'):
                rows[0]['published_at'] = '2026-07-01T00:00:00Z'
            page = int(parse_qs(parsed.query)['page'][0])
            per_page = int(parse_qs(parsed.query)['per_page'][0])
            data = json.dumps(rows[(page-1)*per_page:page*per_page]).encode()
        if len(data) > limit:
            raise ValueError('Fixture response exceeds request limit')
        with destination.open('xb') as stream:
            stream.write(data)


class ReleaseBaselineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='dml-baseline-test-')
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.root = self.folder / 'repo'
        self.root.mkdir()
        env = os.environ.copy()
        for key in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            env.pop(key, None)
        env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        environment = patch.dict(os.environ, env, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        self.git('init', '--template=', '--initial-branch=main')
        self.git('config', 'user.name', 'Baseline fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('remote', 'add', 'origin', 'git@github.com:example/Dml.git')
        (self.root / '.gitignore').write_text('/release-artifacts/\n')
        (self.root / 'version.properties').write_text('VERSION_NAME=v0.7.0-beta\nVERSION_CODE=3\n')
        self.git('add', '.')
        self.git('commit', '-m', 'Previous release')
        self.git('tag', 'v0.7.0-beta')
        self.git('commit', '--allow-empty', '-m', 'Development source')
        self.tools = self.folder / 'sdk'
        self.tools.mkdir()
        fake = '''#!/usr/bin/env python3
from pathlib import Path
import json,sys
value=json.loads(Path(sys.argv[-1]).read_bytes())
if Path(sys.argv[0]).name=='apksigner':
    assert sys.argv[1:4]==['verify','--verbose','--print-certs']
    if not value['valid']: sys.exit(1)
    print('Signer #1 certificate SHA-256 digest: '+value['cert'])
    if value.get('multiple'): print('Signer #2 certificate SHA-256 digest: '+'b'*64)
else:
    assert sys.argv[1:3]==['dump','badging']
    print("package: name='%s' versionCode='%s' versionName='%s'" % (value['app'],value['code'],value['version']))
    if value['debug']: print('application-debuggable')
'''
        for name in ('apksigner', 'aapt2'):
            path = self.tools / name
            path.write_text(fake)
            path.chmod(0o755)
        self.releases = []
        self.assets = {}
        self.blobs = {}
        self.add_release(1, 'v0.7.0-beta', 3)
        self.client = FixtureGitHub(self.releases, self.assets, self.blobs)
        self.output = self.root / 'release-artifacts' / VERSION / 'maintainer/lookup-01'

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True, stderr=subprocess.PIPE).strip()

    def add_release(self, release_id, tag, code, *, prerelease=False, draft=False, app=BASELINE.APPLICATION_ID,
                    version=None, cert=CERT, debug=False, valid=True, multiple=False,
                    published_at='2026-06-30T00:00:00Z'):
        self.releases.append({'id': release_id, 'tag_name': tag, 'draft': draft, 'prerelease': prerelease,
                              'published_at': None if draft else published_at})
        asset_id = release_id * 10
        url = f'https://github.com/example/Dml/releases/download/{tag}/asset-{asset_id}.apk'
        blob = json.dumps({'app': app, 'version': version or tag, 'code': code, 'cert': cert,
                           'debug': debug, 'valid': valid, 'multiple': multiple}, sort_keys=True).encode()
        self.blobs[url] = blob
        self.assets[release_id] = [{'id': asset_id, 'name': 'renamed-download.apk', 'state': 'uploaded',
                                   'size': len(blob), 'digest': 'sha256:' + hashlib.sha256(blob).hexdigest(),
                                   'browser_download_url': url}]

    def collect(self, **kwargs):
        options = dict(root=self.root, version=VERSION, previous_tag='v0.7.0-beta', certificate=CERT,
                       other_code=0, other_evidence='Confirmed no other distributions', build_tools=self.tools,
                       output=self.output, client=self.client)
        options.update(kwargs)
        return BASELINE.collect(**options)

    def test_baseline_records_actual_apk_and_preserves_git_source(self):
        before = (self.root / 'version.properties').read_bytes()
        self.assertEqual(3, self.collect())
        report = (self.output / 'baseline.txt').read_text()
        self.assertIn('Highest verified GitHub code: 3', report)
        self.assertIn('Combined distributed floor: 3', report)
        self.assertIn('No version reserved', report)
        self.assertTrue((self.output / '10-signature.txt').is_file())
        self.assertEqual(before, (self.root / 'version.properties').read_bytes())
        self.assertEqual('', self.git('status', '--porcelain'))

    def test_prerelease_and_old_named_tag_use_actual_codes(self):
        self.add_release(2, 'test/v0.8.0-beta/7', 7, version=VERSION, prerelease=True)
        self.add_release(3, 'v0.1-beta', 9, version='Old label')
        self.assertEqual(9, self.collect())
        self.assertIn('prerelease=True', (self.output / 'baseline.txt').read_text())

    def test_nonrelease_package_and_drafts_do_not_set_floor(self):
        self.add_release(2, 'dev', 999, app=BASELINE.APPLICATION_ID + '.dev', cert='b'*64, debug=True)
        self.add_release(3, 'draft', 1000, draft=True)
        self.assertEqual(3, self.collect())
        self.assertEqual(2, self.client.downloads)
        self.assertIn('included=False', (self.output / 'baseline.txt').read_text())

    def test_other_distribution_floor_is_combined(self):
        self.assertEqual(15, self.collect(other_code=15, other_evidence='Private candidate code15'))

    def test_cache_reuses_bytes_but_rechecks_signatures(self):
        self.collect()
        downloads = self.client.downloads
        second = self.output.with_name('lookup-02')
        self.assertEqual(3, self.collect(output=second))
        self.assertEqual(downloads, self.client.downloads)
        self.assertTrue((second / '10-signature.txt').exists())

    def test_corrupt_cache_is_not_overwritten(self):
        self.collect()
        cache = next((self.output.parent / 'github-apks').glob('*.apk'))
        cache.write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'Cached APK identity mismatch'):
            self.collect(output=self.output.with_name('lookup-02'))
        self.assertEqual(b'corrupted', cache.read_bytes())

    def test_missing_published_digest_downloads_and_verifies_each_time(self):
        self.assets[1][0]['digest'] = None
        self.assertEqual(3, self.collect())
        self.assertEqual(3, self.collect(output=self.output.with_name('lookup-02')))
        self.assertEqual(2, self.client.downloads)

    def test_unsigned_or_wrong_signer_and_debuggable_release_are_refused(self):
        for changes in ({'valid': False}, {'cert': 'b'*64}, {'debug': True}, {'multiple': True}):
            with self.subTest(changes=changes):
                original = self.blobs[self.assets[1][0]['browser_download_url']]
                value = json.loads(original)
                value.update(changes)
                blob = json.dumps(value, sort_keys=True).encode()
                url = self.assets[1][0]['browser_download_url']
                self.blobs[url] = blob
                self.assets[1][0].update(size=len(blob), digest='sha256:'+hashlib.sha256(blob).hexdigest())
                with self.assertRaises(ValueError):
                    self.collect(output=self.output.with_name('bad-'+next(iter(changes))))
                self.blobs[url] = original

    def test_older_lower_code_signer_is_recorded_not_adopted(self):
        self.add_release(2, 'v0.1-beta', 1, cert='b'*64, version='1.0',
                         published_at='2026-05-09T22:09:11Z')
        self.assertEqual(3, self.collect())
        report = (self.output / 'baseline.txt').read_text()
        self.assertIn('certificate=' + 'b'*64, report)
        self.assertIn('Expected certificate SHA256: ' + CERT, report)
        self.assertIn('not adopted as signing authority', report)
        self.assertIn('published_at=2026-05-09T22:09:11Z', report)

    def test_historical_signer_requires_both_earlier_time_and_lower_code(self):
        for index, (time, code) in enumerate((
                ('2026-05-09T22:09:11Z', 3), ('2026-05-09T22:09:11Z', 4),
                ('2026-06-30T00:00:00Z', 1), ('2026-07-01T00:00:00Z', 1))):
            with self.subTest(time=time, code=code):
                self.releases[:] = self.releases[:1]
                self.add_release(2, 'v0.1-beta', code, cert='b'*64, published_at=time)
                output = self.output.with_name(f'boundary-{index}')
                with self.assertRaisesRegex(ValueError, 'unexpected signer'):
                    self.collect(output=output)
                self.assertFalse((output / 'baseline.txt').exists())

    def test_historical_exception_keeps_signature_and_debug_guards(self):
        for index, changes in enumerate(({'valid': False}, {'debug': True}, {'multiple': True})):
            with self.subTest(changes=changes):
                self.releases[:] = self.releases[:1]
                self.add_release(2, 'v0.1-beta', 1, cert='b'*64,
                                 published_at='2026-05-09T22:09:11Z', **changes)
                with self.assertRaises(ValueError):
                    self.collect(output=self.output.with_name(f'historical-bad-{index}'))

    def test_invalid_or_changed_publication_time_is_refused(self):
        for index, time in enumerate((None, '', 'unknown', '2026-02-30T00:00:00Z',
                                      '2026-06-30T00:00:00+00:00')):
            with self.subTest(time=time):
                self.releases[0]['published_at'] = time
                with self.assertRaises(ValueError):
                    self.collect(output=self.output.with_name(f'bad-time-{index}'))
        self.releases[0]['published_at'] = '2026-06-30T00:00:00Z'
        self.client.change_time_after = True
        with self.assertRaisesRegex(ValueError, 'changed during collection'):
            self.collect()
        self.assertFalse((self.output / 'baseline.txt').exists())

    def test_previous_published_identity_must_match_local_tag(self):
        self.add_release(2, 'other', 8)
        self.assets[1] = []
        with self.assertRaisesRegex(ValueError, 'no inspectable APK'):
            self.collect()

    def test_missing_selected_release_cannot_use_another_release(self):
        self.releases[0]['tag_name'] = 'v0.6.0-beta'
        with self.assertRaisesRegex(ValueError, 'previous published release APK'):
            self.collect()

    def test_wrong_previous_version_name_is_refused(self):
        self.releases.clear()
        self.add_release(1, 'v0.7.0-beta', 3, version='wrong version')
        with self.assertRaisesRegex(ValueError, 'differs from the selected Git tag'):
            self.collect()

    def test_download_hash_or_size_mismatch_is_refused(self):
        self.assets[1][0]['digest'] = 'sha256:' + 'f'*64
        with self.assertRaisesRegex(ValueError, 'digest differs'):
            self.collect()
        self.assets[1][0]['digest'] = None
        self.assets[1][0]['size'] += 1
        with self.assertRaisesRegex(ValueError, 'size differs'):
            self.collect(output=self.output.with_name('size-mismatch'))

    def test_paginated_release_and_asset_history_is_complete(self):
        self.add_release(2, 'test', 8, prerelease=True)
        with patch.object(BASELINE, 'PAGE_SIZE', 1):
            self.assertEqual(8, self.collect())
        self.assertTrue((self.output / 'api-before/releases-03.json').exists())
        self.assertTrue((self.output / 'api-before/assets-1-02.json').exists())

    def test_truncated_pagination_is_not_a_partial_success(self):
        with patch.object(BASELINE, 'PAGE_SIZE', 1), patch.object(BASELINE, 'MAX_PAGES', 1):
            with self.assertRaisesRegex(ValueError, 'history is incomplete'):
                self.collect()
        self.assertFalse((self.output / 'baseline.txt').exists())

    def test_changing_publications_and_duplicate_ids_are_refused(self):
        self.client.change_after = True
        with self.assertRaisesRegex(ValueError, 'changed during collection'):
            self.collect()
        self.client.change_after = False
        self.releases.append(copy.deepcopy(self.releases[0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate release ID'):
            self.collect(output=self.output.with_name('duplicate'))

    def test_network_failure_does_not_produce_a_baseline(self):
        self.client.failure = True
        with self.assertRaisesRegex(ValueError, 'network failure'):
            self.collect()
        self.assertFalse((self.output / 'baseline.txt').exists())
        self.assertEqual('VERSION_NAME=v0.7.0-beta\nVERSION_CODE=3\n', (self.root / 'version.properties').read_text())

    def test_output_must_be_new_ignored_owned_release_directory(self):
        with self.assertRaisesRegex(ValueError, 'Baseline output must'):
            self.collect(output=self.root / 'public-output')
        self.collect()
        with self.assertRaisesRegex(ValueError, 'output exists'):
            self.collect()

    def test_symlinked_artifact_parent_is_refused(self):
        (self.root / 'release-artifacts').symlink_to(self.folder, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symlinks|leaves the repository'):
            self.collect()

    def test_resource_limits_refuse_incomplete_history(self):
        with patch.object(BASELINE, 'MAX_TOTAL', 1):
            with self.assertRaisesRegex(ValueError, 'resource limit'):
                self.collect()
        with patch.object(BASELINE, 'MAX_APKS', 0):
            with self.assertRaisesRegex(ValueError, 'resource limit'):
                self.collect(output=self.output.with_name('count-limit'))

    def test_metadata_request_size_and_count_limits_are_failures(self):
        for name in ('MAX_METADATA_TOTAL', 'MAX_REQUESTS', 'MAX_RELEASES', 'MAX_ASSETS'):
            with self.subTest(name=name), patch.object(BASELINE, name, 0):
                with self.assertRaisesRegex(ValueError, 'limit reached'):
                    self.collect(output=self.output.with_name(name.lower()))

    def test_bad_confirmation_and_origin_fail_before_output(self):
        with self.assertRaises(ValueError):
            self.collect(other_code=-1)
        with self.assertRaises(ValueError):
            self.collect(other_evidence='')
        self.git('remote', 'set-url', 'origin', 'https://token@github.com/example/Dml.git')
        with self.assertRaisesRegex(ValueError, 'without credentials'):
            self.collect()
        self.assertFalse(self.output.exists())

    def test_redirect_boundary_rejects_http_credentials_unknown_hosts(self):
        for url in ('http://github.com/file', 'https://evil.invalid/file', 'https://token@github.com/file',
                    'https://github.com:444/file', 'https://github.com/file#fragment'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                BASELINE.endpoint(url)
        self.assertEqual('https://release-assets.githubusercontent.com/file?signature=value',
                         BASELINE.endpoint('https://release-assets.githubusercontent.com/file?signature=value'))

    def test_http_error_does_not_expose_signed_url_or_body(self):
        client = BASELINE.GitHub()
        error = urllib.error.HTTPError('https://release-assets.githubusercontent.com/file?secret=token', 403, 'secret body', None, None)
        client.opener = Mock()
        client.opener.open.side_effect = error
        with self.assertRaisesRegex(ValueError, 'HTTP 403') as caught:
            client.fetch('https://api.github.com/test', self.folder/'error.json', 10)
        self.assertNotIn('secret', str(caught.exception))
        self.assertNotIn('token', str(caught.exception))
        client.opener.open.assert_called_once()

    def test_http_streaming_limits_and_truncation_are_failures(self):
        class Response(io.BytesIO):
            status = 200
            def __init__(self, data, length):
                super().__init__(data)
                self.headers = {} if length is None else {'Content-Length': str(length)}
            def geturl(self):
                return 'https://api.github.com/test'
        client = BASELINE.GitHub()
        client.opener = Mock()
        cases = ((b'abc', 3, 10, None), (b'abc', 9, 10, 'Truncated'),
                 (b'abc', None, 2, 'exceeds'), (b'abc', 3, 2, 'content length'))
        for index, (data, length, limit, error) in enumerate(cases):
            client.opener.open.return_value = Response(data, length)
            path = self.folder / f'http-{index}.bin'
            if error:
                with self.assertRaisesRegex(ValueError, error):
                    client.fetch('https://api.github.com/test', path, limit)
            else:
                client.fetch('https://api.github.com/test', path, limit)
                self.assertEqual(data, path.read_bytes())
        request = client.opener.open.call_args[0][0]
        self.assertNotIn('Authorization', dict(request.header_items()))

    def test_tool_timeout_is_a_failure(self):
        with patch.object(BASELINE.subprocess, 'run', side_effect=subprocess.TimeoutExpired('inspection', 60)):
            with self.assertRaisesRegex(ValueError, 'timed out'):
                BASELINE.tool(['inspection'], self.folder/'timeout.txt')


if __name__ == '__main__':
    unittest.main()
