#!/usr/bin/env python3
"""Tally block states / tile entities / entities of a 1.12.2 Forge world, optionally checking a dump's coverage.

Usage:
  python3 -I ops/scan/scan_world.py <world dir | world .zip> --out stats.json [--workers 8] [--limit N]
  python3 -I ops/scan/scan_world.py --stats stats.json --dump dumps/<dump dir>     # coverage report only

Block ids are resolved through level.dat's FML registry, so results are keyed by "namespace:name:meta".
"""
import argparse
import collections
import json
import multiprocessing
import os
import struct
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402

LO = bytes(i & 15 for i in range(256))
HI = bytes(i >> 4 for i in range(256))
ZERO = bytes(4096)


def nibbles(x):
    o = bytearray(4096)
    o[0::2] = x.translate(LO)
    o[1::2] = x.translate(HI)
    return bytes(o)


class WorldSource:
    """Reads level.dat and region files from either a directory or a zip of the world folder."""

    def __init__(self, path):
        self.path = path
        self.zip = None
        self.prefix = ''
        if os.path.isfile(path):
            self.zip = zipfile.ZipFile(path)
            names = self.zip.namelist()
            level = sorted((n for n in names if n.endswith('level.dat') and n.count('/') <= 1), key=len)[0]
            self.prefix = level[:-len('level.dat')]

    def read(self, rel):
        if self.zip:
            return self.zip.read(self.prefix + rel)
        with open(os.path.join(self.path, rel), 'rb') as f:
            return f.read()

    def regions(self):
        if self.zip:
            p = self.prefix + 'region/'
            return sorted(n[len(self.prefix):] for n in self.zip.namelist()
                          if n.startswith(p) and n.endswith('.mca') and '/' not in n[len(p):])
        d = os.path.join(self.path, 'region')
        return sorted('region/' + n for n in os.listdir(d) if n.endswith('.mca'))


def scan_region(args):
    world_path, rel = args
    src = WorldSource(world_path)
    raw = src.read(rel)
    states = collections.Counter()
    tes = collections.Counter()
    ents = collections.Counter()
    chunks = 0
    bad = 0
    for c in range(1024):
        off = struct.unpack('>I', b'\0' + raw[c * 4:c * 4 + 3])[0]
        if not off:
            continue
        p = off * 4096
        if p + 5 > len(raw):
            bad += 1
            continue
        ln = struct.unpack('>I', raw[p:p + 4])[0]
        ctype = raw[p + 4]
        try:
            body = raw[p + 5:p + 4 + ln]
            d = nbt.load(zlib.decompress(body) if ctype == 2 else body)
        except Exception:
            bad += 1
            continue
        level = d['Level']
        chunks += 1
        for te in level.get('TileEntities', []):
            tes[te.get('id')] += 1
        for e in level.get('Entities', []):
            ents[e.get('id')] += 1
        for s in level.get('Sections', []):
            blocks = s['Blocks']
            meta = nibbles(s['Data'])
            add = nibbles(s['Add']) if 'Add' in s else ZERO
            for (a, b, m), v in collections.Counter(zip(add, blocks, meta)).items():
                states[((a << 8) | b) << 4 | m] += v
    return rel, chunks, bad, states, tes, ents


def scan(world, workers, limit):
    src = WorldSource(world)
    level = nbt.load(src.read('level.dat'))
    registry = {e['V']: e['K'] for e in level['FML']['Registries']['minecraft:blocks']['ids']}
    regions = src.regions()
    if limit:
        regions = regions[:limit]
    states = collections.Counter()
    tes = collections.Counter()
    ents = collections.Counter()
    chunks = bad = 0
    with multiprocessing.Pool(workers) as pool:
        for i, (rel, c, b, s, t, e) in enumerate(pool.imap_unordered(scan_region, [(world, r) for r in regions])):
            chunks += c
            bad += b
            states.update(s)
            tes.update(t)
            ents.update(e)
            if (i + 1) % 50 == 0 or i + 1 == len(regions):
                print('  %d/%d regions, %d chunks' % (i + 1, len(regions), chunks), file=sys.stderr)
    named = collections.Counter()
    for key, v in states.items():
        bid, meta = key >> 4, key & 15
        named['%s:%d' % (registry.get(bid, '?unregistered_%d' % bid), meta)] += v
    return {
        'world': os.path.abspath(world),
        'regions': len(regions),
        'chunks': chunks,
        'badChunks': bad,
        'registry': {str(k): v for k, v in sorted(registry.items())},
        'states': dict(named.most_common()),
        'tileEntities': dict(tes.most_common()),
        'entities': dict(ents.most_common()),
    }


def coverage(stats, dump_dir):
    """Every (name, meta) the world uses must exist in the dump with a round-tripping meta."""
    missing_block = collections.Counter()
    bad_meta = collections.Counter()
    no_model = collections.Counter()
    cache = {}
    for key, count in stats['states'].items():
        name, meta = key.rsplit(':', 1)
        meta = int(meta)
        if name == 'minecraft:air':
            continue
        if name not in cache:
            ns, path = name.split(':', 1)
            f = os.path.join(dump_dir, 'blocks', ns, path + '.json')
            cache[name] = json.load(open(f, encoding='utf8')) if os.path.exists(f) else None
        b = cache[name]
        if b is None:
            missing_block[name] += count
            continue
        entry = b['metaTable'][meta]
        if entry.get('roundTrip') != meta:
            bad_meta[key] += count
        elif b.get('modelsDumped') and not name.startswith('minecraft:'):
            st = entry['state']
            match = next((s for s in b['states'] if s['props'] == st), None)
            if match is None or not match.get('model', {}).get('layers'):
                no_model[key] += count
    by_mod = collections.Counter(n.split(':')[0] for n in missing_block)
    return {
        'blocksMissingFromDump': dict(missing_block.most_common()),
        'blocksMissingByMod': dict(by_mod.most_common()),
        'metaNotRoundTripping': dict(bad_meta.most_common()),
        'statesWithoutQuads': dict(no_model.most_common()),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('world', nargs='?', help='world directory or zip')
    ap.add_argument('--out', help='write stats JSON here')
    ap.add_argument('--stats', help='reuse an existing stats JSON instead of scanning')
    ap.add_argument('--dump', help='dump directory to check coverage against')
    ap.add_argument('--workers', type=int, default=os.cpu_count())
    ap.add_argument('--limit', type=int, default=0, help='only scan the first N regions (testing)')
    a = ap.parse_args()
    if a.stats:
        stats = json.load(open(a.stats, encoding='utf8'))
    elif a.world:
        stats = scan(a.world, a.workers, a.limit)
        if a.out:
            with open(a.out, 'w', encoding='utf8') as f:
                json.dump(stats, f, ensure_ascii=False, indent=1)
    else:
        ap.error('give a world or --stats')
    mods = collections.Counter()
    for key, v in stats['states'].items():
        mods[key.split(':')[0]] += v
    print('%d chunks, %d distinct (name, meta), %d bad chunks' % (stats['chunks'], len(stats['states']), stats['badChunks']))
    for m, v in mods.most_common():
        print('  %-22s %15d' % (m, v))
    if a.dump:
        cov = coverage(stats, a.dump)
        print(json.dumps({k: (v if k == 'blocksMissingByMod' else len(v)) for k, v in cov.items()}, indent=1))
        out = os.path.join(a.dump, 'coverage.json')
        with open(out, 'w', encoding='utf8') as f:
            json.dump(cov, f, ensure_ascii=False, indent=1)
        print('coverage details ->', out)


if __name__ == '__main__':
    main()
