"""CTM 1.12 -> Continuity conversion on synthetic sheets:  python3 -I -m unittest discover -s mirror/generator/tests"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))

from nebgen.ctm import CtmConverter  # noqa: E402
from nebgen.png import read_png, write_png  # noqa: E402


class _Dump:
    def __init__(self, root):
        self.root = root

    def texture_file(self, sprite):
        ns, path = sprite.split(':', 1)
        return os.path.join(self.root, 'textures', ns, path + '.png')


class _Report:
    def __init__(self):
        self.counts, self.items, self.warnings = {}, {}, []

    def count(self, k, n=1):
        self.counts[k] = self.counts.get(k, 0) + n

    def item(self, k, v):
        self.items.setdefault(k, []).append(v)

    def warn(self, *a):
        self.warnings.append(a)


def _sheet(path, cols, rows, tile=4, frames=1):
    """Each tile is filled with its row-major index as the red channel, so crops are identifiable."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = []
    for _ in range(frames):
        for y in range(rows * tile):
            r = bytearray()
            for x in range(cols * tile):
                r += bytes(((y // tile) * cols + x // tile, 0, 0, 255))
            out.append(bytes(r))
    write_png(path, cols * tile, rows * tile * frames, out)


class CtmTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.assets = os.path.join(self.root, 'assets')
        self.report = _Report()
        self.conv = CtmConverter(_Dump(os.path.join(self.root, 'dump')), self.assets, self.report)

    def tearDown(self):
        self.tmp.cleanup()

    def _tex(self, sprite, cols, rows, ctm, frames=1, animation=None):
        f = _Dump(os.path.join(self.root, 'dump')).texture_file(sprite)
        _sheet(f, cols, rows, frames=frames)
        meta = {'ctm': ctm}
        if animation:
            meta['animation'] = animation
        with open(f + '.mcmeta', 'w') as fh:
            json.dump(meta, fh)

    def _props(self, name):
        path = os.path.join(self.assets, 'nebulaemirror', 'optifine', 'ctm', name + '.properties')
        with open(path) as f:
            return dict(line.split('=', 1) for line in f.read().splitlines() if '=' in line and not line.startswith('#'))

    def _tile_value(self, tile_id):
        ns, path = tile_id.split(':', 1)
        _, _, rows = read_png(os.path.join(self.assets, ns, 'textures', path + '.png'))
        return rows[0][0]

    def test_ctm_compact_uses_base_then_sheet_quadrants(self):
        self._tex('chisel:blocks/x/dent', 1, 1, {'type': 'ctm', 'textures': ['chisel:blocks/x/dent-ctm']})
        _sheet(_Dump(os.path.join(self.root, 'dump')).texture_file('chisel:blocks/x/dent-ctm'), 2, 2)
        self.conv.convert({'chisel:blocks/x/dent': 'chisel:block/x/dent'})
        p = self._props('chisel/x_dent')
        self.assertEqual(p['method'], 'ctm_compact')
        self.assertEqual(p['matchTiles'], 'chisel:block/x/dent')
        self.assertEqual(p['connect'], 'state')
        tiles = p['tiles'].split()
        self.assertEqual(tiles[0], 'chisel:block/x/dent')
        # CTMLogic: TL full, TR vertical, BL horizontal, BR inner corners == Continuity tiles 1..4.
        self.assertEqual([self._tile_value(t) for t in tiles[1:]], [0, 1, 2, 3])

    def test_ctmh_tile_order(self):
        self._tex('chisel:blocks/x/h', 2, 2, {'type': 'CTMH'})
        self.conv.convert({'chisel:blocks/x/h': 'chisel:block/x/h'})
        p = self._props('chisel/x_h')
        self.assertEqual(p['method'], 'horizontal')
        # Continuity: right only, both, left only, none <- CTMH: BL, TR, BR, TL.
        self.assertEqual([self._tile_value(t) for t in p['tiles'].split()], [2, 1, 3, 0])

    def test_pattern_phase_per_face(self):
        self._tex('chisel:blocks/x/p', 2, 2, {'type': 'pattern', 'extra': {'size': 2}})
        self.conv.convert({'chisel:blocks/x/p': 'chisel:block/x/p'})
        ns = self._props('chisel/x_p_ns')
        self.assertEqual((ns['method'], ns['faces'], ns['width']), ('repeat', 'north south', '2'))
        self.assertEqual([self._tile_value(t) for t in ns['tiles'].split()], [0, 1, 2, 3])
        top = self._props('chisel/x_p_top')  # CTM y index = Continuity + 1
        self.assertEqual([self._tile_value(t) for t in top['tiles'].split()], [2, 3, 0, 1])
        west = self._props('chisel/x_p_west')  # CTM x index = Continuity + 1
        self.assertEqual([self._tile_value(t) for t in west['tiles'].split()], [1, 0, 3, 2])

    def test_random_and_animated_sheet(self):
        anim = {'frametime': 4}
        self._tex('chisel:blocks/x/r', 3, 3, {'type': 'random', 'extra': {'size': 3}}, frames=2, animation=anim)
        self.conv.convert({'chisel:blocks/x/r': 'chisel:block/x/r'})
        p = self._props('chisel/x_r')
        tiles = p['tiles'].split()
        self.assertEqual((p['method'], len(tiles)), ('random', 9))
        ns, path = tiles[4].split(':', 1)
        f = os.path.join(self.assets, ns, 'textures', path + '.png')
        w, h, _ = read_png(f)
        self.assertEqual((w, h), (4, 8))  # both frames kept
        with open(f + '.mcmeta') as fh:
            self.assertEqual(json.load(fh), {'animation': anim})

    def test_block_prefix_is_not_used_as_file_name(self):
        self._tex('chisel:blocks/stone', 2, 2, {'type': 'AR'})
        self.conv.convert({'chisel:blocks/stone': 'chisel:block/stone'})
        d = os.path.join(self.assets, 'nebulaemirror', 'optifine', 'ctm', 'chisel')
        self.assertEqual(os.listdir(d), ['stone.properties'])

    def test_emissive_light(self):
        self._tex('chisel:blocks/x/glow', 1, 1, {'extra': {'light': 15}})
        self._tex('chisel:blocks/x/dim', 1, 1, {'extra': {'light': 8}})
        self.assertTrue(self.conv.emissive('chisel:blocks/x/glow'))
        self.assertFalse(self.conv.emissive('chisel:blocks/x/dim'))
        self.assertFalse(self.conv.emissive('chisel:blocks/x/none'))


if __name__ == '__main__':
    unittest.main()
