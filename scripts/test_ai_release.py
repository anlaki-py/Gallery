"""Guard the minified APK against MediaPipe's reflected-field startup failure."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from release import ai_message_descriptor, verify_ai_apk

MESSAGE = 'com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo'
DESCRIPTOR = 'Lle3;'
VALID_DUMP = """Class #0            -
  Class descriptor  : 'Lle3;'
  Static fields     -
  Instance fields   -
    #0              : (in Lle3;)
      name          : 'platform_'
      type          : 'I'
      access        : 0x0002 (PRIVATE)
  Direct methods    -
  Virtual methods   -
"""


class AiReleaseTests(unittest.TestCase):
    def verify(self, dumps):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/irrelevant.dex', b'ignored')
                for i in range(len(dumps)):
                    archive.writestr('classes' + (str(i + 1) if i else '') + '.dex', b'dex')
            with patch('release.run', side_effect=dumps) as command:
                verify_ai_apk(apk, Path('/sdk/build-tools'), DESCRIPTOR)
                self.assertEqual(command.call_count, len(dumps))
                self.assertEqual(command.call_args.args[0], '/sdk/build-tools/dexdump')

    def test_unchanged_fields_need_no_mapping_entry(self):
        self.assertEqual(ai_message_descriptor(f'{MESSAGE} -> le3:\n'), DESCRIPTOR)
        self.assertEqual(ai_message_descriptor(f'{MESSAGE} -> {MESSAGE}:\n'),
                         'L' + MESSAGE.replace('.', '/') + ';')

    def test_missing_or_removed_message_is_rejected(self):
        for mapping in ['', f'{MESSAGE} -> R8$$REMOVED$$CLASS$$1:\n', 'other.Class -> a:\n']:
            with self.subTest(mapping=mapping), self.assertRaises(ValueError):
                ai_message_descriptor(mapping)

    def test_declared_field_is_verified_in_secondary_dex(self):
        self.verify([VALID_DUMP.replace(DESCRIPTOR, 'Lother/Class;'), VALID_DUMP])

    def test_missing_or_renamed_or_wrong_type_field_is_rejected(self):
        for dump in [VALID_DUMP.replace("'platform_'", "'a'"),
                     VALID_DUMP.replace("'I'", "'Z'"),
                     VALID_DUMP.replace(DESCRIPTOR, 'Lother/Class;')]:
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                self.verify([dump])

    def test_static_field_or_method_reference_cannot_satisfy_guard(self):
        for dump in [VALID_DUMP.replace('  Instance fields   -', ''),
                     VALID_DUMP.replace('  Instance fields   -', '  Direct methods   -')]:
            with self.subTest(dump=dump), self.assertRaises(ValueError):
                self.verify([dump])

    def test_missing_dex_is_rejected(self):
        with self.assertRaises(ValueError):
            self.verify([])
