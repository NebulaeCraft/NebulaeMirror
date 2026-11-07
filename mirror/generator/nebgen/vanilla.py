"""1.21.1 reference data (block report from the server data generator, texture list from the client jar)."""
import os
import re

from .util import read_json


class Vanilla121:
    def __init__(self, mc_dir):
        self.blocks = read_json(os.path.join(mc_dir, 'generated', 'reports', 'blocks.json'))
        with open(os.path.join(mc_dir, 'textures-block.txt'), encoding='utf8') as f:
            self.textures = {line.strip() for line in f if line.strip()}

    def has_block(self, block_id):
        return block_id in self.blocks

    def properties(self, block_id):
        return self.blocks[block_id].get('properties', {})

    def default_state(self, block_id):
        for s in self.blocks[block_id]['states']:
            if s.get('default'):
                return s.get('properties', {})
        return {}

    def has_texture(self, path):
        return path in self.textures


_COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'silver', 'cyan',
           'purple', 'blue', 'brown', 'green', 'red', 'black']
_WOODS = {'oak': 'oak', 'spruce': 'spruce', 'birch': 'birch', 'jungle': 'jungle', 'acacia': 'acacia',
          'big_oak': 'dark_oak'}

# 1.12 "blocks/<name>" -> 1.21 "block/<name>" for names the patterns below don't cover.
_EXPLICIT = {
    'stonebrick': 'stone_bricks', 'stonebrick_mossy': 'mossy_stone_bricks',
    'stonebrick_cracked': 'cracked_stone_bricks', 'stonebrick_carved': 'chiseled_stone_bricks',
    'brick': 'bricks', 'nether_brick': 'nether_bricks', 'red_nether_brick': 'red_nether_bricks',
    'end_bricks': 'end_stone_bricks',
    'stone_slab_top': 'smooth_stone', 'stone_slab_side': 'smooth_stone_slab_side',
    'sandstone_normal': 'sandstone', 'sandstone_smooth': 'sandstone_top', 'sandstone_carved': 'chiseled_sandstone',
    'red_sandstone_normal': 'red_sandstone', 'red_sandstone_smooth': 'red_sandstone_top',
    'red_sandstone_carved': 'chiseled_red_sandstone',
    'cobblestone_mossy': 'mossy_cobblestone',
    'stone_andesite': 'andesite', 'stone_andesite_smooth': 'polished_andesite',
    'stone_granite': 'granite', 'stone_granite_smooth': 'polished_granite',
    'stone_diorite': 'diorite', 'stone_diorite_smooth': 'polished_diorite',
    'prismarine_rough': 'prismarine', 'prismarine_dark': 'dark_prismarine',
    'quartz_block_chiseled': 'chiseled_quartz_block', 'quartz_block_chiseled_top': 'chiseled_quartz_block_top',
    'quartz_block_lines': 'quartz_pillar', 'quartz_block_lines_top': 'quartz_pillar_top',
    'quartz_ore': 'nether_quartz_ore',
    'hardened_clay': 'terracotta',
    'grass_top': 'grass_block_top', 'grass_side': 'grass_block_side',
    'grass_side_overlay': 'grass_block_side_overlay', 'grass_side_snowed': 'grass_block_snow',
    'dirt_podzol_top': 'podzol_top', 'dirt_podzol_side': 'podzol_side',
    'ice_packed': 'packed_ice', 'noteblock': 'note_block', 'slime': 'slime_block',
    'redstone_lamp_off': 'redstone_lamp', 'mob_spawner': 'spawner', 'portal': 'nether_portal', 'web': 'cobweb',
    'sponge_wet': 'wet_sponge', 'magma': 'magma',
    'trapdoor': 'oak_trapdoor', 'door_wood_lower': 'oak_door_bottom', 'door_wood_upper': 'oak_door_top',
    'door_iron_lower': 'iron_door_bottom', 'door_iron_upper': 'iron_door_top',
    'mushroom_block_skin_red': 'red_mushroom_block', 'mushroom_block_skin_brown': 'brown_mushroom_block',
    'mushroom_block_skin_stem': 'mushroom_stem',
    'anvil_base': 'anvil', 'anvil_top_damaged_0': 'anvil_top',
    'torch_on': 'torch', 'redstone_torch_on': 'redstone_torch',
    'rail_normal': 'rail', 'rail_normal_turned': 'rail_corner', 'rail_golden': 'powered_rail',
    'rail_golden_powered': 'powered_rail_on', 'rail_detector': 'detector_rail',
    'rail_detector_powered': 'detector_rail_on', 'rail_activator': 'activator_rail',
    'rail_activator_powered': 'activator_rail_on',
    'furnace_front_off': 'furnace_front', 'pumpkin_face_off': 'carved_pumpkin', 'pumpkin_face_on': 'jack_o_lantern',
    'pumpkin_top': 'pumpkin_top', 'pumpkin_side': 'pumpkin_side',
    'flower_rose': 'poppy', 'flower_dandelion': 'dandelion', 'flower_blue_orchid': 'blue_orchid',
    'flower_allium': 'allium', 'flower_houstonia': 'azure_bluet', 'flower_tulip_red': 'red_tulip',
    'flower_tulip_orange': 'orange_tulip', 'flower_tulip_white': 'white_tulip', 'flower_tulip_pink': 'pink_tulip',
    'flower_oxeye_daisy': 'oxeye_daisy',
    'tallgrass': 'short_grass', 'deadbush': 'dead_bush', 'reeds': 'sugar_cane', 'waterlily': 'lily_pad',
    'farmland_wet': 'farmland_moist', 'farmland_dry': 'farmland',
    'endframe_top': 'end_portal_frame_top', 'endframe_side': 'end_portal_frame_side',
    'endframe_eye': 'end_portal_frame_eye',
    'comparator_off': 'comparator', 'repeater_off': 'repeater',
    'piston_top_normal': 'piston_top', 'piston_top_sticky': 'piston_top_sticky',
    'dispenser_front_horizontal': 'dispenser_front', 'dropper_front_horizontal': 'dropper_front',
    'itemframe_background': 'item_frame', 'hay_block_side': 'hay_block_side',
    'melon_side': 'melon_side', 'melon_top': 'melon_top', 'ice': 'ice', 'glass': 'glass',
    'stone_slab_side_smooth': 'smooth_stone_slab_side',
}

