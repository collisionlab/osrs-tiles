import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('anchors', Path(__file__).parents[1] / 'scripts/anchor_inset_regions.py')
anchors = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anchors)


def catalogue():
    return dict(schemaVersion=1, topologySha256='test', surfaceRects=[[0, 0, 0, 64, 64]], regions=[
        dict(id='surface', plane=0, bounds=[0, 0, 64, 64], classification='mixed', mask=[[0, 0, 64, 64]]),
        dict(id='a', plane=0, bounds=[100, 100, 104, 104], classification='inset', mask=[[100, 100, 4, 4]]),
        dict(id='b', plane=1, bounds=[100, 100, 104, 104], classification='inset', mask=[[100, 100, 4, 4]]),
        dict(id='c', plane=0, bounds=[200, 200, 204, 204], classification='inset', mask=[[200, 200, 4, 4]]),
    ])


def transport(source, target, action='Climb-down Ladder 1', file='transports.tsv', line=2, conditions=None):
    return dict(source=source, target=target, action=action, file=file, line=line,
                conditions=conditions or {}, label='', duration='2')


class AnchorTest(unittest.TestCase):
    def test_direct_indirect_multiple_entrances_and_cycles(self):
        rows = [transport([10, 10, 0], [100, 100, 0]),
                transport([20, 20, 0], [100, 101, 0]),
                transport([100, 100, 0], [100, 100, 1]),
                transport([100, 100, 1], [200, 200, 0]),
                transport([200, 200, 0], [100, 101, 0])]
        result = anchors.build_anchors(catalogue(), rows)
        assert result['regions']['a']['status'] == 'direct'
        assert result['regions']['b']['status'] == 'indirect'
        assert [a['point'] for a in result['regions']['b']['anchors']] == [[10, 10, 0], [20, 20, 0]]
        assert all(a['hops'] == 2 for a in result['regions']['b']['anchors'])
        assert len(result['regions']['c']['anchors']) == 2
        assert result['insetRegionIds'] == ['a', 'b', 'c']
        assert result == anchors.build_anchors(catalogue(), list(reversed(rows)))

    def test_exit_only_keeps_original_direction_and_requirements(self):
        row = transport([100, 100, 0], [10, 10, 0], 'Climb-up Ladder 1', conditions={'Quests': 'Example quest'})
        result = anchors.build_anchors(catalogue(), [row])
        anchor = result['regions']['a']['anchors'][0]
        assert anchor['path'][0]['reversed']
        connection = result['connections'][anchor['path'][0]['connectionId']]
        assert connection['source']['point'] == [100, 100, 0]
        assert connection['target']['point'] == [10, 10, 0]
        assert connection['conditions'] == {'Quests': 'Example quest'}

    def test_sourced_transports_anchor_in_their_own_direction_but_sourceless_seasonal_and_hubs_do_not(self):
        rows = [transport([10, 10, 0], [100, 100, 0], 'Enter Portal 1', file='teleportation_portals.tsv'),
                transport([10, 10, 0], [100, 100, 1], 'Attract Light creature 1'),
                transport(None, [200, 200, 0], 'Teleport', file='teleportation_spells.tsv'),
                transport([20, 20, 0], [200, 200, 0], 'Teleport Wizard', file='seasonal_transports.tsv')]
        rows += [transport([30, 30, 0], [200, 200, 0], 'A B C', file='fairy_rings.tsv'),
                 transport([30, 30, 0], [40, 40, 0], 'A B D', file='fairy_rings.tsv'),
                 transport([30, 30, 0], [41, 41, 0], 'A B E', file='fairy_rings.tsv')]
        result = anchors.build_anchors(catalogue(), rows)
        assert result['regions']['a']['anchors'][0]['point'] == [10, 10, 0]
        assert result['regions']['a']['anchors'][0]['indirectConnections'] == 1
        assert result['regions']['b']['status'] == 'direct'
        assert result['regions']['c']['status'] == 'unresolved'
        assert result['insetRegionIds'] == ['a', 'b']
        # Only a physical connection counts backwards: an exit portal says nothing about where an area is.
        exit_only = anchors.build_anchors(catalogue(), [transport([100, 100, 0], [10, 10, 0], 'Exit Portal 1')])
        assert exit_only['regions']['a']['status'] == 'unresolved'

    def test_a_physical_entrance_beats_any_chain_through_a_transport(self):
        rows = [transport([10, 10, 0], [100, 100, 0], 'Enter Portal 1', file='teleportation_portals.tsv'),
                transport([20, 20, 0], [200, 200, 0]),
                transport([200, 200, 0], [201, 201, 0]),
                transport([201, 201, 0], [100, 101, 0])]
        region = anchors.build_anchors(catalogue(), rows)['regions']['a']
        assert [a['point'] for a in region['anchors']] == [[20, 20, 0]]
        assert (region['anchors'][0]['hops'], region['anchors'][0]['indirectConnections']) == (2, 0)

    def test_curated_anchors_place_areas_from_the_surface_or_another_area(self):
        annotations = dict(areas=[
            dict(key='a', components=[dict(componentId='a', seed=[101, 101, 0],
                                           anchor=dict(point=[5, 5, 0], reason='Dug into'))]),
            dict(key='c', components=[dict(componentId='c', seed=[201, 201, 0],
                                           anchor=dict(point=[101, 101, 0], reason='Through a rift'))])])
        rows = [transport([100, 100, 0], [100, 100, 1])]
        result = anchors.build_anchors(catalogue(), rows, annotations)
        a, b, c = (result['regions'][r]['anchors'][0] for r in 'abc')
        assert (a['point'], a['hops'], a['curated']) == ([5, 5, 0], 1, True)
        assert (b['point'], b['hops']) == ([5, 5, 0], 2)
        assert (c['point'], c['hops']) == ([5, 5, 0], 2)
        connection = result['connections'][c['path'][-1]['connectionId']]
        assert (connection['policy'], connection['target']['point']) == ('curated', [201, 201, 0])
        assert result['reachableRegionIds'] == []  # placement, not a way in
        assert result['summary']['curatedAnchors'] == 2
        # A curated anchor wins a tie with a physical one, and the shorter one otherwise.
        rows.append(transport([10, 10, 0], [100, 100, 0]))
        assert anchors.build_anchors(catalogue(), rows, annotations)['regions']['a']['anchors'][0]['point'] == [5, 5, 0]

    def test_only_ways_in_are_entrances(self):
        # A walked chain from the surface is an entrance, whatever its first step.
        rows = [transport([5, 5, 0], [100, 100, 0], 'Enter Portal 1'), transport([100, 100, 0], [100, 100, 1])]
        result = anchors.build_anchors(catalogue(), rows)
        assert [result['regions'][r]['anchors'][0]['entrance'] for r in 'ab'] == [True, True]
        # Teleporting on from inside another area is not.
        rows.append(transport([101, 101, 0], [200, 200, 0], 'Exit-through Rift 1'))
        assert anchors.build_anchors(catalogue(), rows)['regions']['c']['anchors'][0]['entrance'] is False
        # A curated anchor is an entrance only when it says so, and so are the areas walked into from it.
        for flag in (True, False):
            annotations = dict(areas=[dict(key='a', components=[dict(componentId='a', seed=[101, 101, 0],
                anchor=dict(point=[5, 5, 0], reason='Dug into', entrance=flag))])])
            result = anchors.build_anchors(catalogue(), [transport([100, 100, 0], [100, 100, 1])], annotations)
            assert [result['regions'][r]['anchors'][0]['entrance'] for r in 'ab'] == [flag, flag]

    def test_a_curated_anchor_that_misses_its_area_needs_review(self):
        annotations = dict(areas=[dict(key='a', components=[dict(componentId='a', seed=[200, 200, 0],
                                                                 anchor=dict(point=[5, 5, 0], reason='Dug into'))])])
        result = anchors.build_anchors(catalogue(), [], annotations)
        assert result['regions']['a']['status'] == 'unresolved'
        assert [i['reason'] for i in result['curatedIssues']] == ['curated-anchor-not-into-component']

    def test_reachability_follows_every_row_in_its_own_direction(self):
        rows = [transport(None, [200, 200, 0], 'Teleport', file='teleportation_spells.tsv'),
                transport([200, 200, 0], [100, 100, 1], 'Talk-to Guide 1'),
                transport([100, 100, 0], [10, 10, 0], 'Exit Portal 1')]
        result = anchors.build_anchors(catalogue(), rows)
        assert result['reachableRegionIds'] == ['b', 'c']  # a can only be left
        assert result['insetRegionIds'] == []
        assert result['summary']['reachableRegions'] == 2
        rows.append(transport([10, 10, 0], [100, 100, 0], 'Enter Portal 2'))
        assert anchors.build_anchors(catalogue(), rows)['reachableRegionIds'] == ['a', 'b', 'c']

    def test_an_endpoint_off_the_terrain_snaps_only_to_the_one_area_nearby(self):
        data = catalogue()
        data['regions'][1]['mask'] = [[100, 100, 1, 4], [101, 103, 3, 1]]
        result = anchors.build_anchors(data, [transport([10, 10, 0], [102, 102, 0])])
        assert result['regions']['a']['status'] == 'direct'
        connection = next(iter(result['connections'].values()))
        assert (connection['target']['regionId'], connection['target']['membership']) == ('a', 'snapped')
        assert connection['policy'] == 'local'
        data['regions'].append(dict(id='d', plane=0, bounds=[104, 102, 105, 103], classification='inset', mask=[[104, 102, 1, 1]]))
        result = anchors.build_anchors(data, [transport([10, 10, 0], [102, 102, 0])])
        assert result['regions']['a']['status'] == 'unresolved'
        connection = next(iter(result['connections'].values()))
        assert (connection['target']['kind'], connection['policy']) == ('unknown', 'review')
        assert anchors.RegionIndex(data).endpoint([110, 110, 0])['membership'] == 'unmatched'

    def test_surface_footprint_resolves_missing_surface_terrain_without_treating_mixed_as_world(self):
        data = catalogue()
        data['regions'][0]['mask'] = [[0, 0, 1, 1]]
        index = anchors.RegionIndex(data)
        assert index.endpoint([10, 10, 2])['membership'] == 'surface-footprint'
        result = anchors.build_anchors(data, [transport([10, 10, 2], [100, 100, 0])])
        assert result['regions']['a']['anchors'][0]['point'] == [10, 10, 2]
        data['regions'][0]['mask'] = [[0, 0, 80, 80]]
        assert anchors.RegionIndex(data).endpoint([70, 70, 0])['kind'] == 'unknown'

    def test_exact_plane_and_separate_floor_links(self):
        result = anchors.build_anchors(catalogue(), [transport([10, 10, 0], [100, 100, 1])])
        assert result['regions']['a']['status'] == 'unresolved'
        assert result['regions']['b']['status'] == 'direct'

    def test_all_alternative_direct_transports_retained_as_evidence(self):
        rows = [transport([10, 10, 0], [100, 100, 0]),
                transport([10, 10, 0], [100, 100, 0], conditions={'Items': 'KEY=1'})]
        result = anchors.build_anchors(catalogue(), rows)
        assert len(result['connections']) == 2
        assert len(result['regions']['a']['anchors']) == 1
        assert len(result['regions']['a']['connectionIds']) == 2

    def test_invalid_masks_and_missing_surface_coverage_fail(self):
        data = catalogue()
        del data['surfaceRects']
        with self.assertRaises(ValueError):
            anchors.RegionIndex(data)
        data = catalogue()
        duplicate = copy.deepcopy(data['regions'][1])
        duplicate['id'] = 'overlap'
        data['regions'].append(duplicate)
        with self.assertRaises(ValueError):
            anchors.RegionIndex(data)

    def test_reader_keeps_source_lines_and_rejects_invalid_coordinates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'transports.tsv'
            path.write_text('# Origin\tDestination\tmenuOption menuTarget objectID\tQuests\n'
                            '# Cave\n10 10 0\t100 100 0\tEnter Cave 1\tA quest\n')
            rows, digest = anchors.read_transports(Path(directory))
            assert rows[0]['line'] == 3
            assert rows[0]['conditions'] == {'Quests': 'A quest'}
            assert len(digest) == 64
            path.write_text(path.read_text().replace('100 100 0', '100 100 4'))
            with self.assertRaises(ValueError):
                anchors.read_transports(Path(directory))

    def test_reader_ignores_account_metadata_but_rejects_unknown_transport_schemas(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'transports.tsv').write_text('Origin\tDestination\n10 10 0\t100 100 0\n')
            expected = anchors.read_transports(root)
            (root / 'teleport_restrictions.tsv').write_text('familyName\tmemberItemIds\nCloaks\t13121\n')
            self.assertEqual(anchors.read_transports(root), expected)
            (root / 'broken.tsv').write_text('Unexpected\tSchema\n')
            with self.assertRaisesRegex(ValueError, 'missing Destination'):
                anchors.read_transports(root)


if __name__ == '__main__':
    unittest.main()
