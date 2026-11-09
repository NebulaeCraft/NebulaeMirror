#!/usr/bin/env python3
"""Compare two MirrorDumper dumps: which mods changed version, which blocks were added or removed, and which
blocks changed in a way that alters the 1.12 -> 1.21 mapping (property names/values or the meta -> state table).

Usage:
  python3 -I ops/scan/diff_dumps.py dumps/<old dump> dumps/<new dump>

Vanilla (minecraft:*) blocks are skipped: the importer converts them with Minecraft's own DataFixer.
See docs/mod-updates.md.
"""
import json
import sys


def load(d):
    index = {b['name']: b for b in json.load(open(d + '/blocks/index.json', encoding='utf8'))}
    mods = {m['id']: m['version'] for m in json.load(open(d + '/manifest.json', encoding='utf8'))['mods']}
    return d, index, mods


def shape(d, b):
    """What decides the forward mapping: property names/values and the meta -> state table."""
    j = json.load(open('%s/%s' % (d, b['file']), encoding='utf8'))
    props = [(p['name'], p['type'], tuple(p.get('values', []))) for p in j.get('properties', [])]
    metas = [json.dumps(m, sort_keys=True) for m in j.get('metaTable', [])]
    return props, metas


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    (od, old, om), (nd, new, nm) = load(sys.argv[1]), load(sys.argv[2])
    for m in sorted(set(om) | set(nm)):
        if om.get(m) != nm.get(m):
            print('mod      %-24s %s -> %s' % (m, om.get(m), nm.get(m)))
    for n in sorted(new.keys() - old.keys()):
        if not n.startswith('minecraft:'):
            print('added    ', n)
    for n in sorted(old.keys() - new.keys()):
        if not n.startswith('minecraft:'):
            print('removed  ', n)
    for n in sorted(old.keys() & new.keys()):
        if not n.startswith('minecraft:') and shape(od, old[n]) != shape(nd, new[n]):
            print('CHANGED  ', n, '(properties or meta table differ)')


if __name__ == '__main__':
    main()
