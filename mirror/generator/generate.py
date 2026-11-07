#!/usr/bin/env python3
"""Generate NebulaeMirror 1.21.1 block definitions, resources and id mappings from a MirrorDumper dump.

  python3 -I mirror/generator/generate.py --dump dumps/<dir> [--mc work/mc/1.21.1]
      [--overrides mapping/overrides] [--out work/gen]
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nebgen.build import generate  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--dump', required=True)
    ap.add_argument('--mc', default=os.path.join(ROOT, 'work', 'mc', '1.21.1'),
                    help='dir with generated/reports/blocks.json and textures-block.txt (see ops/mc/fetch_121.sh)')
    ap.add_argument('--overrides', default=os.path.join(ROOT, 'mapping', 'overrides'))
    ap.add_argument('--out', default=os.path.join(ROOT, 'work', 'gen'))
    ap.add_argument('--mods', default=os.path.join(ROOT, 'data', 'mods.zip'),
                    help="the origin server's mods zip (MoarSigns textures, fallback roadmark fonts)")
    ap.add_argument('--nebulaecraft', default=os.path.join(ROOT, '..', 'NebulaeCraft', 'src', 'main', 'resources'),
                    help='NebulaeCraft 2.26 resources (roadmark fonts)')
    a = ap.parse_args()
    report = generate(a.dump, a.mc, a.overrides, a.out, mods_zip=a.mods, nebulaecraft=a.nebulaecraft)
    c = report.counts
    print('generated %d blocks (%d states, %d meshes, %d quads), %d mapped by override, %d unknown by override'
          % (c['blocks.generated'], c['states.generated'], c['meshes'], c['quads'],
             c['blocks.mappedByOverride'], c['blocks.unknownByOverride']))
    print('textures: %d modded, %d vanilla 1.21, %d vanilla legacy fallback, %d missing'
          % (c['textures.modded'], c['textures.vanilla121'], c['textures.vanillaLegacy'],
             len(report.items.get('texturesMissing', []))))
    print('%d warning kinds, %d errors -> %s' % (len(report.warnings), len(report.errors),
                                                 os.path.join(a.out, 'report.md')))
    for e in report.errors[:20]:
        print('  ERROR', e)
    return 1 if report.errors else 0


if __name__ == '__main__':
    sys.exit(main())
