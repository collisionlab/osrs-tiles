import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

loader = importlib.machinery.SourceFileLoader('pin_insets', str(Path(__file__).resolve().parents[1] / 'scripts/pin-insets'))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)

class PinTest(unittest.TestCase):
    def test_new_cache_with_unchanged_renderer_can_advance_but_cannot_regress(self):
        for previous, candidate, allowed in [('1-renderer', '2-renderer', True), ('2-renderer', '1-renderer', False)]:
            with self.subTest(previous=previous), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                pin = root / 'pin.json'
                manifest = root / 'manifest.json'
                pin.write_text(json.dumps(dict(base='https://tiles', version=candidate, styles=['tactical'])))
                manifest.write_text(json.dumps(dict(version='a'*24, tileVersion=candidate, namingReviewCount=0)))
                commands = []
                def run(*command, **kwargs):
                    commands.append(command)
                    if command[:3] == ('gh', 'repo', 'clone'):
                        (Path(command[4]) / 'tiles.json').write_text(json.dumps(dict(version=previous)))
                    return SimpleNamespace(stdout='[]' if command[:3] == ('gh', 'pr', 'list') else '')
                with patch.object(module, 'run', side_effect=run), patch.object(module.subprocess, 'run') as ancestry, \
                        patch('sys.argv', ['pin-insets', str(pin), str(manifest)]):
                    if allowed:
                        module.main()
                        self.assertTrue(any(c[:3] == ('gh', 'pr', 'create') for c in commands))
                    else:
                        with self.assertRaisesRegex(ValueError, 'predates'):
                            module.main()
                    ancestry.assert_not_called()

    def test_defaults_update_both_consumers_preserving_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pin = dict(base='https://tiles', version='2-renderer', styles=['standard', 'tactical'])
            manifest = dict(version='a'*24, tileVersion=pin['version'], namingReviewCount=0)
            (root / 'pin.json').write_text(json.dumps(pin))
            (root / 'manifest.json').write_text(json.dumps(manifest))
            repositories, written, commands = [], [], []
            def run(*command, **kwargs):
                commands.append(command)
                if command[:3] == ('gh', 'repo', 'clone'):
                    repositories.append(command[3])
                    (Path(command[4]) / 'tiles.json').write_text(json.dumps(dict(version='1-renderer', custom=True)))
                if command[:2] == ('git', 'add'):
                    written.append(json.loads((kwargs['cwd'] / 'tiles.json').read_text()))
                return SimpleNamespace(stdout='[]' if command[:3] == ('gh', 'pr', 'list') else '')
            with patch.object(module, 'run', side_effect=run), patch('sys.argv', ['pin-insets', str(root/'pin.json'), str(root/'manifest.json'), '--review']):
                module.main()
            self.assertEqual(repositories, ['collisionlab/osrs-planner', 'collisionlab/osrs-coord-preview'])
            self.assertEqual(written, [{**pin, 'custom': True, 'insets': dict(version='a'*24, tileVersion=pin['version'])}]*2)
            self.assertEqual(sum('--draft' in c for c in commands), 2)

    def test_repeated_repo_arguments_and_already_pinned_are_noops(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pin = dict(version='2-renderer', insets=dict(version='a'*24, tileVersion='2-renderer'))
            (root/'pin.json').write_text(json.dumps(pin))
            (root/'manifest.json').write_text(json.dumps(dict(version='a'*24, tileVersion=pin['version'])))
            commands = []
            def run(*command, **kwargs):
                commands.append(command)
                (Path(command[4])/'tiles.json').write_text(json.dumps({**pin, 'custom': True}))
            with patch.object(module, 'run', side_effect=run), patch('sys.argv', ['pin-insets', str(root/'pin.json'), str(root/'manifest.json'), '--repo', 'owner/one', '--repo', 'owner/two']):
                module.main()
            self.assertEqual([c[3] for c in commands], ['owner/one', 'owner/two'])
            self.assertTrue(all(c[:3] == ('gh', 'repo', 'clone') for c in commands))

if __name__ == '__main__':
    unittest.main()
