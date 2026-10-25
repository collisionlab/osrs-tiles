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
                manifest.write_text(json.dumps(dict(version='a'*24, tileVersion=candidate, reviewCount=0)))
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

    def test_defaults_pin_insets_in_the_planner_and_only_tiles_in_coord_preview(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pin = dict(base='https://tiles', version='2-renderer', styles=['standard', 'tactical'])
            manifest = dict(version='a'*24, tileVersion=pin['version'], reviewCount=0)
            (root / 'pin.json').write_text(json.dumps(pin))
            (root / 'manifest.json').write_text(json.dumps(manifest))
            repositories, written, commands = [], [], []
            def run(*command, **kwargs):
                commands.append(command)
                if command[:3] == ('gh', 'repo', 'clone'):
                    repositories.append(command[3])
                    (Path(command[4]) / 'tiles.json').write_text(json.dumps(dict(version='1-renderer', custom=True,
                        insets=dict(version='b'*24, tileVersion='1-renderer'))))
                if command[:2] == ('git', 'add'):
                    written.append(json.loads((kwargs['cwd'] / 'tiles.json').read_text()))
                return SimpleNamespace(stdout='[]' if command[:3] == ('gh', 'pr', 'list') else '')
            with patch.object(module, 'run', side_effect=run), patch('sys.argv', ['pin-insets', str(root/'pin.json'), str(root/'manifest.json'), '--first-insets']):
                module.main()
            self.assertEqual(repositories, ['collisionlab/osrs-planner', 'collisionlab/osrs-coord-preview'])
            self.assertEqual(written, [{**pin, 'custom': True, 'insets': dict(version='a'*24, tileVersion=pin['version'])},
                                       {**pin, 'custom': True}])
            # The first insets need a look in the planner; coord-preview reads no insets.
            created = [c for c in commands if c[:3] == ('gh', 'pr', 'create')]
            self.assertEqual(['--draft' in c for c in created], [True, False])
            self.assertIn('Use tiles 2-renderer', created[1])

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

    def pin_with(self, flags, draft, naming=(), previous='', anchoring=()):
        """Refresh an open pin PR; returns the commands run and the body written."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pin = dict(base='https://tiles', version='2-renderer', styles=['tactical'])
            manifest = dict(version='a'*24, tileVersion=pin['version'], reviewCount=len(naming) + len(anchoring), componentCount=9)
            (root / 'pin.json').write_text(json.dumps(pin))
            (root / 'manifest.json').write_text(json.dumps(manifest))
            (root / 'review.json').write_text(json.dumps(dict(issues=[dict(componentId=c, reason='required-component-unnamed')
                                                                      for c in naming]
                                                              + [dict(componentId=c, reason='required-component-unanchored')
                                                                 for c in anchoring])))
            (root / 'labels.json').write_text(json.dumps(dict(regions={c: dict(name=f'Unidentified terrain at {c}')
                                                                       for c in naming})))
            commands, bodies = [], []
            def run(*command, **kwargs):
                commands.append(command)
                if command[:3] == ('gh', 'repo', 'clone'):
                    (Path(command[4]) / 'tiles.json').write_text(json.dumps(dict(version='2-renderer')))
                if command[:3] == ('gh', 'pr', 'edit'):
                    bodies.append(Path(command[command.index('--body-file') + 1]).read_text())
                listing = json.dumps([dict(number=7, isDraft=draft, body=previous)])
                return SimpleNamespace(stdout=listing if command[:3] == ('gh', 'pr', 'list') else '')
            with patch.object(module, 'run', side_effect=run), \
                    patch('sys.argv', ['pin-insets', str(root/'pin.json'), str(root/'manifest.json'), '--repo', 'owner/one',
                                       '--run-url', 'https://run', *flags]):
                module.main()
            return commands, bodies[0]

    def test_a_draft_that_no_longer_needs_review_is_marked_ready(self):
        commands, body = self.pin_with([], draft=True)
        self.assertIn(('gh', 'pr', 'ready', '7', '--repo', 'owner/one'), commands)
        self.assertLess(next(i for i, c in enumerate(commands) if c[:2] == ('git', 'push')),
                        commands.index(('gh', 'pr', 'ready', '7', '--repo', 'owner/one')))
        self.assertIn('No action needed', body)
        self.assertNotIn('Action needed', body)

    def test_review_drafts_before_the_push_and_says_what_to_do(self):
        commands, body = self.pin_with(['--geometry-review'], draft=False, naming=['p0-1-2-abc'])
        undo = commands.index(('gh', 'pr', 'ready', '7', '--undo', '--repo', 'owner/one'))
        self.assertLess(undo, next(i for i, c in enumerate(commands) if c[:2] == ('git', 'push')))
        self.assertNotIn(('gh', 'pr', 'ready', '7', '--repo', 'owner/one'), commands)
        self.assertIn('## Action needed', body)
        self.assertIn('**draft**', body)
        self.assertIn('The inset areas changed a lot.', body)
        self.assertIn('## Follow-ups', body)
        self.assertIn('1 reachable areas have no name', body)
        self.assertNotIn('no anchor', body)

    def test_unnamed_and_unanchored_areas_are_follow_ups_that_dont_hold_the_pin(self):
        commands, body = self.pin_with([], draft=True, naming=['p0-1-2-abc'], anchoring=['p0-3-4-def', 'p0-1-2-abc'])
        self.assertIn(('gh', 'pr', 'ready', '7', '--repo', 'owner/one'), commands)
        self.assertIn('No action needed', body)
        self.assertIn('## Follow-ups', body)
        self.assertNotIn('- [ ]', body)
        self.assertIn('- **1 reachable areas have no name', body)
        self.assertIn('- **2 reachable areas have no anchor', body)
        self.assertIn('`p0-3-4-def` (required-component-unanchored)', body)
        self.assertIn('`p0-1-2-abc` (required-component-unnamed): shown as "Unidentified terrain at p0-1-2-abc"', body)
        self.assertIn('<!-- review-keys:  -->', body)
        self.assertIn('Built by https://run.', body)

    def test_a_draft_that_still_needs_review_stays_a_draft(self):
        with tempfile.TemporaryDirectory() as temporary:
            review = Path(temporary) / 'tiles.json'
            review.write_text(json.dumps(dict(review=True, styles=dict(tactical=dict(tiles=10, changed=3, added=0, removed=0)))))
            commands, body = self.pin_with(['--tile-review', str(review)], draft=True)
        self.assertFalse([c for c in commands if c[:3] == ('gh', 'pr', 'ready')])
        self.assertIn('The map tiles changed a lot.', body)
        self.assertIn('tactical: 3 changed, 0 added, 0 removed of 10', body)

    def test_a_person_accepting_the_same_reasons_is_remembered(self):
        _, drafted = self.pin_with(['--geometry-review'], draft=True, naming=['p0-1-2-abc'])
        self.assertIn('<!-- review-keys: geometry:2-renderer -->', drafted)
        # Marked ready by a person, then refreshed with the same findings: stays ready, and a new
        # follow-up doesn't change that.
        commands, body = self.pin_with(['--geometry-review'], draft=False, naming=['p0-1-2-abc', 'p0-3-4-def'],
                                       previous=drafted)
        self.assertFalse([c for c in commands if c[:3] == ('gh', 'pr', 'ready')])
        self.assertIn('A person marked this ready', body)
        self.assertIn('- [x] **The inset areas changed a lot.**', body)
        # Something new turns up: back to draft.
        with tempfile.TemporaryDirectory() as temporary:
            review = Path(temporary) / 'tiles.json'
            review.write_text(json.dumps(dict(review=True, styles=dict(tactical=dict(tiles=10, changed=3, added=0, removed=0)))))
            commands, body = self.pin_with(['--geometry-review', '--tile-review', str(review)], draft=False, previous=drafted)
        self.assertIn(('gh', 'pr', 'ready', '7', '--undo', '--repo', 'owner/one'), commands)
        self.assertIn('## Action needed', body)


if __name__ == '__main__':
    unittest.main()
