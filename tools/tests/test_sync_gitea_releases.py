"""Release transfer boundaries; fake APIs never make network requests or publish."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / 'sync_gitea_releases.py'
SPEC = importlib.util.spec_from_file_location('sync_gitea_releases', MODULE)
syncer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = syncer
SPEC.loader.exec_module(syncer)
TAG = 'v1.0.0'
COMMIT = 'a' * 40


def fixture():
    apk, source, manifest_name, checksum_name = syncer.names_for(TAG)
    files = {apk: b'original signed APK', source: b'original published source ZIP'}
    manifest = {'file': apk, 'versionName': '1.0.0', 'versionCode': 99,
                'applicationId': 'io.github.originosfcmguardian', 'debuggable': False,
                'variant': 'release', 'minified': True, 'signerCertificateSha256': syncer.CERTIFICATE,
                'sourceTag': TAG, 'sourceCommit': COMMIT, 'bytes': len(files[apk]),
                'sha256': syncer.sha256(files[apk]),
                'sourceArchive': {'file': source, 'sha256': syncer.sha256(files[source])}}
    files[manifest_name] = json.dumps(manifest).encode()
    files[checksum_name] = ''.join(f'{syncer.sha256(data)}  {name}\n' for name, data in files.items()).encode()
    release = {'tag_name': TAG, 'draft': False, 'prerelease': False, 'name': 'Guardian 1.0.0', 'body': 'Actual notes'}
    return release, files


class FakeClient:
    token = 'test-only-placeholder'

    def __init__(self, existing=None, fail_upload=None, commit=COMMIT):
        self.release = copy.deepcopy(existing)
        self.mutations = []
        self.uploads = []
        self.fail_upload = fail_upload
        self.commit = commit

    def target(self, path, method='GET', body=None):
        if path == '/git/ref/tags/' + TAG:
            return {'ref': 'refs/tags/' + TAG, 'object': {'type': 'commit', 'sha': self.commit}}
        if method == 'POST':
            self.mutations.append((method, copy.deepcopy(body)))
            self.release = dict(body, id=123, assets=[])
        elif method == 'PATCH':
            self.mutations.append((method, copy.deepcopy(body)))
            self.release.update(body)
        return copy.deepcopy(self.release)

    def upload(self, release_id, name, data):
        if self.fail_upload == name:
            raise syncer.SyncError('simulated upload failure')
        asset = {'id': len(self.release['assets']) + 1, 'name': name, 'size': len(data),
                 'state': 'uploaded', 'digest': 'sha256:' + syncer.sha256(data)}
        self.release['assets'].append(asset)
        self.uploads.append(name)
        return copy.deepcopy(asset)

    def target_asset(self, asset):
        raise AssertionError('Digest is present; downloading again is unnecessary')


class ReleaseSyncTests(unittest.TestCase):
    def setUp(self):
        # Fake API messages must not look like real publications in CI output.
        output = patch('sys.stdout', new_callable=io.StringIO)
        self.output = output.start()
        self.addCleanup(output.stop)

    def prepare(self):
        release, files = fixture()
        return syncer.validate_source(release, files, COMMIT)

    def test_hash_mismatch_stops_before_target_requests(self):
        release, files = fixture()
        files[syncer.names_for(TAG)[0]] = b'tampered'
        with self.assertRaisesRegex(syncer.SyncError, 'Checksum mismatch'):
            syncer.validate_source(release, files, COMMIT)

    def test_source_commit_mismatch_is_rejected(self):
        release, files = fixture()
        with self.assertRaisesRegex(syncer.SyncError, 'source commit mismatch'):
            syncer.validate_source(release, files, 'b' * 40)

    def test_filename_traversal_and_non_version_tags_rejected(self):
        for tag in ['../../private', 'v1.0.0/secret', 'v1.0.0?token=x', 'v1.00.0', 'main']:
            with self.subTest(tag=tag), self.assertRaises(syncer.SyncError):
                syncer.names_for(tag)
        self.assertEqual(syncer.names_for('v1.2.3-rc.1')[0], 'OriginOS-FCM-Guardian-1.2.3-rc.1.apk')

    def test_missing_or_extra_attachments_rejected(self):
        release, files = fixture()
        for name in ['missing', 'extra']:
            altered = dict(files)
            if name == 'missing':
                del altered['artifact-manifest.json']
            else:
                altered['debug.keystore'] = b'never upload'
            with self.assertRaisesRegex(syncer.SyncError, 'missing release attachment'):
                syncer.validate_source(release, altered, COMMIT)

    def test_target_tag_mismatch_never_creates_release(self):
        client = FakeClient(commit='b' * 40)
        with self.assertRaisesRegex(syncer.SyncError, 'tag commit differs'):
            syncer.sync(client, self.prepare(), [], publish=True)
        self.assertFalse(client.mutations)

    def test_gitea_tag_list_is_filtered_exactly_then_annotated_tag_is_peeled(self):
        client = syncer.Client()
        with patch.object(client, 'source', side_effect=[
            [{'ref': 'refs/tags/v1.0.0-rc.1', 'object': {'type': 'commit', 'sha': 'b' * 40}},
             {'ref': 'refs/tags/' + TAG, 'object': {'type': 'tag', 'sha': 'c' * 40}}],
            {'object': {'type': 'commit', 'sha': COMMIT}}]):
            self.assertEqual(syncer.tag_commit(client, TAG, True), COMMIT)

    def test_default_dry_run_never_mutates(self):
        client = FakeClient()
        syncer.sync(client, self.prepare(), [])
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_failed_upload_retains_draft_then_retry_finishes(self):
        prepared = self.prepare()
        names = list(prepared.files)
        client = FakeClient(fail_upload=names[1])
        with self.assertRaisesRegex(syncer.SyncError, 'upload failure'):
            syncer.sync(client, prepared, [], publish=True)
        self.assertTrue(client.release['draft'])
        self.assertEqual(client.uploads, names[:1])
        existing = copy.deepcopy(client.release)
        client.fail_upload = None
        syncer.sync(client, prepared, [existing], publish=True)
        self.assertFalse(client.release['draft'])
        self.assertEqual(client.uploads, names)
        self.assertEqual([method for method, _ in client.mutations], ['POST', 'PATCH'])

    def test_repeated_sync_and_immutable_noop_do_not_write(self):
        client = FakeClient()
        prepared = self.prepare()
        syncer.sync(client, prepared, [], publish=True)
        before = copy.deepcopy(client.mutations)
        client.uploads.clear()
        client.release['immutable'] = True
        syncer.sync(client, prepared, [copy.deepcopy(client.release)], publish=True)
        self.assertFalse(client.uploads)
        self.assertEqual(client.mutations, before)

    def test_existing_different_hash_is_not_deleted_or_replaced(self):
        client = FakeClient()
        prepared = self.prepare()
        syncer.sync(client, prepared, [], publish=True)
        client.release['assets'][0]['digest'] = 'sha256:' + '0' * 64
        before = copy.deepcopy(client.mutations)
        with self.assertRaisesRegex(syncer.SyncError, 'refusing replacement'):
            syncer.sync(client, prepared, [client.release], publish=True)
        self.assertEqual(client.mutations, before)

    def test_missing_github_digest_falls_back_to_download_hash(self):
        data = b'transferred APK bytes'
        asset = {'id': 1, 'name': 'release.apk', 'size': len(data), 'state': 'uploaded'}
        client = syncer.Client()
        with patch.object(client, 'target_asset', return_value=data) as download:
            syncer.verify_target_asset(client, asset, 'release.apk', data)
            self.assertEqual(download.call_count, 1)

    def test_gitea_download_never_gets_authentication(self):
        release, files = fixture()
        name, data = next(iter(files.items()))
        asset = {'name': name, 'size': len(data), 'browser_download_url':
                 f'{syncer.SOURCE}/{syncer.REPOSITORY}/releases/download/{TAG}/{name}'}
        client = syncer.Client('test-only-placeholder')
        with patch.object(client, 'request', return_value=data) as request:
            client.source_asset(release, asset)
            self.assertNotIn('authenticated', request.call_args.kwargs)
            asset['browser_download_url'] = 'https://example.com/evil.apk'
            with self.assertRaisesRegex(syncer.SyncError, 'attachment URL'):
                client.source_asset(release, asset)
            self.assertEqual(request.call_count, 1)

    def test_authenticated_redirect_strips_credentials_and_rejects_other_hosts(self):
        handler = syncer.SafeRedirect({'api.github.com', 'release-assets.githubusercontent.com'})
        request = syncer.urllib.request.Request(syncer.TARGET_API + '/releases/assets/1',
                                                headers={'Authorization': 'Bearer test-only-placeholder'})
        redirected = handler.redirect_request(request, None, 302, 'Found', {},
            'https://release-assets.githubusercontent.com/github-production-release-asset/a?signature=opaque')
        self.assertIsNone(redirected.get_header('Authorization'))
        with self.assertRaisesRegex(syncer.SyncError, 'destination'):
            handler.redirect_request(request, None, 302, 'Found', {}, syncer.SOURCE + '/steal')


if __name__ == '__main__':
    unittest.main()
