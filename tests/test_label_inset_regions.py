import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import label_inset_regions as labels
import snapshot_inset_label_sources as snapshot


def fixture():
    catalogue = dict(schemaVersion=1, surfaceRects=[[0, 0, 0, 10, 10]], regions=[
        dict(id='a', plane=0, bounds=[100, 100, 110, 110], classification='inset', tileCount=36,
             mask=[[100, 100, 2, 10], [102, 100, 8, 2]]),
        dict(id='b', plane=1, bounds=[100, 100, 110, 110], classification='inset', tileCount=100,
             mask=[[100, 100, 10, 10]]),
        dict(id='c', plane=0, bounds=[200, 200, 210, 210], classification='inset', tileCount=100,
             mask=[[200, 200, 10, 10]]),
    ])
    placed = dict(anchors=[dict(point=[5, 5, 0], hops=1, path=[])], defaultAnchor=0, status='direct')
    anchors = dict(schemaVersion=1, catalogueSha256='hash', insetRegionIds=['a', 'b'], reachableRegionIds=['a', 'b'],
                   regions=dict(a=placed, b=placed, c=placed), connections={})
    sources = dict(schemaVersion=1, records=[dict(id='pin', name='A dungeon', point=[105, 105, 0], source='wiki:rev1')])
    areas = [dict(key='dungeon', name='A dungeon', status='named', reason='reviewed', aliases=['Old name'], components=[
        dict(componentId='a', seed=[100, 101, 0], evidence=['pin'], reason='Pin in void; independently reviewed seed'),
        dict(componentId='b', seed=[100, 101, 1], evidence=['pin'], reason='Reviewed second floor'),
    ])]
    return catalogue, anchors, sources, dict(schemaVersion=1, areas=areas)


