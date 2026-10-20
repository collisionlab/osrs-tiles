import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
loader = importlib.machinery.SourceFileLoader('build_insets', str(ROOT / 'scripts/build-insets'))
spec = importlib.util.spec_from_loader(loader.name, loader)
build = importlib.util.module_from_spec(spec)
loader.exec_module(build)


class BuildTest(unittest.TestCase):
    def prepare(self, root):
        cache = root / 'cache'
        cache.mkdir()
        (cache / 'main_file_cache.idx255').write_bytes(b'cache')
        keys = root / 'keys.json'
        keys.write_bytes(b'[]')
        pin = root / 'pin.json'
        pin.write_text(json.dumps(dict(base='https://tiles', version='1-renderer', styles=['standard', 'tactical'])))
        transports = root / 'transports'
        transports.mkdir()
        (transports / 'transports.tsv').write_text('Start\tEnd\tAction\n')
        args = SimpleNamespace(cache=cache, xteas=keys, pin=pin, transports=transports, commit='source', output=root / 'out')
        return args

    TOPOLOGY = dict(schemaVersion=1, mapIndexRevision=1, loadedMapSquares=[50 << 8 | 50],
                    surfaceRects=[[0, 3200, 3200, 64, 64]],
                    spans=[[0, 3200, 3200, 3202], [1, 3200, 3200, 3202]])

    def tiles(self, index, planes=4):
        """fetch() for a published tile version: every style has `index`."""
        manifest = dict(layout='explv-tms-v1', planes=[{}] * planes,
                        cacheIdx255Sha256=build.digest(b'cache'), xteasSha256=build.digest(b'[]'))
        return lambda url: manifest if url.endswith('manifest.json') else index

    def extract(self, command, **kwargs):
        self.assertEqual(command[2], 'insetTopology')  # Insets never render.
        Path(command[3].split(' ', 1)[1]).write_text(json.dumps(self.TOPOLOGY))

    def test_metadata_build_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', side_effect=self.tiles({'8/35/31.png': 'a', 'p1/8/35/31.png': 'b'})), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=self.extract) as run:
                result = build.build(args)
                first = (result / 'manifest.json').read_bytes()
                self.assertEqual(build.build(args), result)
                self.assertEqual((result / 'manifest.json').read_bytes(), first)
                self.assertEqual(run.call_count, 1)
            manifest = json.loads(first)
            self.assertEqual(manifest['schemaVersion'], 2)
            self.assertEqual(manifest['planes'], [[50 << 8 | 50], [50 << 8 | 50], [], []])
            self.assertNotIn('supplemental', manifest)
            self.assertEqual(sorted(p.name for p in result.iterdir()),
                             ['anchors.json', 'labels.json', 'manifest.json', 'regions.json', 'review.json'])

    def test_rejects_tiles_missing_a_covered_square(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', side_effect=self.tiles({'8/35/31.png': 'a'})), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=self.extract):
                with self.assertRaisesRegex(ValueError, 'lack 1 map squares.*plane 1 square 50,50'):
                    build.build(args)
            self.assertEqual(list(args.output.rglob('manifest.json')), [])

    def test_rejects_tiles_without_upper_planes(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', side_effect=self.tiles({}, planes=0)), \
                    patch.object(build.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'all-plane layout'):
                    build.build(args)
                run.assert_not_called()

    def test_failed_candidate_leaves_no_published_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', side_effect=self.tiles({})), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=RuntimeError('extractor failed')):
                with self.assertRaisesRegex(RuntimeError, 'extractor failed'):
                    build.build(args)
            self.assertEqual(list(args.output.rglob('manifest.json')), [])

    def test_rejects_cache_mismatch_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', return_value={'planes': [{}] * 4}), patch.object(build.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'do not match'):
                    build.build(args)
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
