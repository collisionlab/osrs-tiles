import json
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
script = runpy.run_path(str(ROOT / 'scripts/collision-tiles'))


def bitset(bits):
    """java.util.BitSet.toByteArray: least significant bit first, trailing zero bytes dropped."""
    data = bytearray(max(bits) // 8 + 1)
    for bit in bits:
        data[bit // 8] |= 1 << (bit % 8)
    return bytes(data)


def bit(plane, x, y, flag):
    return ((plane * 64 + y) * 64 + x) * 4 + flag


class CollisionTilesTest(unittest.TestCase):
    def test_splits_squares_into_plane_tiles_at_image_tile_addresses(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            squares, out = root / 'squares', root / 'out'
            squares.mkdir()
            # Square 50_53: walkable tiles on planes 0 and 2 only; plane 2's last byte is the last
            # byte of the file, so plane 3 is missing entirely (trimmed, not written).
            (squares / '50_53').write_bytes(bitset([bit(0, 1, 2, 0), bit(2, 63, 63, 3)]))
            source = dict(repository='owner/tooling', rev='abc')
            manifest = script['build'](squares, out, '2740', 'f' * 40, source)

            plane0 = (out / '8/35/34.bin').read_bytes()
            self.assertEqual(len(plane0), 2048)
            north = bit(0, 1, 2, 0)
            self.assertEqual(plane0[north // 8], 1 << north % 8)
            plane2 = (out / 'p2/8/35/34.bin').read_bytes()
            self.assertEqual(plane2[-1], 0x80)
            self.assertFalse((out / 'p1').exists())
            self.assertFalse((out / 'p3').exists())

            self.assertEqual([p['mapSquares'] for p in manifest['planes']], [1, 0, 1, 0])
            self.assertEqual(manifest['tiles'], 2)
            self.assertEqual(manifest['cacheId'], 2740)
            self.assertEqual(manifest['dumperSource'], source)
            self.assertEqual(json.loads((out / 'manifest.json').read_text()), manifest)
            self.assertEqual(sorted(json.loads((out / 'index.json').read_text())), ['8/35/34.bin', 'p2/8/35/34.bin'])

    def test_reads_the_pinned_dumper_source_from_the_flake_lock(self):
        source = script['dumper_source'](ROOT / 'flake.lock')
        self.assertEqual(source['repository'], 'osrs-pathfinding/shortest-path-tooling')
        self.assertRegex(source['rev'], '^[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main()
