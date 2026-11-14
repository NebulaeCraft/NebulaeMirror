#!/usr/bin/env python3
"""Compare two MirrorDumper dumps: which mods changed version, which blocks were added or removed, which
blocks changed in a way that alters the 1.12 -> 1.21 mapping (property names/values or the meta -> state table),
and which only look different (same mapping, other baked models).

Usage:
  python3 -I ops/scan/diff_dumps.py dumps/<old dump> dumps/<new dump>

Vanilla (minecraft:*) blocks are skipped: the importer converts them with Minecraft's own DataFixer.
See docs/mod-updates.md. ops/update/update.py uses diff() from here.
"""
import json
import sys


def read(path):
    with open(path, encoding='utf8') as f:
        return json.load(f)


def load(d):
    index = {b['name']: b for b in read(d + '/blocks/index.json')}
    mods = {m['id']: m['version'] for m in read(d + '/manifest.json')['mods']}
    return d, index, mods


def shape(d, b):
    """(mapping, look) of one block.
    mapping: what decides the forward mapping, i.e. property names/values and the meta -> state table.
    look: the baked model of every state (content hashes per render layer)."""
    j = read('%s/%s' % (d, b['file']))
    props = [(p['name'], p['type'], tuple(p.get('values', []))) for p in j.get('properties', [])]
    metas = [json.dumps(m, sort_keys=True) for m in j.get('metaTable', [])]
    look = sorted(json.dumps([s.get('props'), (s.get('model') or {}).get('layers')], sort_keys=True)
                  for s in j.get('states', []))
    return (props, metas), look


def diff(old_dir, new_dir):
    """Structured comparison of two dump directories:
    {'mods': [(id, old version or None, new version or None)], 'added': [...], 'removed': [...],
     'changed': [...] (mapping differs), 'appearance': [...] (same mapping, models differ)}; block names sorted."""
    (od, old, om), (nd, new, nm) = load(old_dir), load(new_dir)
    out = {'mods': [(m, om.get(m), nm.get(m)) for m in sorted(set(om) | set(nm)) if om.get(m) != nm.get(m)],
           'added': sorted(n for n in new.keys() - old.keys() if not n.startswith('minecraft:')),
           'removed': sorted(n for n in old.keys() - new.keys() if not n.startswith('minecraft:')),
           'changed': [], 'appearance': []}
    for n in sorted(old.keys() & new.keys()):
        if n.startswith('minecraft:'):
            continue
        (omap, olook), (nmap, nlook) = shape(od, old[n]), shape(nd, new[n])
        if omap != nmap:
            out['changed'].append(n)
        elif olook != nlook:
            out['appearance'].append(n)
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    d = diff(sys.argv[1], sys.argv[2])
    for m, old, new in d['mods']:
        print('mod      %-24s %s -> %s' % (m, old, new))
    for n in d['added']:
        print('added    ', n)
    for n in d['removed']:
        print('removed  ', n)
    for n in d['changed']:
        print('CHANGED  ', n, '(properties or meta table differ)')
    for n in d['appearance']:
        print('look     ', n, '(models differ, mapping unchanged)')


if __name__ == '__main__':
    main()