class LabelTest(unittest.TestCase):
    def test_multiple_components_per_area_and_multiple_areas_per_component(self):
        cat, anchors, sources, authored = fixture()
        extra = copy.deepcopy(authored['areas'][0])
        extra.update(key='subarea', name='A subarea', components=extra['components'][:1])
        authored['areas'].append(extra)
        output, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        # Both names show, but two areas on one component need a cut.
        self.assertEqual(report['issues'], [dict(componentId='a', areas=['dungeon', 'subarea'],
                                                 reason='component-has-several-areas')])
        self.assertEqual(output['regions']['a']['name'], 'A dungeon / A subarea')
        self.assertEqual(output['regions']['b']['name'], 'A dungeon')
        self.assertEqual(output['regions']['c']['status'], 'unresolved')
        self.assertIn('200, 200', output['regions']['c']['name'])
        self.assertEqual(output['evidence']['pin']['point'], [105, 105, 0])
        self.assertEqual((output, report), labels.compile_labels(cat, anchors, sources, authored, 'hash'))

    def test_wrong_plane_holes_changed_geometry_and_removed_evidence_need_review(self):
        for change in ('wrong-plane', 'hole', 'changed-id', 'removed-source'):
            cat, anchors, sources, authored = fixture()
            if change == 'wrong-plane':
                authored['areas'][0]['components'][0]['seed'][2] = 1
            elif change == 'hole':
                authored['areas'][0]['components'][0]['seed'] = [105, 105, 0]
            elif change == 'changed-id':
                cat['regions'][0]['id'] = 'new-a'
            else:
                sources['records'] = []
            result, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
            self.assertTrue(report['issues'], change)
            if change == 'removed-source':
                # Stale evidence is a note: the area hasn't moved, so it keeps its name.
                self.assertEqual(result['regions']['a']['status'], 'named')
                self.assertEqual({i['reason'] for i in report['issues']}, {'evidence-removed'})
            if change == 'changed-id':
                self.assertEqual(result['regions']['new-a']['status'], 'named')
                self.assertIn('component-id-changed', [i['reason'] for i in report['issues']])

    def test_cuts_without_terrain_need_review(self):
        cat, anchors, sources, authored = fixture()
        cat['cutIssues'] = [dict(cut=0, plane=0, squares=[[9, 9]], reason='cut-without-terrain')]
        _, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        self.assertEqual(report['issues'], cat['cutIssues'])

    def test_every_reachable_component_needs_a_name(self):
        cat, anchors, sources, authored = fixture()
        anchors['insetRegionIds'] = []
        anchors['reachableRegionIds'] = ['a', 'b', 'c']
        _, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        self.assertEqual(report['issues'], [dict(componentId='c', reason='required-component-unnamed')])
        anchors['reachableRegionIds'] = ['a']
        _, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        self.assertEqual((report['issues'], report['required']), ([], 1))

    def test_every_reachable_component_needs_an_anchor(self):
        cat, anchors, sources, authored = fixture()
        anchors['regions']['b'] = dict(anchors=[], defaultAnchor=None, status='unresolved')
        anchors['curatedIssues'] = [dict(area='dungeon', componentId='a', point=[1, 1, 0],
                                         reason='curated-anchor-not-into-component')]
        _, report = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        self.assertEqual([i['reason'] for i in report['issues']],
                         ['curated-anchor-not-into-component', 'required-component-unanchored'])
        self.assertEqual(report['issues'][1]['componentId'], 'b')
        self.assertEqual((report['requiredAnchored'], report['required']), (1, 2))
        authored['areas'][0]['components'][0]['anchor'] = dict(point=[1, 1], reason='Dug into')
        with self.assertRaisesRegex(ValueError, 'anchor needs a point and a reason'):
            labels.compile_labels(cat, anchors, sources, authored, 'hash')

    def test_candidates_do_not_promote_nearest_labels_or_cross_planes(self):
        cat, anchors, sources, authored = fixture()
        output, _ = labels.compile_labels(cat, anchors, sources, authored, 'hash')
        work = labels.candidate_worklist(cat, anchors, sources, output, radius=5)
        by_id = {r['componentId']: r for r in work['regions']}
        candidate = by_id['a']['candidates'][0]
        self.assertEqual(candidate['distanceSquared'], 16)  # The centre is in a hole.
        self.assertIsNone(candidate['pinComponentId'])
        self.assertEqual(by_id['b']['candidates'], [])
        self.assertEqual(output['regions']['c']['status'], 'unresolved')

    def test_duplicate_keys_invalid_coordinates_and_stale_anchors_fail(self):
        cat, anchors, sources, authored = fixture()
        authored['areas'] *= 2
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            labels.compile_labels(cat, anchors, sources, authored, 'hash')
        cat, anchors, sources, authored = fixture()
        sources['records'][0]['point'][2] = 4
        with self.assertRaisesRegex(ValueError, 'Invalid source'):
            labels.compile_labels(cat, anchors, sources, authored, 'hash')
        sources['records'][0]['point'][2] = 0
        with self.assertRaisesRegex(ValueError, 'does not match'):
            labels.compile_labels(cat, anchors, sources, authored, 'changed-hash')

    def test_cli_drift_keeps_the_previous_runtime_lookup(self):
        cat, anchors, sources, authored = fixture()
        cat['regions'][0]['id'] = 'changed'
        raw = json.dumps(cat)
        anchors['catalogueSha256'] = hashlib.sha256(raw.encode()).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            for name, value in [('catalogue', cat), ('anchors', anchors), ('sources', sources), ('annotations', authored)]:
                (directory / f'{name}.json').write_text(json.dumps(value))
            output = directory / 'labels.json'
            output.write_text('previous release')
            command = [sys.executable, str(ROOT / 'scripts/label_inset_regions.py')]
            for name in ('catalogue', 'anchors', 'sources', 'annotations'):
                command.extend([f'--{name}', str(directory / f'{name}.json')])
            command.extend(['--output', str(output), '--report', str(directory / 'report.json')])
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(), 'previous release')
            self.assertTrue(json.loads((directory / 'report.json').read_text())['issues'])
            result = subprocess.run(command + ['--allow-unresolved'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            # The name follows its seed into the renamed component.
            self.assertEqual(json.loads(output.read_text())['regions']['changed']['status'], 'named')

    def test_mejrs_snapshot_retains_missing_pins_but_rejects_malformed_rows(self):
        rows = snapshot.mejrs_records(b'100,101,1,A dungeon\n,,0,No pin\n')
        self.assertEqual(rows[0]['point'], [100, 101, 1])
        self.assertIsNone(rows[1]['point'])
        self.assertEqual(rows, snapshot.mejrs_records(b'100,101,1,A dungeon\n,,0,No pin\n'))
        with self.assertRaises(ValueError):
            snapshot.mejrs_records(b'bad,101,1,A dungeon\n')

    def test_reviewed_names_and_anchors_cover_every_required_component(self):
        base = ROOT / 'tests/fixtures'
        raw = (base / 'regions.json').read_bytes()
        catalogue = json.loads(raw)
        anchors = json.loads((base / 'anchors.json').read_text())
        sources = json.loads((ROOT / 'data/inset-labels/sources.json').read_text())
        annotations = json.loads((ROOT / 'data/inset-labels/annotations.json').read_text())
        output, report = labels.compile_labels(catalogue, anchors, sources, annotations, hashlib.sha256(raw).hexdigest())
        self.assertEqual(report['issues'], [])
        reviewed = {association['componentId'] for area in annotations['areas'] for association in area['components']}
        self.assertEqual(set(output['regions']), reviewed)
        self.assertTrue(all(region['status'] != 'unresolved' for region in output['regions'].values()))
        self.assertEqual((output, report), labels.compile_labels(
            catalogue, anchors, sources, annotations, hashlib.sha256(raw).hexdigest()))
        self.assertEqual(output['regions']['p3-3400-9927-cfe6848db0']['name'], 'Slayer Tower basement')
        self.assertEqual(output['regions']['p0-2387-9767-3351540754']['name'], 'Stronghold Slayer Cave')
        self.assertEqual(output['regions']['p0-2820-9730-a3a71dd2a5']['name'], 'Entrana Dungeon')
        # Cuts keep areas that fill their map squares apart.
        self.assertEqual(output['regions']['p0-2826-9539-29b59396ab']['name'], 'Crandor and Karamja Dungeon')
        self.assertEqual(output['regions']['p0-2880-9549-f7e27c8a1b']['name'], 'Asgarnian Ice Dungeon')
        self.assertEqual(output['regions']['p0-2816-4800-39ba9f1de3']['name'], 'Air Altar')
        self.assertEqual(output['regions']['p0-2576-4608-d8353ffb01']['name'], 'Fisher Realm')
        # Reached only by teleports, an unrecognised action, or a one-way chain.
        self.assertEqual(output['regions']['p0-2528-6277-903188b249']['name'], 'Lassar Undercity')
        self.assertEqual(output['regions']['p0-4033-4356-3931b69953']['name'], 'Ancient Guthixian Temple')
        self.assertEqual(output['regions']['p0-2137-4828-4e2af7ffc0']['name'], 'Cosmic Altar')
        self.assertEqual(output['regions']['p0-3153-12354-1cfa6b96c1']['name'], 'Iorwerth Dungeon')
        self.assertEqual(report['requiredAnchored'], report['required'])
        self.assertEqual(report['required'], len(anchors['reachableRegionIds']) + 1)


if __name__ == '__main__':
    unittest.main()
