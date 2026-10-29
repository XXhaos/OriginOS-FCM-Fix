#!/usr/bin/env python3
"""Copy verified public Gitea releases to GitHub; default is read-only.

No signing, builds, Git ref changes, asset replacement or release deletion.
Gitea is read anonymously. Only --publish permits GitHub mutations.
"""
import argparse
import hashlib
import json
import os
from pathlib import PurePosixPath
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

REPOSITORY = 'XXhaos/OriginOS-FCM-Fix'
SOURCE = 'https://source.example.invalid'
SOURCE_API = f'{SOURCE}/api/v1/repos/{REPOSITORY}'
TARGET_API = f'https://api.github.com/repos/{REPOSITORY}'
UPLOAD_API = f'https://uploads.github.com/repos/{REPOSITORY}'
CERTIFICATE = 'd662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d'
VERSION = re.compile(r'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?\Z')
SHA = re.compile(r'[a-f0-9]{40}\Z')
MAX_ASSET = 100 * 1024 * 1024


class SyncError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SyncError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def names_for(tag):
    require(isinstance(tag, str) and VERSION.fullmatch(tag), 'Unsupported release tag')
    stem = f'OriginOS-FCM-Guardian-{tag[1:]}'
    return (stem + '.apk', stem + '-source.zip', 'artifact-manifest.json', 'SHA256SUMS.txt')


def safe_https(url, hosts):
    parts = urllib.parse.urlsplit(url)
    require(parts.scheme == 'https' and parts.hostname in hosts and parts.port in (None, 443)
            and not parts.username and not parts.password, 'Unexpected download destination')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts):
        self.hosts = hosts

    def redirect_request(self, request, fp, code, message, headers, new_url):
        safe_https(new_url, self.hosts)
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected:
            # Never forward GitHub authorization to storage URLs or another service.
            redirected.remove_header('Authorization')
        return redirected


class Client:
    def __init__(self, token=None):
        self.token = token

    def request(self, url, *, method='GET', body=None, authenticated=False,
                binary=False, limit=4 * 1024 * 1024):
        initial_host = urllib.parse.urlsplit(url).hostname
        hosts = {initial_host}
        if binary and authenticated:
            hosts |= {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}
        safe_https(url, hosts)
        headers = {'User-Agent': 'OriginOS-FCM-Guardian-release-sync',
                   'Accept': 'application/octet-stream' if binary else 'application/json'}
        if authenticated:
            require(url.startswith(TARGET_API + '/') or url.startswith(UPLOAD_API + '/'),
                    'GitHub authorization destination rejected')
            if self.token:
                headers['Authorization'] = 'Bearer ' + self.token
            headers['X-GitHub-Api-Version'] = '2022-11-28'
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        elif body is not None:
            headers['Content-Type'] = 'application/octet-stream'
        opener = urllib.request.build_opener(SafeRedirect(hosts))
        try:
            with opener.open(urllib.request.Request(url, data=body, headers=headers, method=method), timeout=60) as response:
                length = response.headers.get('Content-Length')
                require(not length or int(length) <= limit, 'Response exceeds size limit')
                data = response.read(limit + 1)
                require(len(data) <= limit, 'Response exceeds size limit')
                return data if binary else json.loads(data)
        except urllib.error.HTTPError as error:
            # Do not print request headers, token, signed redirect URLs or response bodies.
            raise SyncError(f'{method} {initial_host}: HTTP {error.code}') from None
        except (urllib.error.URLError, TimeoutError) as error:
            raise SyncError(f'{method} {initial_host}: network request failed ({type(error).__name__})') from None

    def source(self, path):
        return self.request(SOURCE_API + path)

    def target(self, path, method='GET', body=None):
        return self.request(TARGET_API + path, method=method, body=body, authenticated=True)

    def source_asset(self, release, asset):
        tag, name = release['tag_name'], asset['name']
        expected = f'{SOURCE}/{REPOSITORY}/releases/download/{tag}/{name}'
        require(asset.get('browser_download_url') == expected, 'Unexpected Gitea attachment URL')
        limit = 256 * 1024 if name == 'artifact-manifest.json' else 32 * 1024 if name == 'SHA256SUMS.txt' else MAX_ASSET
        require(type(asset.get('size')) is int and 0 < asset['size'] <= limit, 'Invalid Gitea attachment size')
        data = self.request(expected, binary=True, limit=limit)
        require(len(data) == asset['size'], 'Truncated Gitea attachment')
        return data

    def target_asset(self, asset):
        require(type(asset.get('id')) is int and asset['id'] > 0, 'Invalid GitHub asset ID')
        return self.request(TARGET_API + f"/releases/assets/{asset['id']}", authenticated=True,
                            binary=True, limit=MAX_ASSET)

    def upload(self, release_id, name, data):
        return self.request(UPLOAD_API + f'/releases/{release_id}/assets?name=' + urllib.parse.quote(name),
                            method='POST', body=data, authenticated=True)


