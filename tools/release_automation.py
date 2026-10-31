#!/usr/bin/env python3
"""Plan one published source release, or publish an independently signed bundle.

Only trusted workflow code calls this tool. It never executes tagged build code,
signs APKs, moves Git refs, replaces attachments, or deletes releases.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

import sync_gitea_releases as syncer


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise syncer.SyncError('Invalid release automation arguments')


def release_kind(release):
    """A release either preserves all approved original assets or requests a build."""
    syncer.require(isinstance(release, dict), 'Invalid release data')
    expected = set(syncer.names_for(release.get('tag_name')))
    syncer.require(type(release.get('draft')) is bool and not release['draft'],
                   'Only published source releases are supported')
    syncer.require(isinstance(release.get('name'), str) and isinstance(release.get('body'), str)
                   and type(release.get('prerelease')) is bool, 'Invalid source release metadata')
    assets = release.get('assets')
    syncer.require(isinstance(assets, list) and all(isinstance(a, dict) for a in assets),
                   'Invalid source attachment list')
    if not assets:
        return 'build'
    syncer.require(len(assets) == 4 and {a.get('name') for a in assets} == expected,
                   'Source release must have no attachments or exactly the four approved attachments')
    return 'legacy'


def find_target(releases, tag):
    matches = [r for r in releases if r.get('tag_name') == tag]
    syncer.require(len(matches) <= 1, 'Multiple GitHub releases for one tag; resolve manually')
    return matches[0] if matches else None


def trusted_commit(client, release, repository, expected=None):
    tag = release['tag_name']
    commit = syncer.tag_commit(client, tag, True)
    syncer.require(expected is None or commit == expected, 'Source tag moved after planning')
    syncer.require(syncer.tag_commit(client, tag, False) == commit,
                   'GitHub tag commit differs or mirror is incomplete')
    result = subprocess.run(['git', '-C', str(repository), 'merge-base', '--is-ancestor', commit, 'HEAD'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    syncer.require(result.returncode == 0, 'Release commit must be an ancestor of trusted main')
    return commit


def inspect_source_tree(client, release, commit, repository):
    result = subprocess.run(['git', '-C', str(repository), '-c', 'core.autocrlf=false',
                             '-c', 'core.eol=lf', 'archive', '--format=zip', commit],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    syncer.require(result.returncode == 0 and len(result.stdout) <= syncer.MAX_ASSET,
                   'Source archive could not be inspected')
    syncer.check_public_content(syncer.Prepared(release, {'source.zip': result.stdout}, commit),
                                client.source_url)


def verify_source_archive_identity(repository, tag, commit, data):
    """Regenerate source from trusted Git objects, independent of build reports."""
    result = subprocess.run(['git', '-C', str(repository), '-c', 'core.autocrlf=false',
                             '-c', 'core.eol=lf', 'archive', '--format=zip',
                             '--prefix=OriginOS-FCM-Guardian-' + tag[1:] + '/', commit],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    syncer.require(result.returncode == 0 and 0 < len(result.stdout) <= syncer.MAX_ASSET,
                   'Trusted source archive could not be generated')
    syncer.require(data == result.stdout, 'Source archive differs from the trusted release commit')


def target_bundle(client, target, source, commit):
    """Validate existing bytes before treating a target as complete or resumable."""
    expected = set(syncer.names_for(source['tag_name']))
    assets = target.get('assets')
    syncer.require(isinstance(assets, list) and len(assets) == 4
                   and all(isinstance(a, dict) for a in assets)
                   and {a.get('name') for a in assets} == expected,
                   'Existing GitHub assets are incomplete; preserve them and resolve manually')
    files = {}
    for asset in assets:
        syncer.require(type(asset.get('size')) is int and 0 < asset['size'] <= syncer.MAX_ASSET,
                       'Invalid GitHub attachment size')
        data = client.target_asset(asset)
        syncer.verify_target_asset(client, asset, asset['name'], data)
        files[asset['name']] = data
    prepared = syncer.validate_source(source, files, commit)
    syncer.check_public_content(prepared, client.source_url)
    return prepared


def version_code(prepared):
    return json.loads(prepared.files['artifact-manifest.json'])['versionCode']


def published_floor(client, releases, excluded_tag=None):
    """Use verified published target manifests, rather than API ordering or names."""
    floor = 0
    for target in releases:
        tag = target.get('tag_name')
        if target.get('draft') or tag == excluded_tag:
            continue
        syncer.names_for(tag)
        commit = syncer.tag_commit(client, tag, False)
        metadata = {key: target.get(key) for key in ('tag_name', 'name', 'body', 'prerelease')}
        metadata['draft'] = False
        floor = max(floor, version_code(target_bundle(client, target, metadata, commit)))
    return floor


def plan_release(client, repository, tag=None, validate_only=False):
    syncer.require(not validate_only or tag, 'Validation-only planning requires a tag')
    if tag:
        syncer.names_for(tag)
    sources = [r for r in syncer.list_pages(client, True) if not r.get('draft')]
    selected = [r for r in sources if not tag or r.get('tag_name') == tag]
    syncer.require(selected or not tag, 'Requested published source release not found')
    syncer.require(len({r.get('tag_name') for r in sources}) == len(sources),
                   'Duplicate published source release tags')
    targets = list(syncer.list_pages(client, False))
    if validate_only:
        syncer.require(len(selected) == 1, 'Requested release is ambiguous')
        release = selected[0]
        release_kind(release)
        syncer.check_public_content(syncer.Prepared(release, {}, ''), client.source_url)
        commit = trusted_commit(client, release, repository)
        inspect_source_tree(client, release, commit, repository)
        return {'has_pending': True, 'tag': tag, 'commit': commit,
                'minimum_version_code': 0, 'validate_only': True}

    syncer.require(client.token, 'Release planning requires GITHUB_TOKEN')
    floor = published_floor(client, targets)
    for release in sources:
        kind = release_kind(release)
        if release not in selected and kind == 'legacy':
            # A tag-specific run must still respect older source releases that
            # have not yet been copied to a freshly created target repository.
            prepared = syncer.prepare(client, release)
            syncer.check_public_content(prepared, client.source_url)
            floor = max(floor, version_code(prepared))
    candidates = []
    # The source API normally returns newest first; timestamps make oldest-first
    # selection explicit without relying on lexicographical version ordering.
    ordered = sorted(selected, key=lambda r: (str(r.get('created_at') or r.get('published_at') or ''),
                                             tuple(int(n) for n in syncer.VERSION.fullmatch(r['tag_name']).groups())))
    for release in ordered:
        kind = release_kind(release)
        commit = trusted_commit(client, release, repository)
        syncer.check_public_content(syncer.Prepared(release, {}, commit), client.source_url)
        if kind == 'legacy':
            prepared = syncer.prepare(client, release)
            syncer.require(prepared.commit == commit, 'Source tag moved during planning')
            syncer.check_public_content(prepared, client.source_url)
            floor = max(floor, version_code(prepared))
            syncer.sync(client, prepared, targets, publish=True)
            continue
        existing = find_target(targets, release['tag_name'])
        if existing and existing.get('assets'):
            prepared = target_bundle(client, existing, release, commit)
            floor = max(floor, version_code(prepared))
            # A complete draft can be resumed without building different bytes.
            syncer.sync(client, prepared, targets, publish=True)
            continue
        syncer.require(not existing or existing.get('draft') is True,
                       'Published GitHub release has no approved assets; resolve manually')
        inspect_source_tree(client, release, commit, repository)
        candidates.append((release, commit))
    if not candidates:
        return {'has_pending': False}
    release, commit = candidates[0]
    return {'has_pending': True, 'tag': release['tag_name'], 'commit': commit,
            'minimum_version_code': floor, 'validate_only': False}


def validate_plan(plan):
    syncer.require(isinstance(plan, dict) and plan.get('has_pending') is True, 'No planned release to publish')
    syncer.names_for(plan.get('tag'))
    syncer.require(isinstance(plan.get('commit'), str) and syncer.SHA.fullmatch(plan['commit']),
                   'Invalid planned source commit')
    syncer.require(type(plan.get('minimum_version_code')) is int and plan['minimum_version_code'] >= 0
                   and type(plan.get('validate_only')) is bool, 'Invalid planned release policy')


def read_bundle(directory, tag):
    directory = Path(directory)
    names = set(syncer.names_for(tag))
    syncer.require(directory.is_dir() and {p.name for p in directory.iterdir()} == names,
                   'Release directory must contain exactly the four approved files')
    files = {}
    for name in names:
        path = directory / name
        syncer.require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= syncer.MAX_ASSET,
                       'Invalid local release attachment')
        files[name] = path.read_bytes()
    return files


def publish_release(client, plan, directory, repository):
    validate_plan(plan)
    sources = [r for r in syncer.list_pages(client, True)
               if not r.get('draft') and r.get('tag_name') == plan['tag']]
    syncer.require(len(sources) == 1, 'Planned source release is no longer uniquely published')
    release = sources[0]
    kind = release_kind(release)
    syncer.require(plan['validate_only'] or kind == 'build',
                   'Source attachments changed after planning; preserve them and plan again')
    commit = trusted_commit(client, release, repository, expected=plan['commit'])
    files = read_bundle(directory, plan['tag'])
    prepared = syncer.validate_source(release, files, commit)
    verify_source_archive_identity(repository, plan['tag'], commit, files[syncer.names_for(plan['tag'])[1]])
    syncer.check_public_content(prepared, client.source_url)
    if plan['validate_only']:
        print(f"{plan['tag']}: signed bundle validated; no release or attachment was changed")
        return
    syncer.require(client.token, 'Release publication requires GITHUB_TOKEN')
    targets = list(syncer.list_pages(client, False))
    floor = max(plan['minimum_version_code'], published_floor(client, targets, excluded_tag=plan['tag']))
    syncer.require(version_code(prepared) > floor, 'Release versionCode must exceed all previous published versions')
    # Recheck both refs immediately before the first potential write. sync()
    # checks the target ref once more and preserves any different existing bytes.
    trusted_commit(client, release, repository, expected=commit)
    syncer.sync(client, prepared, targets, publish=True)


def write_plan(path, plan, github_output=None):
    Path(path).write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
    if github_output:
        with Path(github_output).open('a', encoding='utf-8') as output:
            for key, value in plan.items():
                output.write(f'{key}={str(value).lower() if type(value) is bool else value}\n')
    print('Release planning complete; pending build=' + str(plan['has_pending']).lower())


def main(argv=None):
    parser = SafeArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    plan_parser = commands.add_parser('plan', help='Preserve original releases and select one new build')
    plan_parser.add_argument('--tag')
    plan_parser.add_argument('--validate-only', action='store_true')
    plan_parser.add_argument('--repository', required=True, type=Path)
    plan_parser.add_argument('--output', required=True, type=Path)
    plan_parser.add_argument('--github-output', type=Path)
    publish_parser = commands.add_parser('publish', help='Verify and publish the exact signed bundle')
    publish_parser.add_argument('--plan', required=True, type=Path)
    publish_parser.add_argument('--directory', required=True, type=Path)
    publish_parser.add_argument('--repository', required=True, type=Path)
    args = parser.parse_args(argv)
    client = syncer.Client(os.environ.get('GITHUB_TOKEN'),
                           source_url=syncer.source_origin(os.environ.get('GITEA_SOURCE_URL')))
    if args.command == 'plan':
        plan = plan_release(client, args.repository, args.tag, args.validate_only)
        write_plan(args.output, plan, args.github_output)
    else:
        syncer.require(args.plan.is_file() and args.plan.stat().st_size <= 16384, 'Invalid plan file')
        publish_release(client, json.loads(args.plan.read_text(encoding='utf-8')),
                        args.directory, args.repository)
    return 0


def cli(argv=None):
    try:
        return main(argv)
    except syncer.SyncError as error:
        print(f'Release automation stopped: {error}', file=sys.stderr)
    except Exception:
        # Never print URLs, remote JSON values, credentials or private local paths.
        print('Release automation stopped: invalid response, local input or release data', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(cli())
