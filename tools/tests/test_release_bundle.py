"""Offline release-boundary checks. No real SDK signing key or network is used."""
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
with patch.object(sys, 'path', [str(TOOLS)] + sys.path):
    SPEC = importlib.util.spec_from_file_location('release_bundle', TOOLS / 'release_bundle.py')
    bundle = importlib.util.module_from_spec(SPEC)
    sys.modules[SPEC.name] = bundle
    SPEC.loader.exec_module(bundle)


class ReleaseBundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / 'repository'
        self.repository.mkdir()
        self.tag, self.version_code = 'v2.7.3-rc.2', 20703
        self.candidate, self.output = self.root / 'candidate', self.root / 'output'
        self.key_paths, self.calls = [], []
        self.certificates = [('1', bundle.syncer.CERTIFICATE)]
        self.report = None
        self.git('init', '--quiet')
        (self.repository / '.gitignore').write_text('**/build/\n')
        source_assets = ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'licenses/Apache-2.0.txt',
                         'licenses/Shizuku-API-MIT.txt', 'licenses/dependencies/runtime-dependencies.json',
                         'licenses/dependencies/THIRD-PARTY-NOTICES.txt')
        for name in source_assets:
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('Public fixture attribution\n')
        self.git('add', '.')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'Public fixture source')
        self.commit = self.git('rev-parse', 'HEAD').decode().strip()
        self.git('tag', self.tag)
        self.apk = self.repository / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        self.apk.parent.mkdir(parents=True)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            for name in bundle.LICENSE_ASSETS:
                archive.writestr(name, b'Public fixture license')
            archive.writestr('classes.dex', b'Fixture DEX; not executable')
        mapping = self.repository / 'app/build/outputs/mapping/release/mapping.txt'
        mapping.parent.mkdir(parents=True)
        mapping.write_text('Fixture -> a:\n')
        tests = self.repository / 'core/build/test-results/test'
        tests.mkdir(parents=True)
        (tests / 'TEST-fixture.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>')
        lint = self.repository / 'app/build/reports/lint-results-debug.xml'
        lint.parent.mkdir(parents=True)
        lint.write_text('<issues/>')
        self.real_run = bundle.run_tool
        self.tool_patch = patch.object(bundle, 'sdk_tool', side_effect=lambda name: Path('/trusted-sdk') / name)
        self.tool_patch.start()
        self.addCleanup(self.tool_patch.stop)
        self.run_patch = patch.object(bundle, 'run_tool', side_effect=self.fake_tool)
        self.run_patch.start()
        self.addCleanup(self.run_patch.stop)
        self.secrets = {'GUARDIAN_RELEASE_KEYSTORE_BASE64': base64.b64encode(b'not-a-real-keystore').decode(),
                        'GUARDIAN_RELEASE_KEY_ALIAS': 'fixture-alias',
                        'GUARDIAN_RELEASE_STORE_PASSWORD': 'fixture-store-password',
                        'GUARDIAN_RELEASE_KEY_PASSWORD': 'fixture-key-password',
                        'RUNNER_TEMP': str(self.root)}
        environment = patch.dict(os.environ, self.secrets)
        environment.start()
        self.addCleanup(environment.stop)

    def git(self, *arguments):
        return subprocess.run(['git', '-C', str(self.repository), *arguments], check=True,
                              capture_output=True).stdout

    def fake_tool(self, arguments, **kwargs):
        args = [str(value) for value in arguments]
        self.calls.append(args)
        if Path(args[0]).name == 'git':
            return self.real_run(arguments, **kwargs)
        if Path(args[0]).name == 'apkanalyzer':
            field = args[2]
            return {'application-id': b'io.github.originosfcmguardian', 'version-name': self.tag[1:].encode(),
                    'version-code': str(self.version_code).encode(), 'min-sdk': b'30',
                    'target-sdk': b'37', 'debuggable': b'false'}[field]
        if Path(args[0]).name == 'apksigner' and args[1] == 'sign':
            key = Path(args[args.index('--ks') + 1])
            self.key_paths.append(key)
            self.assertEqual(stat.S_IMODE(key.parent.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(key.stat().st_mode), 0o600)
            self.assertEqual(key.read_bytes(), b'not-a-real-keystore')
            self.assertIn('env:GUARDIAN_RELEASE_STORE_PASSWORD', args)
            self.assertIn('env:GUARDIAN_RELEASE_KEY_PASSWORD', args)
            self.assertNotIn(self.secrets['GUARDIAN_RELEASE_STORE_PASSWORD'], args)
            self.assertNotIn(self.secrets['GUARDIAN_RELEASE_KEY_PASSWORD'], args)
            self.assertEqual(args[args.index('--v4-signing-enabled') + 1], 'false')
            Path(args[args.index('--out') + 1]).write_bytes(Path(args[-1]).read_bytes() + b'fixture signature')
            return b''
        if Path(args[0]).name == 'apksigner' and args[1] == 'verify':
            return ('Number of signers: ' + str(len(self.certificates)) + '\n' + ''.join(
                'Signer #' + number + ' certificate SHA-256 digest: ' + certificate + '\n'
                for number, certificate in self.certificates)).encode()
        raise AssertionError('Unexpected trusted tool invocation')

    def stage(self):
        self.report = bundle.stage(self.repository, self.tag, self.commit, self.candidate)
        return self.report

    def sign(self, **kwargs):
        return bundle.sign(self.candidate, self.tag, self.commit, self.output, **kwargs)

    def alter_report(self, mutate):
        report = json.loads((self.candidate / 'build-report.json').read_bytes())
        mutate(report)
        (self.candidate / 'build-report.json').write_bytes(bundle.json_bytes(report))

    def test_dynamic_future_version_is_preserved_and_exact_public_bundle_is_generated(self):
        report = self.stage()
        self.assertEqual({path.name for path in self.candidate.iterdir()}, bundle.CANDIDATE_FILES)
        self.assertEqual(report['versionName'], '2.7.3-rc.2')
        self.assertEqual(report['versionCode'], self.version_code)
        self.assertEqual(report['checks']['unitTests']['status'], 'passed')
        manifest = self.sign(minimum_version_code=self.version_code - 1)
        self.assertEqual(set(path.name for path in self.output.iterdir()), set(bundle.syncer.names_for(self.tag)))
        self.assertEqual(manifest['versionName'], '2.7.3-rc.2')
        self.assertEqual(manifest['versionCode'], self.version_code)
        self.assertEqual(manifest['sourceCommit'], self.commit)
        self.assertEqual(manifest['buildReportSha256'], bundle.digest((self.candidate / 'build-report.json').read_bytes()))
        self.assertEqual(manifest['signerCertificateSha256'], bundle.syncer.CERTIFICATE)
        for key in self.key_paths:
            self.assertFalse(key.exists())
            self.assertFalse(key.parent.exists())
        self.assertTrue(all(Path(call[0]).name in ('git', 'apkanalyzer', 'apksigner') for call in self.calls))

    def test_stage_refuses_wrong_checkout_or_wrong_tag(self):
        with self.assertRaisesRegex(bundle.BundleError, 'Checkout or tag'):
            bundle.stage(self.repository, self.tag, 'b' * 40, self.candidate)
        self.git('tag', '-f', self.tag, 'HEAD')
        with self.assertRaisesRegex(bundle.BundleError, 'Invalid release tag'):
            bundle.stage(self.repository, '../private', self.commit, self.candidate)
        self.assertFalse(self.candidate.exists())

    def test_stage_refuses_dirty_tracked_source(self):
        (self.repository / 'NOTICE').write_text('uncommitted change')
        with self.assertRaisesRegex(bundle.BundleError, 'must be clean'):
            self.stage()

    def test_stage_reports_actual_junit_and_lint_counts_without_private_xml_fields(self):
        tests = self.repository / 'core/build/test-results/test'
        tests.mkdir(parents=True, exist_ok=True)
        (tests / 'TEST-fixture.xml').write_text('<testsuite name="private-name" hostname="private-host" tests="2" failures="0" errors="0" skipped="1"><testcase/><testcase><skipped/></testcase><system-out>private text</system-out></testsuite>')
        lint = self.repository / 'app/build/reports/lint-results-debug.xml'
        lint.parent.mkdir(parents=True, exist_ok=True)
        lint.write_text('<issues><issue severity="Warning" message="private-path"/></issues>')
        report = self.stage()
        self.assertEqual(report['checks']['unitTests'], {'status': 'passed', 'suites': 1, 'tests': 2,
                                                        'failures': 0, 'errors': 0, 'skipped': 1})
        self.assertEqual(report['checks']['lintDebug']['warning'], 1)
        self.assertEqual(report['checks']['lintDebug']['status'], 'passed')
        self.assertNotIn('private', json.dumps(report))

    def test_stage_rejects_failing_or_inconsistent_test_reports(self):
        directory = self.repository / 'core/build/test-results/test'
        directory.mkdir(parents=True, exist_ok=True)
        report = directory / 'TEST-fixture.xml'
        for content in ['<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>',
                        '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase/></testsuite>']:
            with self.subTest(content=content):
                report.write_text(content)
                with self.assertRaises(bundle.BundleError):
                    self.stage()
        self.assertFalse(self.candidate.exists())

    def test_missing_or_empty_test_reports_cannot_become_an_official_bundle(self):
        tests = self.repository / 'core/build/test-results/test/TEST-fixture.xml'
        lint = self.repository / 'app/build/reports/lint-results-debug.xml'
        tests.unlink()
        report = self.stage()
        self.assertEqual(report['checks']['unitTests']['status'], 'not-run')
        with self.assertRaisesRegex(bundle.BundleError, 'requires passed'):
            self.sign()
        self.alter_report(lambda value: value['checks']['unitTests'].update(status='passed', suites=1))
        with self.assertRaisesRegex(bundle.BundleError, 'requires passed'):
            self.sign()
        tests.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>')
        lint.unlink()
        report = bundle.check_stats(self.repository)
        self.alter_report(lambda value: value.update(checks=report))
        with self.assertRaisesRegex(bundle.BundleError, 'requires passed'):
            self.sign()
        self.assertFalse(self.key_paths)

    def test_candidate_wrong_tag_and_hash_are_rejected_before_secrets_are_decoded(self):
        self.stage()
        for change in [lambda r: r.update(sourceTag='v9.9.9'),
                       lambda r: r['files']['unsigned.apk'].update(sha256='0' * 64)]:
            original = (self.candidate / 'build-report.json').read_bytes()
            self.alter_report(change)
            with patch.object(bundle, 'temporary_keystore') as keys, self.assertRaises(bundle.BundleError):
                self.sign()
            keys.assert_not_called()
            (self.candidate / 'build-report.json').write_bytes(original)

    def test_changed_unsigned_bytes_are_rejected(self):
        self.stage()
        (self.candidate / 'unsigned.apk').write_bytes(b'tampered')
        with self.assertRaisesRegex(bundle.BundleError, 'hash or size'):
            self.sign()

    def test_candidate_extra_files_and_symlinks_are_rejected(self):
        self.stage()
        extra = self.candidate / 'mapping.txt'
        extra.write_text('private mapping')
        with self.assertRaisesRegex(bundle.BundleError, 'file set'):
            self.sign()
        extra.unlink()
        source = self.candidate / 'source.zip'
        saved = self.root / 'saved-source.zip'
        source.rename(saved)
        source.symlink_to(saved)
        with self.assertRaisesRegex(bundle.BundleError, 'regular artifact'):
            self.sign()

    def test_version_code_must_increase_and_match_actual_apk(self):
        self.stage()
        with self.assertRaisesRegex(bundle.BundleError, 'must increase'):
            self.sign(minimum_version_code=self.version_code)
        self.version_code += 1
        with self.assertRaisesRegex(bundle.BundleError, 'Actual APK metadata'):
            self.sign()

    def test_wrong_actual_manifest_or_missing_attribution_is_rejected(self):
        fake_tool = self.fake_tool
        for field, value in [('application-id', b'other.application'), ('version-name', b'0.0.0'),
                             ('version-code', b'0'), ('min-sdk', b'29'), ('target-sdk', b'36'),
                             ('debuggable', b'true')]:
            def altered(arguments, **kwargs):
                if Path(str(arguments[0])).name == 'apkanalyzer' and arguments[2] == field:
                    return value
                return fake_tool(arguments, **kwargs)
            with self.subTest(field=field), patch.object(bundle, 'run_tool', side_effect=altered), \
                    self.assertRaises(bundle.BundleError):
                bundle.apk_metadata(self.apk, self.tag)
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('classes.dex', b'fixture')
        with self.assertRaisesRegex(bundle.BundleError, 'attribution'):
            bundle.apk_metadata(self.apk, self.tag)

    def test_wrong_or_multiple_signer_certificates_refuse_bundle_and_remove_temporary_key(self):
        self.stage()
        for certificates in [[('1', '0' * 64)], [('1', bundle.syncer.CERTIFICATE), ('2', bundle.syncer.CERTIFICATE)]]:
            with self.subTest(certificates=certificates):
                self.certificates = certificates
                with self.assertRaisesRegex(bundle.BundleError, 'signer differs'):
                    self.sign()
                self.assertFalse(self.output.exists())
                self.assertTrue(all(not path.exists() and not path.parent.exists() for path in self.key_paths))

    def test_signing_tool_failure_removes_temporary_key(self):
        self.stage()
        fake_tool = self.fake_tool
        def fail(arguments, **kwargs):
            if str(arguments[1]) == 'sign':
                key = Path(arguments[arguments.index('--ks') + 1])
                self.key_paths.append(key)
                raise bundle.BundleError('Trusted tool operation failed')
            return fake_tool(arguments, **kwargs)
        with patch.object(bundle, 'run_tool', side_effect=fail), self.assertRaises(bundle.BundleError):
            self.sign()
        self.assertTrue(self.key_paths)
        self.assertTrue(all(not path.exists() and not path.parent.exists() for path in self.key_paths))

    def test_invalid_key_encoding_and_existing_output_are_rejected(self):
        self.stage()
        with patch.dict(os.environ, {'GUARDIAN_RELEASE_KEYSTORE_BASE64': 'not base64'}), \
                self.assertRaisesRegex(bundle.BundleError, 'encoding'):
            self.sign()
        self.output.mkdir()
        (self.output / 'existing.txt').write_text('preserve')
        with self.assertRaisesRegex(bundle.BundleError, 'absent or empty'):
            self.sign()
        self.assertEqual((self.output / 'existing.txt').read_text(), 'preserve')

    def test_source_archive_commit_must_match_and_no_mapping_is_in_candidate(self):
        self.stage()
        data = io.BytesIO((self.candidate / 'source.zip').read_bytes())
        with zipfile.ZipFile(data) as archive:
            self.assertEqual(archive.comment.decode(), self.commit)
            self.assertFalse(any('build/outputs/mapping' in name for name in archive.namelist()))
        with self.assertRaisesRegex(bundle.BundleError, 'commit does not match'):
            bundle.source_archive(data.getvalue(), self.tag, 'b' * 40)

    def test_tool_and_cli_errors_do_not_echo_private_paths_passwords_or_output(self):
        private = 'private-host.example.invalid/private-key/private-password'
        self.run_patch.stop()
        with patch.object(bundle.subprocess, 'run', side_effect=OSError(private)), \
                self.assertRaisesRegex(bundle.BundleError, 'could not complete') as stopped:
            bundle.run_tool(['/trusted-sdk/apksigner'])
        self.assertNotIn(private, str(stopped.exception))
        with patch.object(bundle, 'main', side_effect=ValueError(private)), \
                patch('sys.stderr', new_callable=io.StringIO) as output:
            self.assertEqual(bundle.cli([]), 1)
        self.assertNotIn(private, output.getvalue())
        with patch('sys.stderr', new_callable=io.StringIO) as output:
            self.assertEqual(bundle.cli(['sign', '--private-path=' + private]), 1)
        self.assertNotIn(private, output.getvalue())

    def test_subprocess_receives_password_references_only_for_signer(self):
        self.run_patch.stop()
        completed = subprocess.CompletedProcess([], 0, b'public result', b'private stderr')
        with patch.object(bundle.subprocess, 'run', return_value=completed) as run:
            bundle.run_tool(['/trusted-sdk/apkanalyzer'])
            self.assertTrue(all(name not in run.call_args.kwargs['env'] for name in bundle.SECRET_NAMES))
            bundle.run_tool(['/trusted-sdk/apksigner'], signing=True)
            self.assertNotIn('GUARDIAN_RELEASE_KEYSTORE_BASE64', run.call_args.kwargs['env'])
            self.assertEqual(run.call_args.kwargs['env']['GUARDIAN_RELEASE_STORE_PASSWORD'],
                             self.secrets['GUARDIAN_RELEASE_STORE_PASSWORD'])


if __name__ == '__main__':
    unittest.main()
