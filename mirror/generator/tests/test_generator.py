"""Hermetic tests on a synthetic dump:  python3 -I -m unittest discover -s mirror/generator/tests"""
import itertools
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))

from nebgen import archetypes  # noqa: E402
from nebgen.build import Generator  # noqa: E402
from nebgen.util import sanitize_id, variant_key  # noqa: E402
from nebgen.vanilla import vanilla_texture_candidates  # noqa: E402

H = ['north', 'south', 'west', 'east']


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8') as f:
        json.dump(data, f)


class Fixture:
    """Builds a minimal MirrorDumper-shaped dump and a fake 1.21.1 reference directory."""

    def __init__(self, root):
        self.root = root
        self.dump = os.path.join(root, 'dump')
        self.mc = os.path.join(root, 'mc')
        self.overrides = os.path.join(root, 'overrides')
        self.out = os.path.join(root, 'out')
        self.index = []
        self._models = {}

    def model(self, sprite, layer='SOLID', tint=None):
        quad = {'face': 'up', 'sprite': sprite, 'v': [[0, 1, 0, 0, 0], [0, 1, 1, 0, 16], [1, 1, 1, 16, 16],
                                                      [1, 1, 0, 16, 0]]}
        if tint is not None:
            quad['tint'] = tint
        h = ('%040x' % (abs(hash((sprite, layer, tint))) % (1 << 160)))
        _write(os.path.join(self.dump, 'models', h[:2], h + '.json'), {'quads': [quad]})
        return {layer: h}

    def block(self, name, classes, props, meta_fn, valid_states=None, sprite=None, stored=None, items=None,
              drop_fn=None):
        """props: [(name, type, values)]; meta_fn(state)->meta or None for actual-state-only states."""
        names = [p[0] for p in props]
        all_states = [dict(zip(names, vs)) for vs in itertools.product(*[p[2] for p in props])] or [{}]
        valid_states = valid_states or all_states
        meta_table = []
        by_meta = {}
        for s in valid_states:
            m = meta_fn(s)
            if m is not None and m not in by_meta:
                by_meta[m] = s
        for m in range(16):
            if m in by_meta:
                meta_table.append({'meta': m, 'state': by_meta[m], 'roundTrip': m})
            else:
                meta_table.append({'meta': m, 'invalid': 'x'})
        layers = self.model(sprite or (name.split(':')[0] + ':blocks/' + name.split(':')[1]))
        states = [{'props': s, 'meta': meta_fn(s) or 0, 'drop': (drop_fn or (lambda _s: 0))(s), 'light': 0, 'opaqueCube': True, 'fullCube': True,
                   'bounds': [0, 0, 0, 1, 1, 1], 'collision': [0, 0, 0, 1, 1, 1], 'layers': ['SOLID'],
                   'model': {'ao': True, 'particle': sprite or 'missingno', 'layers': layers}}
                  for s in valid_states]
        if stored is None:
            stored = [n for n in names if len({str(by_meta[m][n]) for m in by_meta}) > 1]
        b = {
            'name': name, 'classes': classes + ['net.minecraft.block.Block'], 'translationKey': 'tile.' + name,
            'properties': [{'name': n, 'type': t, 'values': v} for n, t, v in props],
            'defaultState': valid_states[0], 'metaTable': meta_table,
            'storedProperties': stored, 'derivedProperties': [n for n in names if n not in stored],
            'items': items if items is not None else [{'meta': 0, 'translationKey': 'tile.' + name,
                                                       'displayName': name.split(':')[1]}],
            'stateCount': len(states), 'modelsDumped': True, 'states': states,
        }
        ns, path = name.split(':')
        rel = 'blocks/%s/%s.json' % (ns, path)
        _write(os.path.join(self.dump, rel), b)
        self.index.append({'name': name, 'file': rel, 'states': len(states)})

    def finish(self, overrides=None):
        _write(os.path.join(self.dump, 'manifest.json'), {'schema': 1, 'mods': [{'id': 'testmod', 'version': '1'}]})
        _write(os.path.join(self.dump, 'blocks', 'index.json'), self.index)
        _write(os.path.join(self.dump, 'sprites.json'), {})
        _write(os.path.join(self.dump, 'biomes.json'), {})
        os.makedirs(os.path.join(self.dump, 'textures', 'testmod', 'blocks'), exist_ok=True)
        for t in ('deco', 'rail_fence', 'brick_stairs', 'old_wall', 'weird_name'):
            open(os.path.join(self.dump, 'textures', 'testmod', 'blocks', t + '.png'), 'wb').close()
        os.makedirs(os.path.join(self.dump, 'lang', 'testmod'), exist_ok=True)
        with open(os.path.join(self.dump, 'lang', 'testmod', 'zh_cn.lang'), 'w', encoding='utf8') as f:
            f.write('tile.testmod:deco.name=装饰\n')
        # fake 1.21.1 reference: the archetype reference blocks plus override targets
        blocks = {}
        for a in archetypes.ARCHETYPES:
            blocks[a.vanilla_reference] = {'properties': {p.name: p.values for p in a.props},
                                           'states': [{'default': True}]}
        blocks['minecraft:smooth_stone'] = {'states': [{'default': True}]}
        blocks['minecraft:stripped_spruce_log'] = {'properties': {'axis': ['x', 'y', 'z']},
                                                   'states': [{'default': True, 'properties': {'axis': 'y'}}]}
        _write(os.path.join(self.mc, 'generated', 'reports', 'blocks.json'), blocks)
        with open(os.path.join(self.mc, 'textures-block.txt'), 'w') as f:
            f.write('block/black_concrete\nblock/stone\n')
        _write(os.path.join(self.overrides, 'test.json'), {'blocks': overrides or {}})

    def run(self):
        return Generator(self.dump, self.mc, self.overrides, self.out).run()

    def read(self, rel):
        with open(os.path.join(self.out, rel), encoding='utf8') as f:
            return json.load(f)


