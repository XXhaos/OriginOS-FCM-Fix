#!/usr/bin/env python3
"""Stage checked unsigned artifacts, then sign them on a separate trusted runner.

Neither command builds source, runs repository scripts, installs APKs or publishes.
The sign command uses only the current trusted helper and Android SDK tools.
"""
import argparse
import base64
import binascii
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import sync_gitea_releases as syncer

SCHEMA = 'guardian.release-candidate.v1'
CANDIDATE_FILES = {'unsigned.apk', 'source.zip', 'build-report.json'}
LICENSE_ASSETS = ('assets/Apache-2.0.txt', 'assets/Shizuku-API-MIT.txt',
                  'assets/dependencies/runtime-dependencies.json',
                  'assets/dependencies/THIRD-PARTY-NOTICES.txt')
SECRET_NAMES = ('GUARDIAN_RELEASE_KEYSTORE_BASE64', 'GUARDIAN_RELEASE_KEY_ALIAS',
                'GUARDIAN_RELEASE_STORE_PASSWORD', 'GUARDIAN_RELEASE_KEY_PASSWORD')
HEX256 = re.compile(r'[a-f0-9]{64}\Z')
MAX_REPORT = 1024 * 1024


class BundleError(Exception):
    """Only static, public error messages may be placed in this exception."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise BundleError('Invalid release bundle arguments')


def require(condition, message):
    if not condition:
        raise BundleError(message)


def identity(tag, commit):
    require(isinstance(tag, str) and syncer.VERSION.fullmatch(tag), 'Invalid release tag')
    require(isinstance(commit, str) and syncer.SHA.fullmatch(commit), 'Invalid release commit')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + '\n').encode('ascii')


def read_file(path, maximum=syncer.MAX_ASSET):
    require(path.is_file() and not path.is_symlink(), 'Required regular artifact is missing')
    require(0 < path.stat().st_size <= maximum, 'Artifact exceeds permitted size')
    with path.open('rb') as source:
        data = source.read(maximum + 1)
    require(0 < len(data) <= maximum, 'Artifact exceeds permitted size')
    return data


def run_tool(arguments, *, signing=False, maximum=syncer.MAX_ASSET):
    # The build/tag checkout is never used as a process cwd, PATH or executable source.
    environment = os.environ.copy()
    for name in SECRET_NAMES:
        if not signing or name == 'GUARDIAN_RELEASE_KEYSTORE_BASE64':
            environment.pop(name, None)
    try:
        result = subprocess.run([str(value) for value in arguments], capture_output=True,
                                timeout=180, check=False, env=environment)
    except Exception:
        raise BundleError('Trusted tool could not complete') from None
    require(result.returncode == 0, 'Trusted tool operation failed')
    require(len(result.stdout) <= maximum, 'Trusted tool output exceeds permitted size')
    return result.stdout


def sdk_tool(name):
    root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(bool(root), 'Android SDK environment is required')
    suffix = '.bat' if os.name == 'nt' else ''
    relative = ('cmdline-tools/latest/bin/' + name + suffix) if name == 'apkanalyzer' else (
        'build-tools/36.0.0/' + name + suffix)
    tool = (Path(root) / relative).resolve()
    require(tool.is_file(), 'Required Android SDK tool is missing')
    return tool


def apk_metadata(apk, tag):
    analyzer = sdk_tool('apkanalyzer')
    values = {}
    for field in ('application-id', 'version-name', 'version-code', 'min-sdk', 'target-sdk', 'debuggable'):
        values[field] = run_tool([analyzer, 'manifest', field, apk], maximum=4096).decode('utf-8').strip()
    require(values['application-id'] == 'io.github.originosfcmguardian', 'Unexpected APK application ID')
    require(values['version-name'] == tag[1:], 'APK version does not match release tag')
    require(re.fullmatch(r'[1-9][0-9]*', values['version-code']) is not None, 'Invalid APK version code')
    require(values['min-sdk'] == '30' and values['target-sdk'] == '37', 'Unexpected APK SDK range')
    require(values['debuggable'] == 'false', 'Release APK must not be debuggable')
    require(int(values['version-code']) <= 2_100_000_000, 'Invalid APK version code')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate APK archive entries')
        for name in LICENSE_ASSETS:
            require(name in names and archive.getinfo(name).file_size > 0, 'Required APK attribution is missing')
    return {'applicationId': values['application-id'], 'versionName': values['version-name'],
            'versionCode': int(values['version-code']), 'minSdk': 30, 'targetSdk': 37, 'debuggable': False}


def source_archive(data, tag, commit):
    prefix = 'OriginOS-FCM-Guardian-' + tag[1:] + '/'
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        require(archive.comment == commit.encode('ascii'), 'Source archive commit does not match release')
        require(len(entries) <= 20000 and sum(item.file_size for item in entries) <= syncer.MAX_ASSET,
                'Source archive exceeds permitted size')
        names = [item.filename for item in entries]
        require(len(names) == len(set(names)), 'Duplicate source archive entries')
        for item in entries:
            path = PurePosixPath(item.filename)
            require(item.filename.startswith(prefix) and not path.is_absolute() and '..' not in path.parts
                    and '\\' not in item.filename, 'Unsafe source archive entry')
            require(not stat.S_ISLNK(item.external_attr >> 16), 'Source archive must not contain symbolic links')
        for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'licenses/Apache-2.0.txt',
                     'licenses/Shizuku-API-MIT.txt', 'licenses/dependencies/runtime-dependencies.json',
                     'licenses/dependencies/THIRD-PARTY-NOTICES.txt'):
            require(prefix + name in names and archive.getinfo(prefix + name).file_size > 0,
                    'Required source attribution is missing')


def check_stats(repository):
    tests = {'status': 'not-run', 'suites': 0, 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for directory in (repository / 'core/build/test-results/test', repository / 'app/build/test-results/testDebugUnitTest'):
        for path in sorted(directory.glob('TEST-*.xml')):
            root = ET.fromstring(read_file(path, MAX_REPORT))
            require(root.tag == 'testsuite', 'Invalid JUnit report')
            cases = root.findall('testcase')
            require(int(root.attrib['tests']) == len(cases), 'Inconsistent JUnit report')
            counts = {'tests': len(cases), 'failures': sum(case.find('failure') is not None for case in cases),
                      'errors': sum(case.find('error') is not None for case in cases),
                      'skipped': sum(case.find('skipped') is not None for case in cases)}
            require(all(int(root.attrib.get(key, '0')) == value for key, value in counts.items()),
                    'Inconsistent JUnit report')
            tests['suites'] += 1
            for key, value in counts.items():
                tests[key] += value
    if tests['suites']:
        require(tests['failures'] == 0 and tests['errors'] == 0, 'Unit test reports contain failures')
        tests['status'] = 'passed'
    lint = {'status': 'not-run', 'fatal': 0, 'error': 0, 'warning': 0, 'information': 0, 'ignore': 0}
    path = repository / 'app/build/reports/lint-results-debug.xml'
    if path.is_file():
        root = ET.fromstring(read_file(path, 8 * MAX_REPORT))
        require(root.tag == 'issues', 'Invalid lint report')
        for issue in root.findall('issue'):
            severity = issue.attrib['severity'].lower()
            require(severity in lint and severity != 'status', 'Unexpected lint severity')
            lint[severity] += 1
        require(lint['fatal'] == 0 and lint['error'] == 0, 'Lint report contains errors')
        lint['status'] = 'passed'
    return {'unitTests': tests, 'lintDebug': lint, 'deviceTests': 'not-run'}


def validate_checks(checks):
    require(isinstance(checks, dict) and set(checks) == {'unitTests', 'lintDebug', 'deviceTests'}
            and checks['deviceTests'] == 'not-run', 'Invalid candidate check declarations')
    for name, fields in (('unitTests', {'suites', 'tests', 'failures', 'errors', 'skipped'}),
                         ('lintDebug', {'fatal', 'error', 'warning', 'information', 'ignore'})):
        value = checks[name]
        require(isinstance(value, dict) and set(value) == fields | {'status'}
                and value['status'] in ('passed', 'not-run')
                and all(type(value[field]) is int and 0 <= value[field] <= 10_000_000 for field in fields),
                'Invalid candidate check counts')
        require(value['status'] != 'not-run' or all(value[field] == 0 for field in fields),
                'Inconsistent candidate check declarations')
    tests, lint = checks['unitTests'], checks['lintDebug']
    require(tests['failures'] == 0 and tests['errors'] == 0 and lint['fatal'] == 0 and lint['error'] == 0,
            'Candidate checks contain failures')
    require(tests['skipped'] <= tests['tests'] and (tests['status'] != 'passed' or tests['suites'] > 0),
            'Inconsistent candidate unit test counts')


def publish_directory(output, files):
    require(not output.is_symlink() and (not output.exists() or (output.is_dir() and not any(output.iterdir()))),
            'Output directory must be absent or empty')
    output.mkdir(parents=True, exist_ok=True)
    try:
        for name, data in files.items():
            with (output / name).open('xb') as destination:
                destination.write(data)
    except Exception:
        for name in files:
            with contextlib.suppress(OSError):
                (output / name).unlink()
        raise


def stage(repository, tag, commit, output):
    identity(tag, commit)
    repository, output = Path(repository).resolve(), Path(output)
    git = shutil.which('git')
    require(git is not None, 'Git is required')
    actual = run_tool([git, '-C', repository, 'rev-parse', 'HEAD'], maximum=4096).decode().strip()
    tagged = run_tool([git, '-C', repository, 'rev-parse', 'refs/tags/' + tag + '^{commit}'], maximum=4096).decode().strip()
    require(actual == commit and tagged == commit, 'Checkout or tag differs from requested release commit')
    require(not run_tool([git, '-C', repository, 'status', '--porcelain', '--untracked-files=no'], maximum=4096),
            'Tracked source checkout must be clean')
    apk = repository / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    unsigned = read_file(apk)
    metadata = apk_metadata(apk, tag)
    mapping = repository / 'app/build/outputs/mapping/release/mapping.txt'
    require(mapping.is_file() and not mapping.is_symlink() and mapping.stat().st_size > 0,
            'Minified release mapping is missing')
    source = run_tool([git, '-C', repository, '-c', 'core.autocrlf=false', '-c', 'core.eol=lf',
                       'archive', '--format=zip', '--prefix=OriginOS-FCM-Guardian-' + tag[1:] + '/', commit])
    source_archive(source, tag, commit)
    report = {'schema': SCHEMA, 'sourceTag': tag, 'sourceCommit': commit, **metadata,
              'variant': 'release', 'minified': True,
              'files': {name: {'bytes': len(data), 'sha256': digest(data)}
                        for name, data in (('unsigned.apk', unsigned), ('source.zip', source))},
              'checks': check_stats(repository)}
    validate_checks(report['checks'])
    publish_directory(output, {'unsigned.apk': unsigned, 'source.zip': source,
                               'build-report.json': json_bytes(report)})
    return report


def candidate_files(directory, tag, commit, minimum_version_code):
    identity(tag, commit)
    require(type(minimum_version_code) is int and minimum_version_code >= 0, 'Invalid minimum version code')
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink() and
            {path.name for path in directory.iterdir()} == CANDIDATE_FILES, 'Unexpected candidate file set')
    data = {name: read_file(directory / name, MAX_REPORT if name.endswith('.json') else syncer.MAX_ASSET)
            for name in CANDIDATE_FILES}
    report = json.loads(data['build-report.json'])
    expected_fields = {'schema', 'sourceTag', 'sourceCommit', 'applicationId', 'versionName', 'versionCode',
                       'minSdk', 'targetSdk', 'debuggable', 'variant', 'minified', 'files', 'checks'}
    require(isinstance(report, dict) and set(report) == expected_fields and report['schema'] == SCHEMA
            and report['sourceTag'] == tag and report['sourceCommit'] == commit,
            'Candidate identity does not match requested release')
    require(type(report['versionCode']) is int and report['versionCode'] > minimum_version_code,
            'Release version code must increase')
    require(report['variant'] == 'release' and report['minified'] is True, 'Candidate is not a minified release')
    require(isinstance(report['files'], dict) and set(report['files']) == {'unsigned.apk', 'source.zip'},
            'Invalid candidate hash report')
    for name in ('unsigned.apk', 'source.zip'):
        entry = report['files'][name]
        require(isinstance(entry, dict) and set(entry) == {'bytes', 'sha256'}
                and type(entry['bytes']) is int and entry['bytes'] == len(data[name])
                and isinstance(entry['sha256'], str) and HEX256.fullmatch(entry['sha256'])
                and entry['sha256'] == digest(data[name]), 'Candidate hash or size differs')
    validate_checks(report['checks'])
    require(report['checks']['unitTests']['status'] == 'passed'
            and report['checks']['unitTests']['tests'] > 0
            and report['checks']['lintDebug']['status'] == 'passed',
            'Official signing requires passed unit test and lint reports')
    metadata = apk_metadata(directory / 'unsigned.apk', tag)
    require(all(type(report[key]) is type(value) and report[key] == value for key, value in metadata.items()),
            'Actual APK metadata differs from candidate report')
    source_archive(data['source.zip'], tag, commit)
    return data, report


@contextlib.contextmanager
def temporary_keystore():
    require(all(os.environ.get(name) for name in SECRET_NAMES), 'Official signing secrets are required')
    try:
        encoded = os.environ['GUARDIAN_RELEASE_KEYSTORE_BASE64']
        require(len(encoded) <= 16 * MAX_REPORT, 'Signing keystore exceeds permitted size')
        key = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise BundleError('Invalid signing keystore encoding') from None
    require(0 < len(key) <= 8 * MAX_REPORT, 'Invalid signing keystore size')
    with tempfile.TemporaryDirectory(prefix='guardian-sign-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        os.chmod(directory, 0o700)
        path = Path(directory) / 'release.keystore'
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as destination:
            destination.write(key)
        key = b''
        yield path


def sign(candidate, tag, commit, output, minimum_version_code=0):
    data, report = candidate_files(candidate, tag, commit, minimum_version_code)
    signer = sdk_tool('apksigner')
    apk_name, source_name, manifest_name, checksum_name = syncer.names_for(tag)
    # No paths from the candidate report are used for tools or output; all names are fixed here.
    with tempfile.TemporaryDirectory(prefix='guardian-apk-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        unsigned, signed = Path(directory) / 'unsigned.apk', Path(directory) / 'signed.apk'
        unsigned.write_bytes(data['unsigned.apk'])
        with temporary_keystore() as key:
            run_tool([signer, 'sign', '--ks', key, '--ks-key-alias', os.environ['GUARDIAN_RELEASE_KEY_ALIAS'],
                      '--ks-pass', 'env:GUARDIAN_RELEASE_STORE_PASSWORD',
                      '--key-pass', 'env:GUARDIAN_RELEASE_KEY_PASSWORD', '--v4-signing-enabled', 'false',
                      '--out', signed, unsigned], signing=True, maximum=4096)
            verification = run_tool([signer, 'verify', '--verbose', '--print-certs', signed], maximum=64 * 1024).decode('utf-8')
            certificates = re.findall(r'^Signer #(\d+) certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$',
                                      verification, re.MULTILINE)
            require(len(certificates) == 1 and certificates[0][0] == '1'
                    and certificates[0][1].lower() == syncer.CERTIFICATE,
                    'APK signer differs from the original release certificate')
            require(re.findall(r'^Number of signers: (\d+)\s*$', verification, re.MULTILINE) == ['1'],
                    'Expected exactly one release APK signer')
            metadata = apk_metadata(signed, tag)
            require(all(type(report[key]) is type(value) and report[key] == value for key, value in metadata.items()),
                    'Signed APK metadata differs from candidate report')
            signed_data = read_file(signed)
        manifest = {**metadata, 'variant': 'release', 'minified': True, 'file': apk_name,
                    'bytes': len(signed_data), 'sha256': digest(signed_data),
                    'signerCertificateSha256': syncer.CERTIFICATE, 'sourceTag': tag, 'sourceCommit': commit,
                    'sourceArchive': {'file': source_name, 'bytes': len(data['source.zip']),
                                      'sha256': digest(data['source.zip'])},
                    'buildReportSha256': digest(data['build-report.json']), 'checks': report['checks']}
        files = {apk_name: signed_data, source_name: data['source.zip'], manifest_name: json_bytes(manifest)}
        files[checksum_name] = ''.join(digest(value) + '  ' + name + '\n' for name, value in files.items()).encode('ascii')
        syncer.validate_source({'tag_name': tag, 'draft': False, 'prerelease': '-' in tag,
                                'name': 'OriginOS FCM Guardian ' + tag[1:], 'body': ''}, files, commit)
        publish_directory(Path(output), files)
        return manifest


def main(argv=None):
    parser = SafeArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for command in ('stage', 'sign'):
        child = commands.add_parser(command)
        child.add_argument('--tag', required=True)
        child.add_argument('--commit', required=True)
        child.add_argument('--output', required=True, type=Path)
        child.add_argument('--repository' if command == 'stage' else '--candidate', required=True, type=Path)
        if command == 'sign':
            child.add_argument('--minimum-version-code', type=int, default=0)
    args = parser.parse_args(argv)
    if args.command == 'stage':
        stage(args.repository, args.tag, args.commit, args.output)
    else:
        sign(args.candidate, args.tag, args.commit, args.output, args.minimum_version_code)
    print(args.tag + ': ' + ('candidate staged' if args.command == 'stage' else 'official bundle verified'))
    return 0


def cli(argv=None):
    try:
        return main(argv)
    except BundleError as error:
        print('Release bundle stopped: ' + str(error), file=sys.stderr)
    except Exception:
        print('Release bundle stopped: artifact or trusted tool validation failed', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(cli())