def list_pages(client, source):
    for page in range(1, 101):
        items = client.source(f'/releases?limit=100&page={page}') if source else client.target(f'/releases?per_page=100&page={page}')
        require(isinstance(items, list), 'Invalid release list')
        yield from items
        # Gitea administrators may cap the requested page size below 100.
        if not items:
            return
    raise SyncError('Too many release pages; narrow the release set')


def tag_commit(client, tag, source):
    read = client.source if source else client.target
    ref = read('/git/refs/tags/' + urllib.parse.quote(tag, safe='')) if source else read('/git/ref/tags/' + urllib.parse.quote(tag, safe=''))
    if source and isinstance(ref, list):
        exact = [item for item in ref if item.get('ref') == 'refs/tags/' + tag]
        require(len(exact) == 1, 'Missing or ambiguous exact Gitea tag')
        ref = exact[0]
    require(isinstance(ref, dict) and ref.get('ref') == 'refs/tags/' + tag, 'Missing exact tag')
    obj = ref.get('object', {})
    for _ in range(8):
        require(SHA.fullmatch(obj.get('sha', '')), 'Invalid tag object')
        if obj.get('type') == 'commit':
            return obj['sha']
        require(obj.get('type') == 'tag', 'Tag does not resolve to a commit')
        obj = read('/git/tags/' + obj['sha']).get('object', {})
    raise SyncError('Tag nesting exceeds limit')


@dataclass
class Prepared:
    release: dict
    files: dict
    commit: str


def validate_source(release, files, commit):
    tag = release['tag_name']
    apk, source, manifest_name, checksum_name = names_for(tag)
    require(not release.get('draft'), 'Draft Gitea release cannot be published')
    require(set(files) == {apk, source, manifest_name, checksum_name}, 'Unexpected or missing release attachment')
    sums = {}
    for line in files[checksum_name].decode('ascii').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9._-]+)', line)
        require(match is not None and match[2] not in sums, 'Invalid or duplicate checksum entry')
        sums[match[2]] = match[1]
    require(set(sums) == {apk, source, manifest_name}, 'Checksum file must cover exactly APK, source ZIP and manifest')
    for name, expected in sums.items():
        require(sha256(files[name]) == expected, f'Checksum mismatch: {name}')
    manifest = json.loads(files[manifest_name])
    required = {'file': apk, 'versionName': tag[1:], 'applicationId': 'io.github.originosfcmguardian',
                'debuggable': False, 'variant': 'release', 'minified': True,
                'signerCertificateSha256': CERTIFICATE, 'sourceTag': tag, 'sourceCommit': commit,
                'bytes': len(files[apk]), 'sha256': sha256(files[apk])}
    require(SHA.fullmatch(commit), 'Invalid source commit')
    require(all(type(manifest.get(key)) is type(value) and manifest.get(key) == value for key, value in required.items()),
            'Manifest identity, version, signature declaration or source commit mismatch')
    require(type(manifest.get('versionCode')) is int and manifest['versionCode'] > 0, 'Invalid version code')
    require(manifest.get('sourceArchive', {}).get('file') == source and
            manifest.get('sourceArchive', {}).get('sha256') == sha256(files[source]), 'Source archive manifest mismatch')
    require(isinstance(release.get('name'), str) and isinstance(release.get('body'), str), 'Invalid release notes')
    require(type(release.get('prerelease')) is bool, 'Invalid prerelease flag')
    return Prepared(release, files, commit)


def prepare(client, release):
    expected = set(names_for(release['tag_name']))
    assets = release.get('assets', [])
    require(len(assets) == 4 and {asset.get('name') for asset in assets} == expected,
            'Gitea release must have exactly the four approved attachments')
    # Validate filename and URL before any attachment request; no local paths are used.
    require(all(PurePosixPath(name).name == name for name in expected), 'Unsafe attachment name')
    files = {asset['name']: client.source_asset(release, asset) for asset in assets}
    return validate_source(release, files, tag_commit(client, release['tag_name'], True))


