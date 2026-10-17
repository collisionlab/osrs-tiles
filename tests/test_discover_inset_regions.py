import importlib.util
from pathlib import Path

import unittest

spec = importlib.util.spec_from_file_location('discovery', Path(__file__).parents[1] / 'scripts/discover_inset_regions.py')
discovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(discovery)


def run(spans):
    return discovery.discover(dict(schemaVersion=1, spans=spans, loadedMapSquares=[0],
                                  surfaceRects=[[0, 50, 50, 10, 10]], mapIndexRevision=1))['regions']


def tiles(region):
    return {(x, y) for rx, ry, w, h in region['mask']
            for x in range(rx, rx + w) for y in range(ry, ry + h)}


class DiscoveryTest(unittest.TestCase):
    def test_holes_and_concavities_preserved_in_clip_mask(self):
        spans = [[0, 1, 1, 5], [0, 2, 1, 2], [0, 2, 4, 5], [0, 3, 1, 5], [0, 4, 1, 2]]
        regions = run(spans)
        assert len(regions) == 1
        region = regions[0]
        assert region['bounds'] == [1, 1, 5, 5]
        assert region['tileCount'] == 11
        assert tiles(region) == {(x, y) for _, y, a, b in spans for x in range(a, b)}
        assert (3, 2) not in tiles(region)
        assert (3, 4) not in tiles(region)


    def test_diagonal_contact_gaps_and_planes_remain_separate(self):
        regions = run([[0, 1, 1, 2], [0, 2, 2, 3], [0, 1, 4, 5], [1, 1, 1, 2]])
        assert len(regions) == 4


    def test_bridge_unions_multiple_previous_spans_and_map_square_seams(self):
        regions = run([[0, 1, 62, 64], [0, 1, 64, 65], [0, 1, 66, 67], [0, 2, 62, 67]])
        assert len(regions) == 1
        assert regions[0]['tileCount'] == 9
        assert not regions[0]['touchesUnknown']
        assert run([[0, 0, 1, 2]])[0]['touchesUnknown']


    def test_surface_islands_upper_floors_and_mixed_components(self):
        regions = run([[0, 50, 51, 52], [0, 55, 55, 56], [1, 51, 51, 52], [0, 59, 59, 62]])
        assert sorted(r['classification'] for r in regions) == ['main-world'] * 3 + ['mixed']


    def test_deterministic_across_input_order_and_duplicate_spans(self):
        spans = [[0, 1, 3, 4], [0, 2, 1, 5], [0, 1, 1, 2]]
        assert run(spans) == run(list(reversed(spans)) + [spans[0]])


    def test_endpoint_uses_shape_not_overlapping_bounds(self):
        topology = dict(schemaVersion=1, mapIndexRevision=1, loadedMapSquares=[0],
                        surfaceRects=[[0, 50, 50, 1, 1]],
                        spans=[[0, 1, 1, 6], [0, 2, 1, 2], [0, 3, 1, 2], [0, 4, 1, 2],
                            [0, 5, 1, 6], [0, 2, 5, 6], [0, 3, 5, 6], [0, 4, 5, 6], [0, 3, 3, 4]])
        example = dict(areaName='Inside hole', detachedEndpoint='target', route=dict(target=[3, 3, 0]))
        result = discovery.discover(topology, [example])
        assert len(result['regions']) == 2
        assert result['checks'][0]['status'] == 'matched'
        matched = next(r for r in result['regions'] if r['examples'])
        assert matched['tileCount'] == 1


    def test_missing_coverage_or_invalid_spans_fail(self):
        with self.assertRaises(ValueError):
            run([[0, 0, 2, 1]])
        with self.assertRaises(ValueError):
            discovery.discover(dict(schemaVersion=1, spans=[[0, 0, 1, 2]], surfaceRects=[]))


if __name__ == "__main__":
    unittest.main()
