"""Release planning/publication boundaries; all APIs and Git calls are fake."""
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_automation as automation

syncer = automation.syncer
COMMIT = 'a' * 40
OLD_COMMIT = 'b' * 40
ORIGIN = 'https://private-origin.example.org'


def archive_bytes(entries, comment=b''):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries.items():
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 31, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, value)
        archive.comment = comment
    return data.getvalue()


def source_archive_fixture(tag, commit, content=b'public source fixture'):
    return archive_bytes({'OriginOS-FCM-Guardian-' + tag[1:] + '/README.md': content},
                         comment=commit.encode('ascii'))


def fixture(tag='v1.0.1', code=100, commit=COMMIT, legacy=False):
    apk, source, manifest, checksums = syncer.names_for(tag)
    files = {apk: archive_bytes({'classes.dex': b'public APK fixture'}),
             source: source_archive_fixture(tag, commit)}
    identity = {'file': apk, 'versionName': tag[1:], 'versionCode': code,
                'applicationId': 'io.github.originosfcmguardian', 'debuggable': False,
                'variant': 'release', 'minified': True,
                'signerCertificateSha256': syncer.CERTIFICATE,
                'sourceTag': tag, 'sourceCommit': commit, 'bytes': len(files[apk]),
                'sha256': syncer.sha256(files[apk]),
                'sourceArchive': {'file': source, 'sha256': syncer.sha256(files[source])}}
    files[manifest] = json.dumps(identity).encode()
    files[checksums] = ''.join(f'{syncer.sha256(value)}  {name}\n'
                              for name, value in files.items()).encode()
    release = {'tag_name': tag, 'name': 'Guardian ' + tag[1:], 'body': 'Public release notes',
               'draft': False, 'prerelease': False, 'created_at': '2026-10-31T10:00:00Z',
               'assets': []}
    if legacy:
        release['assets'] = [{'name': name, 'size': len(value)} for name, value in files.items()]
    return release, files


class FakeClient:
    token = 'test-only-placeholder'
    source_url = ORIGIN

    def __init__(self, sources, bundles=None, targets=None):
        self.sources = copy.deepcopy(sources)
        self.bundles = bundles or {}
        self.targets = []
        self.payloads = {}
        self.source_refs = {r['tag_name']: COMMIT for r in sources}
        self.target_refs = dict(self.source_refs)
        self.mutations = []
        self.uploads = []
        for release, files in targets or []:
            self.add_target(release, files)

    def add_target(self, release, files):
        target = copy.deepcopy(release)
        target['id'] = len(self.targets) + 1
        target['assets'] = []
        for name, data in files.items():
            target['assets'].append(self.asset(name, data))
        self.targets.append(target)
        return target

    def asset(self, name, data):
        identity = len(self.payloads) + 1
        self.payloads[identity] = data
        return {'id': identity, 'name': name, 'size': len(data), 'state': 'uploaded',
                'digest': 'sha256:' + syncer.sha256(data)}

    def source(self, path):
        if path.startswith('/releases?'):
            return copy.deepcopy(self.sources if path.endswith('page=1') else [])
        prefix = '/git/refs/tags/'
        if path.startswith(prefix):
            tag = path.removeprefix(prefix)
            return {'ref': 'refs/tags/' + tag,
                    'object': {'type': 'commit', 'sha': self.source_refs[tag]}}
        raise AssertionError('Unexpected fake source request')

    def source_asset(self, release, asset):
        return self.bundles[release['tag_name']][asset['name']]

    def target(self, path, method='GET', body=None):
        if path.startswith('/releases?'):
            return copy.deepcopy(self.targets if path.endswith('page=1') else [])
        prefix = '/git/ref/tags/'
        if path.startswith(prefix):
            tag = path.removeprefix(prefix)
            return {'ref': 'refs/tags/' + tag,
                    'object': {'type': 'commit', 'sha': self.target_refs[tag]}}
        if method == 'POST':
            self.mutations.append((method, copy.deepcopy(body)))
            target = dict(body, id=len(self.targets) + 1, assets=[])
            self.targets.append(target)
            return copy.deepcopy(target)
        if path.startswith('/releases/'):
            identity = int(path.removeprefix('/releases/'))
            target = next(t for t in self.targets if t['id'] == identity)
            if method == 'PATCH':
                self.mutations.append((method, copy.deepcopy(body)))
                target.update(body)
            return copy.deepcopy(target)
        raise AssertionError('Unexpected fake target request')

    def target_asset(self, asset):
        return self.payloads[asset['id']]

    def upload(self, identity, name, data):
        target = next(t for t in self.targets if t['id'] == identity)
        asset = self.asset(name, data)
        target['assets'].append(asset)
        self.uploads.append(name)
        return copy.deepcopy(asset)