def standard_fixture(root, overrides=None):
    fx = Fixture(root)
    fx.block('testmod:deco', ['x.Deco'],
             [('facing', 'direction', H), ('subtype', 'enum', ['subtype0', 'subtype1'])],
             lambda s: H.index(s['facing']) + 4 * int(s['subtype'][-1]),
             drop_fn=lambda s: int(s['subtype'][-1]),
             items=[{'meta': 0, 'translationKey': 'tile.testmod:deco', 'displayName': 'deco'},
                    {'meta': 1, 'translationKey': 'tile.testmod:deco.b', 'displayName': 'deco b'}])
    conn = [(d, 'bool', ['true', 'false']) for d in ('north', 'east', 'south', 'west')]
    fx.block('testmod:rail_fence', ['x.RailFence', 'net.minecraft.block.BlockPane'],
             conn + [('color', 'enum', ['red', 'blue'])],
             lambda s: ['red', 'blue'].index(s['color']) if all(s[d] == 'false' for d in
                                                                 ('north', 'east', 'south', 'west')) else None)
    fx.block('testmod:brick_stairs', ['net.minecraft.block.BlockStairs'],
             [('facing', 'direction', H), ('half', 'enum', ['top', 'bottom']),
              ('shape', 'enum', ['straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right'])],
             lambda s: (H.index(s['facing']) + 4 * ['bottom', 'top'].index(s['half']))
             if s['shape'] == 'straight' else None,
             sprite='minecraft:blocks/concrete_black')
    fx.block('testmod:old_wall', ['net.minecraft.block.BlockWall'],
             [('up', 'bool', ['true', 'false'])] + conn + [('variant', 'enum', ['plain'])],
             lambda s: 0 if s['up'] == 'true' and all(s[d] == 'false' for d in
                                                       ('north', 'east', 'south', 'west')) else None)
    fx.block('testmod:smooth', ['x.Smooth'], [], lambda s: 0)
    fx.block('testmod:log', ['x.Log'], [('axis', 'enum', ['y', 'x', 'z'])],
             lambda s: {'y': 0, 'x': 4, 'z': 8}[s['axis']])
    fx.block('testmod:gone', ['x.Gone'], [], lambda s: 0)
    fx.block('testmod:Weird_Name', ['x.Weird'], [], lambda s: 0, sprite='testmod:blocks/weird_name')
    base = {
        'testmod:smooth': {'target': 'minecraft:smooth_stone'},
        'testmod:log': {'target': 'minecraft:stripped_spruce_log', 'props': {'axis': 'axis'}},
        'testmod:gone': {'unknown': True},
    }
    base.update(overrides or {})
    fx.finish(base)
    return fx


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.fx = standard_fixture(self.tmp.name)
        self.report = self.fx.run()
        self.blocks = {b['id']: b for b in self.fx.read('mirror/blocks.json')['blocks']}
        self.forward = self.fx.read('mirror/forward.json')['blocks']
        self.reverse = self.fx.read('mirror/reverse.json')['blocks']

    def tearDown(self):
        self.tmp.cleanup()

    def test_no_errors(self):
        self.assertEqual(self.report.errors, [])

    def test_generic_block_keeps_meta_encoding(self):
        fw = self.forward['testmod:deco']['metas']
        self.assertEqual(fw['5'], {'facing': 'south', 'subtype': 'subtype1'})
        self.assertEqual(self.blocks['testmod:deco']['archetype'], 'generic')
        rev = self.reverse['testmod:deco']
        self.assertEqual(rev['keys'], ['facing', 'subtype'])
        self.assertEqual(rev['map']['facing=south,subtype=subtype1'], ['testmod:deco', 5])
        kinds = {p['name']: p['kind'] for p in self.blocks['testmod:deco']['properties']}
        self.assertEqual(kinds, {'facing': 'direction', 'subtype': 'enum'})

    def test_item_subtype_uses_damage_dropped_not_block_meta(self):
        items = {i['meta']: i for i in self.blocks['testmod:deco']['items']}
        # block meta 1 is facing=south/subtype0; item damage 1 must place subtype1 (default facing)
        self.assertEqual(items[1]['state'], {'facing': 'north', 'subtype': 'subtype1'})
        self.assertEqual(items[0]['state'], {'facing': 'north', 'subtype': 'subtype0'})

    def test_pane_archetype_covers_all_connections(self):
        b = self.blocks['testmod:rail_fence']
        self.assertEqual(b['archetype'], 'pane')
        self.assertEqual(len(b['states']), 16 * 2)
        bs = self.fx.read('assets/testmod/blockstates/rail_fence.json')['variants']
        self.assertIn('color=blue,east=true,north=false,south=true,west=false', bs)
        # connections are derived: not part of the reverse key
        self.assertEqual(self.reverse['testmod:rail_fence']['keys'], ['color'])
        self.assertEqual(self.forward['testmod:rail_fence']['metas']['1'],
                         {'north': 'false', 'east': 'false', 'south': 'false', 'west': 'false', 'color': 'blue'})

    def test_stairs_shape_is_derived(self):
        rev = self.reverse['testmod:brick_stairs']
        self.assertEqual(rev['keys'], ['facing', 'half'])
        self.assertEqual(self.forward['testmod:brick_stairs']['metas']['6']['shape'], 'straight')
        self.assertEqual(len(self.blocks['testmod:brick_stairs']['states']), 4 * 2 * 5)

    def test_wall_maps_low_and_tall_to_connected(self):
        b = self.blocks['testmod:old_wall']
        self.assertEqual(b['archetype'], 'wall')
        self.assertEqual(len(b['states']), 2 * 3 ** 4)
        bs = self.fx.read('assets/testmod/blockstates/old_wall.json')['variants']
        low = bs['east=none,north=low,south=none,up=true,variant=plain,west=none']
        tall = bs['east=none,north=tall,south=none,up=true,variant=plain,west=none']
        self.assertEqual(low, tall)

    def test_override_to_vanilla(self):
        self.assertEqual(self.forward['testmod:smooth'], {'target': 'minecraft:smooth_stone', 'metas': {'0': {}},
                                                          'override': True})
        self.assertNotIn('testmod:smooth', self.blocks)
        log = self.forward['testmod:log']['metas']
        self.assertEqual(log, {'0': {'axis': 'y'}, '4': {'axis': 'x'}, '8': {'axis': 'z'}})
        self.assertEqual(self.reverse['minecraft:stripped_spruce_log']['map']['axis=z'], ['testmod:log', 8])

    def test_unknown_override_generates_nothing(self):
        self.assertNotIn('testmod:gone', self.forward)
        self.assertNotIn('testmod:gone', self.blocks)

    def test_ids_are_sanitized(self):
        self.assertIn('testmod:weird_name', self.blocks)
        self.assertEqual(self.forward['testmod:Weird_Name']['target'], 'testmod:weird_name')

    def test_vanilla_texture_uses_121_asset(self):
        meshes = os.path.join(self.fx.out, 'assets', 'nebulaemirror', 'meshes')
        sprites = set()
        for f in os.listdir(meshes):
            with open(os.path.join(meshes, f)) as fh:
                sprites |= {q['sprite'] for q in json.load(fh)['quads']}
        self.assertIn('minecraft:block/black_concrete', sprites)
        self.assertIn('testmod:block/deco', sprites)
        self.assertTrue(os.path.exists(os.path.join(self.fx.out, 'assets', 'testmod', 'textures', 'block', 'deco.png')))

    def test_lang_from_legacy_files(self):
        zh = self.fx.read('assets/testmod/lang/zh_cn.json')
        self.assertEqual(zh['block.testmod.deco'], '装饰')


