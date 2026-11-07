"""
Hand-written mapping rules, mapping/overrides/*.json:

{
  "blocks": {
    "futuremc:smooth_stone": {"target": "minecraft:smooth_stone"},
    "futuremc:stripped_spruce_log": {"target": "minecraft:stripped_spruce_log", "props": {"axis": "axis"}},
    "quark:some_block":  {"target": "minecraft:x", "state": {"type": "bottom"},
                          "props": {"variant": null, "facing": "facing"},
                          "values": {"facing": {"up": "north"}},
                          "metas": {"5": "unknown", "6": {"type": "top"}}},
    "mod:weird":         {"archetype": "generic"},
    "mod:gone":          {"unknown": true},
    "comment": "keys that are not namespaced ids are ignored"
  }
}

target  - map onto an existing 1.21 block instead of generating one. Every valid meta maps to
          state (fixed props) + renamed 1.12 props (props: legacyName -> newName | null to drop)
          with optional value renames; metas.<n> overrides one meta ("unknown" = leave as placeholder).
          "reverse": false keeps the mirror from ever writing this 1.12 block back.
archetype - force an archetype (or "generic") for a generated block.
unknown - generate nothing; the importer keeps these as data-preserving placeholders.
"""
import glob
import os

from .util import read_json


def load(directory):
    rules = {}
    sources = {}
    for path in sorted(glob.glob(os.path.join(directory, '*.json'))):
        data = read_json(path)
        for name, rule in data.get('blocks', {}).items():
            if ':' not in name:
                continue
            if name in rules:
                raise ValueError('override for %s defined in both %s and %s' % (name, sources[name], path))
            rules[name] = rule
            sources[name] = os.path.basename(path)
    return rules


def target_props(rule, legacy_props, meta):
    """1.21 props for one meta under a 'target' rule, or None when that meta should stay unknown."""
    explicit = rule.get('metas', {}).get(str(meta))
    if explicit == 'unknown':
        return None
    out = dict(rule.get('state', {}))
    renames = rule.get('props', {})
    values = rule.get('values', {})
    for k, v in legacy_props.items():
        if k not in renames:
            continue
        nk = renames[k]
        if nk is None:
            continue
        out[nk] = values.get(k, {}).get(v, v)
    if isinstance(explicit, dict):
        out.update(explicit)
    return out


def validate_target(rule, props, vanilla, generated_ids):
    """Return a list of problems with a resolved target state."""
    target = rule['target']
    if target.startswith('minecraft:'):
        if not vanilla.has_block(target):
            return ['target %s does not exist in 1.21.1' % target]
        allowed = vanilla.properties(target)
        problems = []
        for k, v in props.items():
            if k not in allowed:
                problems.append('%s has no property %s' % (target, k))
            elif v not in allowed[k]:
                problems.append('%s.%s has no value %s' % (target, k, v))
        return problems
    if target not in generated_ids:
        return ['target %s is neither vanilla nor a generated block' % target]
    return []