_PATTERNS = [
    (re.compile(r'^concrete_powder_(\w+)$'), '{c}_concrete_powder', 'color'),
    (re.compile(r'^concrete_(\w+)$'), '{c}_concrete', 'color'),
    (re.compile(r'^hardened_clay_stained_(\w+)$'), '{c}_terracotta', 'color'),
    (re.compile(r'^glazed_terracotta_(\w+)$'), '{c}_glazed_terracotta', 'color'),
    (re.compile(r'^wool_colored_(\w+)$'), '{c}_wool', 'color'),
    (re.compile(r'^glass_pane_top_(\w+)$'), '{c}_stained_glass_pane_top', 'color'),
    (re.compile(r'^glass_(\w+)$'), '{c}_stained_glass', 'color'),
    (re.compile(r'^shulker_top_(\w+)$'), '{c}_shulker_box', 'color'),
    (re.compile(r'^planks_(\w+)$'), '{c}_planks', 'wood'),
    (re.compile(r'^log_(\w+)_top$'), '{c}_log_top', 'wood'),
    (re.compile(r'^log_(\w+)$'), '{c}_log', 'wood'),
    (re.compile(r'^leaves_(\w+)$'), '{c}_leaves', 'wood'),
    (re.compile(r'^sapling_(\w+)$'), '{c}_sapling', 'wood'),
    (re.compile(r'^door_(\w+)_lower$'), '{c}_door_bottom', 'wood'),
    (re.compile(r'^door_(\w+)_upper$'), '{c}_door_top', 'wood'),
    (re.compile(r'^trapdoor_(\w+)$'), '{c}_trapdoor', 'wood'),
]


def vanilla_texture_candidates(name):
    """Candidate 1.21 names (without 'block/') for a 1.12 'blocks/<name>' texture, best first."""
    out = []
    if name in _EXPLICIT:
        out.append(_EXPLICIT[name])
    for rx, tpl, kind in _PATTERNS:
        m = rx.match(name)
        if not m:
            continue
        g = m.group(1)
        if kind == 'color' and g in _COLORS:
            out.append(tpl.format(c='light_gray' if g == 'silver' else g))
        elif kind == 'wood' and g in _WOODS:
            out.append(tpl.format(c=_WOODS[g]))
    out.append(name)
    return out