class OverrideErrorTest(unittest.TestCase):
    def test_invalid_override_value_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = standard_fixture(tmp, {'testmod:log': {'target': 'minecraft:stripped_spruce_log',
                                                        'props': {'axis': 'axis'},
                                                        'values': {'axis': {'x': 'sideways'}}}})
            report = fx.run()
            self.assertTrue(any('no value sideways' in e for e in report.errors), report.errors)

    def test_refuses_to_clear_foreign_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            fx = standard_fixture(tmp)
            os.makedirs(fx.out)
            open(os.path.join(fx.out, 'precious.txt'), 'w').close()
            with self.assertRaises(SystemExit):
                fx.run()


class UnitTest(unittest.TestCase):
    def test_vanilla_texture_candidates(self):
        self.assertEqual(vanilla_texture_candidates('concrete_silver')[0], 'light_gray_concrete')
        self.assertEqual(vanilla_texture_candidates('planks_big_oak')[0], 'dark_oak_planks')
        self.assertEqual(vanilla_texture_candidates('hardened_clay_stained_cyan')[0], 'cyan_terracotta')
        self.assertEqual(vanilla_texture_candidates('stonebrick_mossy')[0], 'mossy_stone_bricks')
        self.assertEqual(vanilla_texture_candidates('iron_block'), ['iron_block'])

    def test_sanitize_and_variant_key(self):
        self.assertEqual(sanitize_id('chisel:Planks-Spruce'), 'chisel:planks-spruce')
        self.assertEqual(sanitize_id('Mod:a b'), 'mod:a_b')
        self.assertEqual(variant_key({'b': '1', 'a': '2'}), 'a=2,b=1')


if __name__ == '__main__':
    unittest.main()
