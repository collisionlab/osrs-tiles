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
        pin.write_text(json.dumps(dict(base='https://tiles', version='1-renderer')))
        transports = root / 'transports'
        transports.mkdir()
        (transports / 'transports.tsv').write_text('Start\tEnd\tAction\n')
        args = SimpleNamespace(cache=cache, xteas=keys, pin=pin, transports=transports, commit='source', output=root / 'out')
        return args

    def test_metadata_build_reuses_plane_zero_and_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            manifest = dict(layout='explv-tms-v1', plane=0,
                            cacheIdx255Sha256=build.digest(b'cache'), xteasSha256=build.digest(b'[]'))
            topology = dict(schemaVersion=1, mapIndexRevision=1, loadedMapSquares=[50 << 8 | 50],
                            surfaceRects=[[0, 3200, 3200, 64, 64]], spans=[[0, 3200, 3200, 3202]])
            def extract(command, **kwargs):
                self.assertEqual(command[2], 'insetTopology')  # No raster invocation needed.
                Path(command[3].split(' ', 1)[1]).write_text(json.dumps(topology))
            with patch.object(build, 'fetch', side_effect=lambda url: manifest if url.endswith('manifest.json') else {'8/35/31.png': 'hash'}), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=extract) as run:
                result = build.build(args)
                first = (result / 'manifest.json').read_bytes()
                self.assertEqual(build.build(args), result)
                self.assertEqual((result / 'manifest.json').read_bytes(), first)
                self.assertEqual(run.call_count, 1)
                self.assertEqual(json.loads(first)['supplemental'], [[], [], [], []])

    def test_failed_candidate_leaves_no_published_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            manifest = dict(layout='explv-tms-v1', plane=0,
                            cacheIdx255Sha256=build.digest(b'cache'), xteasSha256=build.digest(b'[]'))
            with patch.object(build, 'fetch', side_effect=lambda url: manifest if url.endswith('manifest.json') else {}), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=RuntimeError('extractor failed')):
                with self.assertRaisesRegex(RuntimeError, 'extractor failed'):
                    build.build(args)
            self.assertEqual(list(args.output.rglob('manifest.json')), [])

    def test_rejects_cache_mismatch_before_extraction(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.prepare(Path(temporary))
            with patch.object(build, 'fetch', return_value={}), patch.object(build.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'do not match'):
                    build.build(args)
                run.assert_not_called()

class CachedRasterTest(unittest.TestCase):
    def test_warm_and_cold_render_caches_produce_identical_release_bytes(self):
        import shlex
        import shutil
        with tempfile.TemporaryDirectory() as temporary:
            args = BuildTest().prepare(Path(temporary))
            manifest = dict(layout='explv-tms-v1', plane=0,
                            cacheIdx255Sha256=build.digest(b'cache'), xteasSha256=build.digest(b'[]'))
            topology = dict(schemaVersion=1, mapIndexRevision=1, loadedMapSquares=[50 << 8 | 50],
                            surfaceRects=[[0, 3200, 3200, 64, 64]], spans=[[1, 3200, 3200, 3202]])
            calls = []
            def generate(command, **kwargs):
                calls.append(command[2])
                arguments = shlex.split(command[3].removeprefix('--args='))
                if command[2] == 'insetTopology':
                    Path(arguments[1]).write_text(json.dumps(topology))
                else:
                    flags = dict(zip(arguments[::2], arguments[1::2]))
                    out = Path(flags['--out']) / 'tactical'
                    native = out / '8/35/31.png'
                    native.parent.mkdir(parents=True)
                    native.write_bytes(b'pixels')
                    build.write(out / 'index.json', {'8/35/31.png': build.digest(b'pixels')})
                    # Simulate a cache warmed by a different metadata revision.
                    build.write(out / 'manifest.json', dict(missingKeyRegions=[], rendererCommit=str(len(calls))))
            with patch.object(build, 'fetch', side_effect=lambda url: manifest if url.endswith('manifest.json') else {}), \
                    patch.object(build, 'read_transports', return_value=([], 'transport-hash')), \
                    patch.object(build.subprocess, 'run', side_effect=generate):
                candidate = build.build(args)
                first = (candidate / 'manifest.json').read_bytes()
                self.assertNotIn('rendererCommit', json.loads((candidate / 'planes/1/manifest.json').read_text()))
                shutil.rmtree(args.output)
                build.build(args)
                self.assertEqual(calls, ['insetTopology', 'tiles'])
                self.assertEqual((candidate / 'manifest.json').read_bytes(), first)
                shutil.rmtree(args.output)
                shutil.rmtree(args.output.parent / 'inset-topology')
                shutil.rmtree(args.output.parent / 'inset-raster')
                build.build(args)
                self.assertEqual((candidate / 'manifest.json').read_bytes(), first)


if __name__ == '__main__':
    unittest.main()
