"""Conversion of 1.12 block properties that the mirror block keeps verbatim ('extra' properties)."""
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from .util import sanitize_name

DIRECTIONS = {'down', 'up', 'north', 'south', 'west', 'east'}


@dataclass
class ExtraProp:
    name: str                 # 1.21 property name
    legacy: str               # 1.12 property name
    kind: str                 # bool | int | direction | axis | enum
    values: List[str]         # 1.21 values, 1.12 order
    to_legacy: Dict[str, str] = field(default_factory=dict)  # 1.21 value -> 1.12 value
    min: Optional[int] = None
    max: Optional[int] = None

    def from_legacy(self, v112):
        for k, v in self.to_legacy.items():
            if v == v112:
                return k
        raise KeyError('%s: 1.12 value %r has no 1.21 value' % (self.legacy, v112))

    def legacy_value(self, v121):
        return self.to_legacy[v121]

    def to_json(self, stored):
        d = {'name': self.name, 'legacy': self.legacy, 'kind': self.kind, 'values': self.values,
             'stored': stored}
        if self.kind == 'int':
            d['min'] = self.min
            d['max'] = self.max
        if any(k != v for k, v in self.to_legacy.items()):
            d['legacyValues'] = self.to_legacy
        return d


def make_extra(prop, taken):
    """Build an ExtraProp from a dumped 1.12 property; `taken` holds 1.21 names already used on the block."""
    legacy = prop['name']
    name = sanitize_name(legacy)
    while name in taken:
        name += '_legacy'
    taken.add(name)
    values = [str(v) for v in prop['values']]
    t = prop.get('type')

    if t == 'bool' and set(values) <= {'true', 'false'}:
        return ExtraProp(name, legacy, 'bool', values, {v: v for v in values})

    if t == 'int':
        try:
            ints = sorted(int(v) for v in values)
        except ValueError:
            ints = None
        # IntegerProperty needs 0 <= min < max and a contiguous range.
        if ints and len(ints) > 1 and ints[0] >= 0 and ints == list(range(ints[0], ints[-1] + 1)):
            vs = [str(i) for i in ints]
            return ExtraProp(name, legacy, 'int', vs, {v: v for v in vs}, min=ints[0], max=ints[-1])

    if t == 'direction' and set(values) <= DIRECTIONS:
        return ExtraProp(name, legacy, 'direction', values, {v: v for v in values})

    if t == 'enum' and legacy == 'axis' and set(values) == {'x', 'y', 'z'}:
        return ExtraProp(name, legacy, 'axis', ['x', 'y', 'z'], {v: v for v in ('x', 'y', 'z')})

    # Generic string-valued property; keep values unique after sanitising.
    out, seen = {}, set()
    for v in values:
        nv = sanitize_name(v) or '_'
        while nv in seen:
            nv += '_'
        seen.add(nv)
        out[nv] = v
    return ExtraProp(name, legacy, 'enum', list(out), out)
