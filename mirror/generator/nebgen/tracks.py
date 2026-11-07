"""Railcraft 12 outfitted tracks: the dumper's TileEntity-driven model variants -> meshes + a lookup table.

The dump (MirrorDumper >= 0.2.1) holds `extras/railcraft_tracks.json`: Railcraft's composite model baked for every
rail type x kit x shape x kit render state, keyed "<rail>|<kit>|<shape>|<state>" with the TileEntity's own NBT
names. Each variant becomes an ordinary mesh (`nebulaemirror:mesh/<hash>`, deduplicated with every other mesh), and

  assets/nebulaemirror/legacy/railcraft_tracks.json
    {"schema": 1, "block": "railcraft:track_outfitted",
     "kits": {"railcraft_turnout": {"renderer": "UNIFIED", "states": 8}, ...},
     "variants": {"railcraft_reinforced|railcraft_turnout|north_south|3": "nebulaemirror:mesh/<hash>", ...}}

tells the mirror's client renderer (client/legacy/OutfittedTrack) which mesh a track's TileEntity selects.
"""
import os

from . import MOD_ID
from .util import read_json, write_json

BLOCK = 'railcraft:track_outfitted'
EXTRA = os.path.join('extras', 'railcraft_tracks.json')
TABLE = os.path.join(MOD_ID, 'legacy', 'railcraft_tracks.json')
SCHEMA = 1


def convert(dump_root, assets_dir, mesh_fn, report, needed):
    """mesh_fn(state) -> (mesh id, info), as Generator._mesh. needed: whether the block was generated at all."""
    path = os.path.join(dump_root, EXTRA)
    if not os.path.isfile(path):
        if needed:
            report.warn(BLOCK, 'dump has no %s (MirrorDumper < 0.2.1, or the export failed: see the dump\'s '
                        'errors.json, stage trackKits): outfitted tracks keep the default kit model'
                        % EXTRA.replace(os.sep, '/'))
        return None
    data = read_json(path)
    variants = {}
    for key in sorted(data.get('variants', {})):
        mesh_id, info = mesh_fn({'model': data['variants'][key]})
        if info.get('missing'):
            report.count('tracks.missingTextures')
        variants[key] = mesh_id
    table = {
        'schema': SCHEMA,
        'block': data.get('block', BLOCK),
        'kits': {k: {'renderer': v.get('renderer', 'COMPOSITE'), 'states': v.get('states', 1)}
                 for k, v in sorted(data.get('kits', {}).items())},
        'variants': variants,
    }
    write_json(os.path.join(assets_dir, TABLE), table, pretty=False)
    report.count('tracks.variants', len(variants))
    report.count('tracks.meshes', len(set(variants.values())))
    return table
