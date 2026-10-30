"""The automatic routing build picks the closest tiles release and never holds the inset pin back."""
import json
import runpy
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class BuildRoutingLayersTest(unittest.TestCase):
    def setUp(self):
        self.build = runpy.run_path(str(SCRIPTS / 'build-routing-layers'), run_name='test')
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.pin = self.root / 'tiles.json'
        self.pin.write_text(json.dumps({'base': 'https://tiles.example', 'version': '2752-abc',
                                        'routingLayers': {'tileVersion': '2746-abc', 'pluginRevision': 'old'}}))

    def tearDown(self):
        self.directory.cleanup()

    def run_main(self, argv):
        import sys
        saved = sys.argv
        sys.argv = ['build-routing-layers', str(self.pin), '--plugin-dir', str(self.root), '--no-upload',
                    '--notes', str(self.root / 'notes.json'), *argv]
        try:
            self.build['main']()
        finally:
            sys.argv = saved

    def test_same_plugin_does_nothing(self):
        self.run_main(['--revision', 'old'])
        self.assertFalse((self.root / 'notes.json').exists())

    def test_a_failure_is_a_follow_up_not_an_error(self):
        # No git checkout here, so reading the plugin's map fails.
        self.run_main(['--revision', 'new'])
        notes = json.loads((self.root / 'notes.json').read_text())
        self.assertEqual(1, len(notes))
        self.assertIn("weren't rebuilt for plugin `new`", notes[0][0])
        self.assertEqual('old', json.loads(self.pin.read_text())['routingLayers']['pluginRevision'])

    def test_picks_the_tiles_with_fewest_differences_newest_on_a_tie(self):
        chosen = []
        globals_ = self.build['main'].__globals__
        globals_['plugin_planes'] = lambda *_: {}
        globals_['generate'] = lambda _d, _r, version, *_: chosen.append(version) or (_ for _ in ()).throw(RuntimeError('stop'))
        globals_['differences'] = lambda _b, version, _p: {'2752-abc': ['a', 'b'], '2746-abc': ['a']}[version]
        self.run_main(['--revision', 'new'])
        globals_['differences'] = lambda _b, version, _p: ['a']
        self.run_main(['--revision', 'new'])
        self.assertEqual(['2746-abc', '2752-abc'], chosen)


class PinRoutingTest(unittest.TestCase):
    def test_older_tiles_are_allowed_newer_refused(self):
        pin_routing = runpy.run_path(str(SCRIPTS / 'pin-routing-layers'), run_name='test')['pin_routing']
        globals_ = pin_routing.__globals__
        manifest = {'version': 'v', 'tileVersion': '2746-abc', 'collisionContentSha256': 'c', 'transportSha256': 't',
                    'separatorSha256': 's', 'plugin': {'revision': 'r'}}
        globals_['runpy'] = type('R', (), {'run_path': staticmethod(lambda *_a, **_k: {'verify': lambda _p: dict(manifest)})})
        with tempfile.TemporaryDirectory() as directory:
            pin = Path(directory) / 'tiles.json'
            pin.write_text(json.dumps({'version': '2752-abc'}))
            pin_routing(Path(directory) / 'm' / 'manifest.json', pin)
            self.assertEqual('2746-abc', json.loads(pin.read_text())['routingLayers']['tileVersion'])
            manifest['tileVersion'] = '2760-abc'
            with self.assertRaises(SystemExit):
                pin_routing(Path(directory) / 'm' / 'manifest.json', pin)


if __name__ == '__main__':
    unittest.main()
