"""Release guards must fail before signing or publishing invalid versions."""

from pathlib import Path
import json
import os
import tempfile
import unittest
from unittest.mock import patch

from release import ABIS, assets, determine_version, release_notes, verify_apk


class ReleaseTests(unittest.TestCase):
    def test_beta_counter_ignores_other_versions_and_stable_tags(self):
        result = determine_version('beta', 'beta', '1.14.3',
                                   ['v1.14.2', 'v1.14.3-beta.1', 'v1.14.3-beta.9',
                                    'v1.14.4-beta.20', 'v1.14.3-beta.bad'], 1800000000)
        self.assertEqual(result['tag'], 'v1.14.3-beta.10')
        self.assertEqual(result['version_name'], '1.14.3-beta.10')
        self.assertGreater(int(result['version_code']), 51)

    def test_first_beta_and_stable(self):
        for channel, branch, tag in [('beta', 'beta', 'v1.14.3-beta.1'),
                                     ('stable', 'main', 'v1.14.3')]:
            self.assertEqual(determine_version(channel, branch, '1.14.3', [], 1800000000)['tag'], tag)

    def test_branch_channel_mismatch_and_bad_versions(self):
        for channel, branch, base in [('stable', 'beta', '1.14.3'),
                                      ('beta', 'main', '1.14.3'),
                                      ('other', 'main', '1.14.3'),
                                      ('beta', 'beta', '1.14.3-ai2')]:
            with self.assertRaises(ValueError):
                determine_version(channel, branch, base, [], 1800000000)

    def test_existing_stable_requires_new_version_on_both_channels(self):
        for channel, branch in [('stable', 'main'), ('beta', 'beta')]:
            with self.assertRaises(ValueError):
                determine_version(channel, branch, '1.14.3', ['v1.14.3'], 1800000000)

    def test_changelog_extracts_only_selected_section(self):
        text = '# Changelog\n\n## [Unreleased]\n\n### Added\n- AI search\n\n## [1.14.2] - 2026-11-03\n- Translations\n'
        self.assertEqual(release_notes(text, 'Unreleased'), '### Added\n- AI search')
        self.assertEqual(release_notes(text, '1.14.2'), '- Translations')

    def test_missing_empty_heading_only_or_duplicate_notes_fail(self):
        for text in ['## [1.14.2]\n- Old', '## [Unreleased]\n\n',
                     '## [Unreleased]\n### Added\n',
                     '## [Unreleased]\n- A\n## [Unreleased]\n- B\n']:
            with self.assertRaises(ValueError):
                release_notes(text, 'Unreleased')

    def test_invalid_timestamp_fails(self):
        with self.assertRaises(ValueError):
            determine_version('beta', 'beta', '1.14.3', [], 0)

    def test_apk_checks_signed_package_and_version(self):
        cert = 'a' * 64
        signature = f'Signer #1 certificate SHA-256 digest: {cert}'
        badging = "package: name='gallery.anlaki.dev' versionCode='100' versionName='1.14.3-beta.1'"
        with patch('release.run', side_effect=[signature, badging]):
            self.assertEqual(verify_apk(Path('app.apk'), Path('/tools'), '1.14.3-beta.1', 100), cert)
        for invalid in [badging.replace('gallery.anlaki.dev', 'org.fossify.gallery'),
                        badging.replace("versionCode='100'", "versionCode='99'"),
                        badging.replace('1.14.3-beta.1', '1.14.3')]:
            with patch('release.run', side_effect=[signature, invalid]):
                with self.assertRaises(ValueError):
                    verify_apk(Path('app.apk'), Path('/tools'), '1.14.3-beta.1', 100)
        with patch('release.run', return_value='Unsigned'):
            with self.assertRaises(ValueError):
                verify_apk(Path('app.apk'), Path('/tools'), '1.14.3-beta.1', 100)

    def test_assets_contain_verified_apks_exact_notes_and_matching_hashes(self):
        environment = {
            'RELEASE_CHANNEL': 'beta', 'RELEASE_TAG': 'v1.14.3-beta.1',
            'VERSION_NAME': '1.14.3-beta.1', 'VERSION_CODE': '100',
            'GITHUB_SERVER_URL': 'https://github.com',
            'GITHUB_REPOSITORY': 'anlaki-py/Gallery', 'GITHUB_SHA': 'abc123',
            'ANDROID_HOME': '/sdk',
        }
        original = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                Path('gradle').mkdir()
                Path('gradle/libs.versions.toml').write_text(
                    'app-build-minimumSDK = "26"\napp-build-targetSDK = "36"\n')
                mapping = Path('app/build/outputs/mapping/fossRelease/mapping.txt')
                mapping.parent.mkdir(parents=True)
                mapping.write_text('com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo -> a:\n'
                                   '    int platform_ -> platform_\n')
                Path('CHANGELOG.md').write_text('## [Unreleased]\n\n### Added\n- Local search\n')
                output = Path('app/build/outputs/apk/foss/release')
                output.mkdir(parents=True)
                for abi in ABIS:
                    (output / f'gallery-{abi}-release.apk').write_bytes(abi.encode())
                with patch.dict(os.environ, environment), patch('release.verify_apk', return_value='a' * 64), patch('release.verify_ai_apk'):
                    assets()
                metadata = json.loads(Path('release-assets/release-metadata.json').read_text())
                self.assertEqual(metadata['releaseNotes'], '### Added\n- Local search')
                self.assertEqual(metadata['packageName'], 'gallery.anlaki.dev')
                self.assertEqual(metadata['minSdk'], 26)
                self.assertTrue(metadata['prerelease'])
                self.assertEqual(len(metadata['assets']), len(ABIS))
                self.assertEqual(metadata['signingCertificate']['sha256'], 'a' * 64)
                checksums = Path('release-assets/checksums-sha256.txt').read_text()
                for record in metadata['assets']:
                    self.assertIn(f"{record['sha256']}  {record['file']}", checksums)
                    self.assertTrue(Path('release-assets', record['file']).is_file())
                # Mixed signing keys must stop asset preparation, before tagging.
                with patch.dict(os.environ, environment), patch('release.verify_apk', side_effect=['a' * 64, 'b' * 64]), patch('release.verify_ai_apk'):
                    with self.assertRaises(ValueError):
                        assets()
                # A signed APK must still be rejected when R8 removed a reflected AI field.
                with patch.dict(os.environ, environment), patch('release.verify_apk') as verify, \
                        patch('release.verify_ai_apk', side_effect=ValueError('SystemInfo.platform_ missing')):
                    with self.assertRaisesRegex(ValueError, 'SystemInfo.platform_'):
                        assets()
                    verify.assert_not_called()
            finally:
                os.chdir(original)


if __name__ == '__main__':
    unittest.main()
