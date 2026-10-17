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

if __name__ == '__main__':
    unittest.main()
