"""Version and verify Gallery releases, following akihz's channel rules."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64', 'universal')
PACKAGE = 'gallery.anlaki.dev'


def run(*args):
    return subprocess.check_output(args, text=True, timeout=120).strip()


def properties(path):
    return dict(line.split('=', 1) for line in Path(path).read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def release_notes(changelog, section):
    """Extract exactly one nonempty section before any tag can be created."""
    matches = list(re.finditer(r'^## \[([^\]]+)\][^\n]*\n', changelog, re.M))
    selected = [i for i, match in enumerate(matches) if match[1] == section]
    if len(selected) != 1:
        raise ValueError(f'Expected one CHANGELOG.md section for {section}')
    i = selected[0]
    end = matches[i + 1].start() if i + 1 < len(matches) else len(changelog)
    notes = changelog[matches[i].end():end].strip()
    if not re.search(r'^[-*] \S', notes, re.M):
        raise ValueError(f'CHANGELOG.md section {section} needs release notes')
    return notes


def determine_version(channel, branch, base, tags, now):
    """Keep channels branch-locked and increment only the beta counter."""
    expected = {'beta': 'beta', 'stable': 'main'}
    if channel not in expected or branch != expected[channel]:
        raise ValueError(f'Channel {channel} cannot release from branch {branch}')
    if not re.fullmatch(r'\d+\.\d+\.\d+', base):
        raise ValueError('VERSION_NAME must be a stable x.y.z version')
    tag = f'v{base}'
    if channel == 'beta':
        if tag in tags:
            raise ValueError('Bump VERSION_NAME before starting the next beta cycle')
        counters = [int(m[1]) for item in tags
                    if (m := re.fullmatch(re.escape(tag) + r'-beta\.(\d+)', item))]
        tag += f'-beta.{max(counters, default=0) + 1}'
    elif tag in tags:
        raise ValueError(f'Release tag {tag} already exists')
    # Same epoch as akihz. Shared by every ABI so switching APKs remains possible.
    code = int(now) - 1704067200
    if not 1 <= code <= 2100000000:
        raise ValueError('Release timestamp is outside the Android versionCode range')
    return {'tag': tag, 'version_name': tag[1:], 'version_code': str(code)}


def version():
    channel = os.environ['RELEASE_CHANNEL']
    values = determine_version(channel, os.environ['GITHUB_REF_NAME'],
                               properties('gradle.properties')['VERSION_NAME'],
                               run('git', 'tag', '-l').splitlines(), time.time())
    section = 'Unreleased' if channel == 'beta' else values['version_name']
    release_notes(Path('CHANGELOG.md').read_text(), section)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in values.items():
            output.write(f'{key}={value}\n')
    print(f"Validated {values['tag']}, versionCode {values['version_code']}")


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def verify_apk(path, tools, version_name, version_code):
    """Reject unsigned, misversioned or incorrectly named packages."""
    signature = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path))
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$',
                              signature, re.M)
    if len(certificates) != 1:
        raise ValueError(f'Expected one signing certificate for {path.name}')
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(path))
    expected = {'name': PACKAGE, 'versionCode': str(version_code), 'versionName': version_name}
    package_line = next(line for line in badging.splitlines() if line.startswith('package:'))
    actual = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    for key, value in expected.items():
        if actual.get(key) != value:
            raise ValueError(f'{path.name}: expected {key}={value}, got {actual.get(key)}')
    return certificates[0].lower()


def assets():
    channel = os.environ['RELEASE_CHANNEL']
    tag = os.environ['RELEASE_TAG']
    version_name = os.environ['VERSION_NAME']
    code = int(os.environ['VERSION_CODE'])
    if channel not in ('stable', 'beta') or tag != f'v{version_name}':
        raise ValueError('Inconsistent release channel or tag')
    repository = f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}"
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
    destination = Path('release-assets')
    destination.mkdir(exist_ok=True)
    section = 'Unreleased' if channel == 'beta' else version_name
    notes = release_notes(Path('CHANGELOG.md').read_text(), section)
    certificate = None
    records = []
    for abi in ABIS:
        source = Path(f'app/build/outputs/apk/foss/release/gallery-{abi}-release.apk')
        cert = verify_apk(source, tools, version_name, code)
        if certificate is not None and certificate != cert:
            raise ValueError('Release APKs must share the same signing certificate')
        certificate = cert
        filename = f'gallery-{tag}-{abi}.apk'
        target = destination / filename
        shutil.copyfile(source, target)
        records.append({'abi': abi, 'file': filename, 'sha256': sha256(target),
                        'size': target.stat().st_size,
                        'url': f'{repository}/releases/download/{tag}/{filename}'})
    changelog_url = f'{repository}/blob/{tag}/CHANGELOG.md'
    libs = Path('gradle/libs.versions.toml').read_text()
    sdk = lambda name: int(re.search(rf'app-build-{name} = "(\d+)"', libs)[1])
    metadata = {
        'schemaVersion': 1, 'packageName': PACKAGE,
        'developer': {'name': 'anlaki', 'url': 'https://github.com/anlaki-py'},
        'versionName': version_name, 'versionCode': code,
        'channel': channel, 'prerelease': channel == 'beta',
        'commit': os.environ['GITHUB_SHA'],
        'releaseDate': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'repository': repository, 'releaseUrl': f'{repository}/releases/tag/{tag}',
        'changelog': changelog_url, 'releaseNotes': notes,
        'minSdk': sdk('minimumSDK'), 'targetSdk': sdk('targetSDK'),
        'requirements': {'aiSearch': {'optional': True, 'abis': ['arm64-v8a', 'x86_64'],
                                      'modelDownloadBytes': 484622336}},
        'signingCertificate': {'sha256': certificate}, 'assets': records,
    }
    (destination / 'release-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (destination / 'checksums-sha256.txt').write_text(''.join(
        f"{record['sha256']}  {record['file']}\n" for record in records))
    (destination / 'release-notes.md').write_text(f'{notes}\n\n[Full changelog]({changelog_url})\n')
    print(f'Verified {len(records)} APKs for {PACKAGE} {version_name}')


if __name__ == '__main__':
    try:
        {'version': version, 'assets': assets}[sys.argv[1]]()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        sys.exit(f'Release failed: {error}')
