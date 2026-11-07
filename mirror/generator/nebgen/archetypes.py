"""
Archetypes: 1.12 block classes whose look depends on neighbours (connections, stair shape, door halves).
The mirror registers them on the matching 1.21 vanilla behaviour class, so 1.21's updateShape recomputes the
neighbour-derived properties; every other block is 'generic' and keeps its 1.12 properties verbatim.

Each own property describes the 1.21 name/values and how it maps to the 1.12 property (if any):
  legacy   - 1.12 property name (None = no 1.12 counterpart)
  ignored  - not part of blockstate variant keys / mappings (e.g. waterlogged)
  derived  - computed in 1.21 by updateShape; forward conversion only seeds it
  to_legacy / from_legacy - value maps (identity when absent)
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

NESW = ['north', 'east', 'south', 'west']
HORIZONTAL = ['north', 'south', 'west', 'east']
BOOL = ['true', 'false']


@dataclass
class OwnProp:
    name: str
    kind: str
    values: List[str]
    legacy: Optional[str] = None
    ignored: bool = False
    derived: bool = False
    to_legacy: Dict[str, str] = field(default_factory=dict)
    from_legacy: Dict[str, str] = field(default_factory=dict)

    def legacy_value(self, v121):
        return self.to_legacy.get(v121, v121)

    def value_121(self, v112):
        return self.from_legacy.get(v112, v112)


@dataclass
class Archetype:
    name: str
    base_class: str
    vanilla_reference: str  # a 1.21 block with exactly these properties, used to verify the spec
    props: List[OwnProp]
    pair_merge: bool = False  # forward conversion must merge two blocks (door halves)

    def matches(self, block):
        if self.base_class not in block.get('classes', []):
            return False
        legacy = {p['name']: set(p['values']) for p in block.get('properties', [])}
        for p in self.props:
            if p.legacy is None:
                continue
            if p.legacy not in legacy:
                return False
            needed = {p.legacy_value(v) for v in p.values}
            if not needed <= legacy[p.legacy]:
                return False
        return True


def _bool(name, legacy=None, derived=False, ignored=False):
    return OwnProp(name, 'bool', list(BOOL), legacy=legacy, derived=derived, ignored=ignored)


def _waterlogged():
    return _bool('waterlogged', ignored=True)


def _connections():
    return [_bool(d, legacy=d, derived=True) for d in NESW]


ARCHETYPES = [
    Archetype('stairs', 'net.minecraft.block.BlockStairs', 'minecraft:oak_stairs', [
        OwnProp('facing', 'direction', list(HORIZONTAL), legacy='facing'),
        OwnProp('half', 'enum', ['top', 'bottom'], legacy='half'),
        OwnProp('shape', 'enum', ['straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right'],
                legacy='shape', derived=True),
        _waterlogged(),
    ]),
    Archetype('door', 'net.minecraft.block.BlockDoor', 'minecraft:oak_door', [
        OwnProp('facing', 'direction', list(HORIZONTAL), legacy='facing'),
        OwnProp('half', 'enum', ['upper', 'lower'], legacy='half'),
        OwnProp('hinge', 'enum', ['left', 'right'], legacy='hinge'),
        _bool('open', legacy='open'),
        _bool('powered', legacy='powered'),
    ], pair_merge=True),
    Archetype('trapdoor', 'net.minecraft.block.BlockTrapDoor', 'minecraft:oak_trapdoor', [
        OwnProp('facing', 'direction', list(HORIZONTAL), legacy='facing'),
        OwnProp('half', 'enum', ['top', 'bottom'], legacy='half'),
        _bool('open', legacy='open'),
        _bool('powered', ignored=True),
        _waterlogged(),
    ]),
    Archetype('fence_gate', 'net.minecraft.block.BlockFenceGate', 'minecraft:oak_fence_gate', [
        OwnProp('facing', 'direction', list(HORIZONTAL), legacy='facing'),
        _bool('open', legacy='open'),
        _bool('powered', legacy='powered'),
        _bool('in_wall', legacy='in_wall', derived=True),
    ]),
    Archetype('wall', 'net.minecraft.block.BlockWall', 'minecraft:cobblestone_wall', [
        _bool('up', legacy='up', derived=True),
    ] + [
        OwnProp(d, 'enum', ['none', 'low', 'tall'], legacy=d, derived=True,
                to_legacy={'none': 'false', 'low': 'true', 'tall': 'true'},
                from_legacy={'false': 'none', 'true': 'low'})
        for d in NESW
    ] + [_waterlogged()]),
    Archetype('fence', 'net.minecraft.block.BlockFence', 'minecraft:oak_fence',
              _connections() + [_waterlogged()]),
    Archetype('pane', 'net.minecraft.block.BlockPane', 'minecraft:iron_bars',
              _connections() + [_waterlogged()]),
]

BY_NAME = {a.name: a for a in ARCHETYPES}


def classify(block, forced=None):
    """Return the Archetype for a dumped block, or None for generic."""
    if forced == 'generic':
        return None
    if forced:
        a = BY_NAME[forced]
        if not a.matches(block):
            raise ValueError('%s: forced archetype %s does not match its 1.12 properties' % (block['name'], forced))
        return a
    for a in ARCHETYPES:
        if a.matches(block):
            return a
    return None


def verify_against_vanilla(vanilla):
    """The specs must mirror the real 1.21.1 property sets; fail loudly if Mojang data disagrees."""
    problems = []
    for a in ARCHETYPES:
        real = vanilla.properties(a.vanilla_reference)
        spec = {p.name: p.values for p in a.props}
        if set(real) != set(spec):
            problems.append('%s: properties %s != %s' % (a.name, sorted(spec), sorted(real)))
            continue
        for name, values in spec.items():
            if set(values) != set(real[name]):
                problems.append('%s.%s: values %s != %s' % (a.name, name, values, real[name]))
    return problems
