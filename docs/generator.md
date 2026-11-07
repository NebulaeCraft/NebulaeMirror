# Generator (dump → 1.21.1 mirror definitions)

`mirror/generator/` turns a MirrorDumper dump into everything the 1.21.1 mirror mod and the sync tools need. Pure Python standard library, no build step.

```bash
python3 -I mirror/generator/generate.py --dump dumps/<dump dir> [--mc work/mc/1.21.1] [--overrides mapping/overrides] [--out work/gen]
```

`--mc` needs the 1.21.1 reference data (block report + vanilla texture list). Run `ops/mc/fetch_121.sh` once to produce it. The output directory is recreated on every run; to avoid deleting the wrong folder, it only clears directories it created itself.

## Output

| File | Used by | Contents |
|---|---|---|
| `mirror/blocks.json` | mirror mod | Every generated block: archetype, properties (kind/values/`stored`/`derived`/`legacy`), default state, per-state light/opacity/shape index, render layer, sound, emissive, tints, legacy-TE flag, item subtypes (with the state each places) and zh/en names. Shapes are deduplicated into the top-level `shapes` table |
| `mirror/forward.json` | importer (origin → mirror) | `"ns:name" → {target, metas: {meta: {1.21 props}}}` |
| `mirror/reverse.json` | submit (mirror → origin) | `"1.21 block" → {keys, map: {"k=v,...": [1.12 name, meta]}}`. `keys` holds only properties stored in the 1.12 meta; neighbour-derived properties (connections, stair shape) are ignored |
| `mirror/source.json` | traceability | Dump version, versions of every mod, list of overrides used |
| `assets/<ns>/blockstates/*.json` | client | Variants → `nebulaemirror:mesh/<hash>` |
| `assets/nebulaemirror/meshes/<hash>.json` | client | Baked quads (block-space vertices, sprite-relative 0..16 UVs, `layer`, `cull`, `tint`, `shade`, `emissive`), deduplicated by content |
| `assets/<ns>/textures/block/…` | client | Modded textures. Vanilla textures reference 1.21's own assets (`minecraft:block/black_concrete` and so on); only when no equivalent exists is the 1.12 file shipped under `nebulaemirror:block/legacy/minecraft/…` |
| `assets/<ns>/lang/{en_us,zh_cn}.json` | client | Block names plus each subtype's legacy translation key |
| `report.json` / `report.md` | humans | Counts, errors, warnings (grouped by type, listing the blocks), missing textures, foreign item subtypes, and so on |

## Key rules

- **Vanilla blocks are not generated here**; the importer handles them with Minecraft's built-in DFU. Modded blocks keep their original namespaces (e.g. `nebulaecraft:asphalt`), so you can write them directly in WorldEdit/Axiom.
- **Archetypes**: subclasses of 1.12 `BlockStairs`/`BlockDoor`/`BlockTrapDoor`/`BlockFenceGate`/`BlockWall`/`BlockFence`/`BlockPane` map onto the matching 1.21 behaviour, so connections and stair shapes are recalculated in 1.21. Extra properties of those blocks (e.g. colour) are kept. Everything else is `generic`, keeping its 1.12 properties verbatim:
  - `facing`-style properties → `direction`;
  - contiguous integers → `int`;
  - `axis` → `axis`;
  - everything else → string enum.

  Rotating and mirroring in WorldEdit/Axiom relies on the first three types.
- **Item subtypes**: item damage is not the block meta (in NebulaeCraft, meta 1 = facing south while item 1 = subtype1). The placed state is resolved in this order:
  1. `getPickBlock`;
  2. `damageDropped`;
  3. a property value named in the translation key;
  4. item damage = block meta.
- **Self-check** on every run; any failure is an error and exits non-zero:
  - every meta goes forward → reverse back to the same (name, meta);
  - archetype variants cover the full state space;
  - forward states are legal for their target block;
  - every block has a variant for its default state.
- **Archetype specs** are checked against the real property sets in the 1.21.1 block report on every run, so a Minecraft version change is caught immediately.

## Hand-written mappings `mapping/overrides/*.json`

```json
{
  "blocks": {
    "futuremc:smooth_stone": {"target": "minecraft:smooth_stone"},
    "futuremc:stripped_spruce_log": {"target": "minecraft:stripped_spruce_log", "props": {"axis": "axis"}},
    "quark:example": {
      "target": "minecraft:example",
      "state": {"type": "bottom"},
      "props": {"variant": null, "facing": "facing"},
      "values": {"facing": {"up": "north"}},
      "metas": {"5": "unknown", "6": {"type": "top"}}
    },
    "somemod:odd_block": {"archetype": "generic"},
    "nebulaecraft:device_apple_iphone": {"unknown": true}
  }
}
```

- `target`: map to an existing 1.21 block instead of generating one. For each meta, the 1.21 state is built as fixed `state` + renamed 1.12 properties (`props`: old name → new name, `null` drops it) + value renames (`values`). `metas.<n>` overrides a single meta (`"unknown"` = keep that meta as a placeholder). Target properties and values are checked against the 1.21.1 report; any mismatch is an error. With `"reverse": false` the mirror never writes this block back.
- `archetype`: force an archetype, or `generic`.
- `unknown`: generate nothing; the importer keeps these as placeholders that preserve the original data.

Only map onto a vanilla block if it looks the same (the rule from the plan). When several 1.12 blocks share one target, reverse keeps the first and lists the rest in the report's `reverseAmbiguous`.

## Current results (dev dump: NebulaeCraft 2.26 + Railcraft)

- 597 blocks / 12,856 states / 6,057 meshes / 312,019 quads, 0 errors, about 7 seconds.
- Archetypes: 21 stairs (Railcraft) and 4 panes (NebulaeCraft fences); the rest are generic.
- All 3,225 metas pass the forward → reverse round trip.
- Item subtypes: 917 resolved via pick-block, 208 via damageDropped. Railcraft lists 5 vanilla items among its own blocks' subtypes; these are skipped.
- Warnings to deal with:
  - 4 NebulaeCraft blocks (`device_ms_surface`, `fire_hydrant`, `info_screen_5`, `screen_door_top`) reference textures that don't exist in the 1.12 source either; this needs fixing in NebulaeCraft.
  - 8 blocks have only a TESR with no quads (`roadmark_text`, Railcraft chests/engines and so on); P1 handles these.
  - 29 Railcraft multiblock/machine blocks have appearance properties derived from neighbours; the mirror shows them as stored (static) values.
