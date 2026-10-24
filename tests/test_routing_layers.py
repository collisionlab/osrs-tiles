"""The publication completion marker must name exactly the checked payload bytes."""
import hashlib
import json
import runpy
import tempfile
import unittest
from pathlib import Path

verify = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'scripts/verify-routing-layers'))['verify']


class RoutingPublicationTest(unittest.TestCase):
    def candidate(self, root):
        payload = b'[]'
        (root / 'components.json').write_bytes(payload)
        manifest = {'schemaVersion': 3, 'plugin': {'repository': 'Skretzo/shortest-path'},
                    'files': {'components.json': hashlib.sha256(payload).hexdigest()}}
        encoded = json.dumps(manifest, separators=(',', ':')).encode()
        manifest['version'] = hashlib.sha256(encoded).hexdigest()[:24]
        (root / 'manifest.json').write_text(json.dumps(manifest))
        return manifest

    def test_checked_completion_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = self.candidate(root)
            self.assertEqual(expected, verify(root))
            (root / 'components.json').write_bytes(b'[1]')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                verify(root)

    def test_manifest_content_address_and_unlisted_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.candidate(root)
            (root / 'unexpected.bin').write_bytes(b'')
            with self.assertRaisesRegex(ValueError, 'unlisted'):
                verify(root)
            (root / 'unexpected.bin').unlink()
            manifest['version'] = '0' * 24
            (root / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, 'content address'):
                verify(root)

    def test_component_tiles_are_listed_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b'png'
            files = {'components/8/35/31.png': payload, 'components/p1/3/1/0.png': payload}
            for name, data in files.items():
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_bytes(data)
            manifest = {'schemaVersion': 3, 'plugin': {'repository': 'Skretzo/shortest-path'},
                        'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
            encoded = json.dumps(manifest, separators=(',', ':')).encode()
            manifest['version'] = hashlib.sha256(encoded).hexdigest()[:24]
            (root / 'manifest.json').write_text(json.dumps(manifest))
            self.assertEqual(manifest, verify(root))


if __name__ == '__main__':
    unittest.main()