class ReleaseAutomationTests(unittest.TestCase):
    def setUp(self):
        output = patch('sys.stdout', new_callable=io.StringIO)
        self.output = output.start()
        self.addCleanup(output.stop)
        git = patch.object(automation.subprocess, 'run', side_effect=self.git_result)
        self.git = git.start()
        self.addCleanup(git.stop)
        self.repo = Path('/trusted-workflow-source')

    @staticmethod
    def git_result(args, **kwargs):
        prefix = next((value for value in args if value.startswith('--prefix=')), None)
        if prefix:
            tag = 'v' + prefix.removeprefix('--prefix=OriginOS-FCM-Guardian-').removesuffix('/')
            return subprocess.CompletedProcess(args, 0, stdout=source_archive_fixture(tag, args[-1]))
        return subprocess.CompletedProcess(args, 0,
            stdout=archive_bytes({'README.md': b'clean source'}) if 'archive' in args else b'')

    def plan(self, client, **kwargs):
        return automation.plan_release(client, self.repo, **kwargs)

    def publish(self, client, plan, files):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, value in files.items():
                (directory / name).write_bytes(value)
            automation.publish_release(client, plan, directory, self.repo)

    def test_source_release_classification_is_strict(self):
        source, _ = fixture()
        self.assertEqual(automation.release_kind(source), 'build')
        legacy, _ = fixture(legacy=True)
        self.assertEqual(automation.release_kind(legacy), 'legacy')
        for assets in [[{'name': 'partial.apk'}], [{'name': 'x'}] * 4, 'not-a-list']:
            with self.subTest(assets=assets), self.assertRaises(syncer.SyncError):
                automation.release_kind(dict(source, assets=assets))

    def test_selects_one_oldest_pending_release(self):
        newer, _ = fixture('v1.0.2', code=101)
        older, _ = fixture()
        newer['created_at'] = '2026-11-01T10:00:00Z'
        plan = self.plan(FakeClient([newer, older]))
        self.assertEqual(plan, {'has_pending': True, 'tag': 'v1.0.1', 'commit': COMMIT,
                                'minimum_version_code': 0, 'validate_only': False})

    def test_no_release_does_not_schedule_build(self):
        self.assertEqual(self.plan(FakeClient([])), {'has_pending': False})

    def test_legacy_original_bytes_are_mirrored_without_build(self):
        source, files = fixture('v1.0.0', 99, legacy=True)
        client = FakeClient([source], {source['tag_name']: files})
        self.assertEqual(self.plan(client), {'has_pending': False})
        self.assertEqual(set(client.uploads), set(files))
        self.assertEqual([m for m, _ in client.mutations], ['POST', 'PATCH'])
        self.assertFalse(client.targets[0]['draft'])
        self.assertFalse(any('archive' in call.args[0] for call in self.git.call_args_list))

    def test_legacy_and_completed_versions_set_minimum_version_code(self):
        old, old_files = fixture('v1.0.0', 99, legacy=True)
        new, _ = fixture()
        client = FakeClient([new, old], {old['tag_name']: old_files}, [(old, old_files)])
        plan = self.plan(client)
        self.assertEqual(plan['minimum_version_code'], 99)
        self.assertFalse(client.mutations)

    def test_tag_specific_plan_respects_unmirrored_legacy_source_floor(self):
        old, old_files = fixture('v1.0.0', 99, legacy=True)
        new, _ = fixture()
        client = FakeClient([new, old], {old['tag_name']: old_files})
        self.assertEqual(self.plan(client, tag='v1.0.1')['minimum_version_code'], 99)
        self.assertFalse(client.mutations)

    def test_complete_matching_target_is_idempotent(self):
        source, files = fixture()
        client = FakeClient([source], targets=[(source, files)])
        self.assertEqual(self.plan(client), {'has_pending': False})
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_complete_draft_resumes_without_rebuilding_or_uploading(self):
        source, files = fixture()
        client = FakeClient([source], targets=[(dict(source, draft=True), files)])
        self.assertEqual(self.plan(client), {'has_pending': False})
        self.assertEqual([method for method, _ in client.mutations], ['PATCH'])
        self.assertFalse(client.uploads)
        self.assertFalse(client.targets[0]['draft'])

    def test_partial_target_assets_are_preserved_and_not_rebuilt(self):
        source, files = fixture()
        client = FakeClient([source], targets=[(dict(source, draft=True),
                                               {next(iter(files)): next(iter(files.values()))})])
        with self.assertRaisesRegex(syncer.SyncError, 'incomplete'):
            self.plan(client)
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_empty_draft_can_be_built_but_empty_public_target_is_rejected(self):
        source, _ = fixture()
        client = FakeClient([source], targets=[(dict(source, draft=True), {})])
        self.assertTrue(self.plan(client)['has_pending'])
        client.targets[0]['draft'] = False
        with self.assertRaisesRegex(syncer.SyncError, 'incomplete'):
            self.plan(client)

    def test_tag_mismatch_stops_before_writes(self):
        source, _ = fixture()
        client = FakeClient([source])
        client.target_refs[source['tag_name']] = OLD_COMMIT
        with self.assertRaisesRegex(syncer.SyncError, 'mirror is incomplete'):
            self.plan(client)
        self.assertFalse(client.mutations)

    def test_non_main_ancestor_is_rejected(self):
        source, _ = fixture()
        self.git.return_value = subprocess.CompletedProcess([], 1)
        self.git.side_effect = None
        with self.assertRaisesRegex(syncer.SyncError, 'ancestor'):
            self.plan(FakeClient([source]))

    def test_release_notes_source_host_is_rejected_without_writes(self):
        source, _ = fixture()
        source['body'] = ORIGIN + '/private'
        client = FakeClient([source])
        with self.assertRaisesRegex(syncer.SyncError, 'source host'):
            self.plan(client)
        self.assertFalse(client.mutations)

    def test_tagged_source_tree_is_scanned_before_build(self):
        source, _ = fixture()
        self.git.side_effect = lambda args, **kw: subprocess.CompletedProcess(args, 0,
            stdout=archive_bytes({'README.md': ORIGIN.encode()}) if 'archive' in args else b'')
        with self.assertRaisesRegex(syncer.SyncError, 'source host'):
            self.plan(FakeClient([source]))

    def test_validation_only_requires_tag_and_makes_no_writes_for_legacy(self):
        source, files = fixture('v1.0.0', 99, legacy=True)
        client = FakeClient([source], {source['tag_name']: files}, [(source, files)])
        with self.assertRaisesRegex(syncer.SyncError, 'requires a tag'):
            self.plan(client, validate_only=True)
        plan = self.plan(client, tag='v1.0.0', validate_only=True)
        self.assertTrue(plan['validate_only'])
        self.assertEqual(plan['minimum_version_code'], 0)
        self.publish(client, plan, files)
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_missing_or_draft_source_cannot_be_planned(self):
        source, _ = fixture()
        source['draft'] = True
        with self.assertRaisesRegex(syncer.SyncError, 'not found'):
            self.plan(FakeClient([source]), tag='v1.0.1')

    def test_publish_uploads_four_assets_then_exposes_release(self):
        source, files = fixture()
        client = FakeClient([source])
        plan = self.plan(client)
        self.publish(client, plan, files)
        self.assertEqual(set(client.uploads), set(files))
        self.assertEqual([method for method, _ in client.mutations], ['POST', 'PATCH'])
        self.assertFalse(client.targets[0]['draft'])

    def test_moved_source_or_target_tag_is_rejected_at_publish(self):
        source, files = fixture()
        for remote in ['source_refs', 'target_refs']:
            with self.subTest(remote=remote):
                client = FakeClient([source])
                plan = self.plan(client)
                getattr(client, remote)[source['tag_name']] = OLD_COMMIT
                with self.assertRaises(syncer.SyncError):
                    self.publish(client, plan, files)
                self.assertFalse(client.mutations)

    def test_source_unpublished_or_gained_attachments_stops_publish(self):
        source, files = fixture()
        for field, value in [('draft', True), ('assets', [{'name': 'partial.apk'}])]:
            with self.subTest(field=field):
                client = FakeClient([source])
                plan = self.plan(client)
                client.sources[0][field] = value
                with self.assertRaises(syncer.SyncError):
                    self.publish(client, plan, files)
                self.assertFalse(client.mutations)

    def test_version_code_must_exceed_floor_including_newly_published_versions(self):
        source, files = fixture()
        client = FakeClient([source])
        plan = self.plan(client)
        other, other_files = fixture('v1.0.2', 101)
        client.target_refs[other['tag_name']] = COMMIT
        client.add_target(other, other_files)
        with self.assertRaisesRegex(syncer.SyncError, 'versionCode'):
            self.publish(client, plan, files)
        self.assertFalse(client.mutations)

    def test_wrong_certificate_declaration_or_commit_never_publishes(self):
        source, files = fixture()
        for field, value in [('signerCertificateSha256', '0' * 64), ('sourceCommit', OLD_COMMIT)]:
            with self.subTest(field=field):
                client = FakeClient([source])
                plan = self.plan(client)
                altered = dict(files)
                manifest = json.loads(altered['artifact-manifest.json'])
                manifest[field] = value
                altered['artifact-manifest.json'] = json.dumps(manifest).encode()
                altered['SHA256SUMS.txt'] = ''.join(f'{syncer.sha256(data)}  {name}\n'
                    for name, data in altered.items() if name != 'SHA256SUMS.txt').encode()
                with self.assertRaisesRegex(syncer.SyncError, 'mismatch'):
                    self.publish(client, plan, altered)
                self.assertFalse(client.mutations)

    def test_same_name_different_content_is_never_deleted_or_replaced(self):
        source, files = fixture()
        client = FakeClient([source])
        plan = self.plan(client)
        target = client.add_target(dict(source, draft=True), files)
        target['assets'][0]['digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(syncer.SyncError, 'refusing replacement'):
            self.publish(client, plan, files)
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_forged_source_zip_with_same_commit_comment_cannot_be_published(self):
        source, files = fixture()
        client = FakeClient([source])
        plan = self.plan(client)
        source_name = syncer.names_for(source['tag_name'])[1]
        files[source_name] = source_archive_fixture(source['tag_name'], COMMIT,
                                                   content=b'tampered source with matching commit comment')
        manifest = json.loads(files['artifact-manifest.json'])
        manifest['sourceArchive']['sha256'] = syncer.sha256(files[source_name])
        files['artifact-manifest.json'] = json.dumps(manifest).encode()
        files['SHA256SUMS.txt'] = ''.join(f'{syncer.sha256(data)}  {name}\n'
            for name, data in files.items() if name != 'SHA256SUMS.txt').encode()
        with self.assertRaisesRegex(syncer.SyncError, 'trusted release commit'):
            self.publish(client, plan, files)
        self.assertFalse(client.mutations)
        self.assertFalse(client.uploads)

    def test_source_identity_regeneration_uses_exact_prefix_and_trusted_repository(self):
        source, files = fixture()
        client = FakeClient([source])
        plan = self.plan(client)
        self.publish(client, plan, files)
        calls = [call.args[0] for call in self.git.call_args_list
                 if '--prefix=OriginOS-FCM-Guardian-1.0.1/' in call.args[0]]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0], ['git', '-C', str(self.repo), '-c', 'core.autocrlf=false',
                                   '-c', 'core.eol=lf', 'archive', '--format=zip',
                                   '--prefix=OriginOS-FCM-Guardian-1.0.1/', COMMIT])

    def test_argument_errors_never_echo_private_urls_or_local_paths(self):
        with patch('sys.stderr', new_callable=io.StringIO) as output:
            result = automation.cli(['plan', '--repository', '/Users/private/source',
                                     '--output', '/Users/private/plan.json', '--bad-url', ORIGIN])
        self.assertEqual(result, 1)
        self.assertIn('Invalid release automation arguments', output.getvalue())
        self.assertNotIn(ORIGIN, output.getvalue())
        self.assertNotIn('/Users/private', output.getvalue())

    def test_read_bundle_rejects_extra_files_and_symlinks(self):
        source, files = fixture()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, value in files.items():
                (directory / name).write_bytes(value)
            (directory / 'private.key').write_text('not for release')
            with self.assertRaisesRegex(syncer.SyncError, 'exactly'):
                automation.read_bundle(directory, source['tag_name'])
            (directory / 'private.key').unlink()
            name = next(iter(files))
            (directory / name).unlink()
            (directory / name).symlink_to(directory / 'artifact-manifest.json')
            with self.assertRaisesRegex(syncer.SyncError, 'Invalid local'):
                automation.read_bundle(directory, source['tag_name'])

    def test_plan_output_contains_only_safe_metadata_and_lowercase_booleans(self):
        source, _ = fixture()
        plan = self.plan(FakeClient([source]))
        with tempfile.TemporaryDirectory() as temporary:
            path, output = Path(temporary) / 'plan.json', Path(temporary) / 'output'
            automation.write_plan(path, plan, output)
            self.assertEqual(json.loads(path.read_text()), plan)
            self.assertIn('has_pending=true\n', output.read_text())
            self.assertIn('validate_only=false\n', output.read_text())
            self.assertNotIn('private-origin', path.read_text() + output.read_text())

    def test_unexpected_errors_never_expose_remote_values_or_local_paths(self):
        for error in [ValueError(ORIGIN), OSError('/Users/private/local-key'), KeyError('private-token')]:
            with self.subTest(error=type(error).__name__), patch.object(automation, 'main', side_effect=error), \
                    patch('sys.stderr', new_callable=io.StringIO) as output:
                self.assertEqual(automation.cli([]), 1)
                self.assertNotIn('private', output.getvalue())
                self.assertIn('invalid response', output.getvalue())


if __name__ == '__main__':
    unittest.main()
