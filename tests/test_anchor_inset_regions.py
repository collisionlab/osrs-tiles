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

    def test_teleports_travel_networks_and_unknown_actions_do_not_anchor(self):
        rows = [transport([10, 10, 0], [100, 100, 0], 'Enter Portal 1'),
                transport([10, 10, 0], [100, 100, 0], 'Climb-down Ladder 1', file='minecarts.tsv'),
                transport(None, [100, 100, 0], 'Teleport', file='teleportation_spells.tsv'),
                transport([10, 10, 0], [100, 100, 0], 'Travel Stranger 1')]
        result = anchors.build_anchors(catalogue(), rows)
        assert result['regions']['a']['status'] == 'unresolved'
        assert result['insetRegionIds'] == []
        assert len(result['regions']['a']['connectionIds']) == 4
        assert result['summary']['non-localConnections'] == 3
        assert result['summary']['reviewConnections'] == 1

    def test_hole_endpoint_does_not_snap_or_anchor_neighbouring_component(self):
        data = catalogue()
        data['regions'][1]['mask'] = [[100, 100, 1, 4], [101, 103, 3, 1]]
        result = anchors.build_anchors(data, [transport([10, 10, 0], [102, 102, 0])])
        assert result['regions']['a']['status'] == 'unresolved'
        connection = next(iter(result['connections'].values()))
        assert connection['target']['kind'] == 'unknown'
        assert connection['policy'] == 'review'
        assert connection['target']['regionId'] is None

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