def verify_target_asset(client, asset, name, data):
    require(asset.get('name') == name and asset.get('size') == len(data) and asset.get('state') == 'uploaded',
            f'GitHub attachment incomplete: {name}')
    digest = asset.get('digest')
    actual = digest if digest is not None else 'sha256:' + sha256(client.target_asset(asset))
    require(actual == 'sha256:' + sha256(data), f'GitHub attachment differs; refusing replacement: {name}')


def sync(client, prepared, target_releases, publish=False):
    source, files = prepared.release, prepared.files
    tag = source['tag_name']
    require(tag_commit(client, tag, False) == prepared.commit, 'GitHub tag commit differs or mirror is incomplete')
    matches = [item for item in target_releases if item.get('tag_name') == tag]
    require(len(matches) <= 1, 'Multiple GitHub releases for one tag; resolve manually')
    existing = matches[0] if matches else None
    desired = {'name': source['name'], 'body': source['body'], 'prerelease': source['prerelease']}
    assets = existing.get('assets', []) if existing else []
    by_name = {}
    for asset in assets:
        name = asset.get('name')
        require(name in files and name not in by_name, 'Unexpected or duplicate GitHub attachment; resolve manually')
        verify_target_asset(client, asset, name, files[name])
        by_name[name] = asset
    missing = [name for name in files if name not in by_name]
    update = not existing or existing.get('draft') or any(existing.get(key) != value for key, value in desired.items())
    require(not existing or not existing.get('immutable') or (not missing and not update),
            'Immutable GitHub release differs; resolve manually')
    if not publish:
        print(f'{tag}: dry-run; would upload {len(missing)} assets; metadata update={bool(update)}')
        return
    require(client.token, '--publish requires GITHUB_TOKEN')
    if not existing:
        # The GitHub tag was checked above. Never ask the API to create a tag.
        existing = client.target('/releases', 'POST', dict(desired, tag_name=tag, draft=True, make_latest='legacy'))
    release_id = existing['id']
    require(type(release_id) is int and release_id > 0, 'Invalid GitHub release ID')
    for name in missing:
        uploaded = client.upload(release_id, name, files[name])
        verify_target_asset(client, uploaded, name, files[name])
    # A failed upload leaves a recoverable draft. Verify the whole final set before publishing.
    final = client.target(f'/releases/{release_id}')
    final_assets = final.get('assets', [])
    require(len(final_assets) == 4 and {a.get('name') for a in final_assets} == set(files), 'Final GitHub asset set differs')
    for asset in final_assets:
        verify_target_asset(client, asset, asset['name'], files[asset['name']])
    if update:
        final = client.target(f'/releases/{release_id}', 'PATCH', dict(desired, draft=False, make_latest='legacy'))
    require(not final.get('draft') and all(final.get(key) == value for key, value in desired.items()),
            'GitHub release publication not confirmed')
    print(f'{tag}: synchronized; {len(files)} verified assets; https://github.com/{REPOSITORY}/releases/tag/{tag}')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', help='Only synchronize an existing version tag')
    parser.add_argument('--publish', action='store_true', help='Permit GitHub writes using GITHUB_TOKEN')
    parser.add_argument('--source-only', action='store_true', help='Validate public Gitea release only; no GitHub requests')
    args = parser.parse_args(argv)
    require(not (args.source_only and args.publish), '--source-only cannot publish')
    if args.tag:
        names_for(args.tag)
    token = os.environ.get('GITHUB_TOKEN')
    require(not args.publish or token, '--publish requires GITHUB_TOKEN')
    client = Client(token)
    releases = [r for r in list_pages(client, True) if not r.get('draft') and (not args.tag or r.get('tag_name') == args.tag)]
    require(releases or not args.tag, 'Requested published Gitea release not found')
    targets = [] if args.source_only else list(list_pages(client, False))
    for release in reversed(releases):
        prepared = prepare(client, release)
        print(f"{release['tag_name']}: source validated; {len(prepared.files)} attachments; {prepared.commit}")
        if not args.source_only:
            sync(client, prepared, targets, args.publish)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (SyncError, ValueError, KeyError, TypeError) as error:
        print(f'Release sync stopped: {error}', file=sys.stderr)
        sys.exit(1)
