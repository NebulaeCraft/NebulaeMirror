# Generator (dump → 1.21.1 mirror definitions)

`mirror/generator/` turns a MirrorDumper dump into everything the 1.21.1 mirror mod and the sync tools need. Pure Python standard library, no build step.

```bash
python3 -I mirror/generator/generate.py --dump dumps/<dump dir> [--mc work/mc/1.21.1] [--overrides mapping/overrides] [--out work/gen]
    [--mods data/mods.zip] [--nebulaecraft ../NebulaeCraft/src/main/resources]
```

`--mods` (the origin server's mods zip) supplies the MoarSigns sign textures; `--nebulaecraft` (the NebulaeCraft 2.26 source resources) supplies the roadmark fonts, falling back to the NebulaeCraft jar in the mods zip. Both are only needed for the P1 text renderers; without them the generator warns and continues.

`--mc` needs the 1.21.1 reference data (block report + vanilla texture list). Run `ops/mc/fetch_121.sh` once to produce it. The output directory is recreated on every run; to avoid deleting the wrong folder, it only clears directories it created itself.

## Output

| File | Used by | Contents |
|---|---|---|
| `mirror/blocks.json` | mirror mod | Every generated block: archetype, properties (kind/values/`stored`/`derived`/`legacy`), default state, per-state light/opacity/shape index, render layer, sound, emissive, tints, legacy-TE flag, item subtypes (with the state each places) and zh/en names. Shapes are deduplicated into the top-level `shapes` table |
| `mirror/forward.json` | importer (origin → mirror) | `"ns:name" → {target, metas: {meta: {1.21 props}}}` |
| `mirror/reverse.json` | submit (mirror → origin) | `"1.21 block" → {keys, map: {"k=v,...": [1.12 name, meta]}}`. `keys` holds only properties stored in the 1.12 meta; neighbour-derived properties (connections, stair shape) are ignored |
| `mirror/vanilla_metas.json` | write-back | Vanilla 1.12 block → canonical metas with their 1.12 state, from the dump's meta table; the mod runs DataFixer over these to build its vanilla reverse table (see [writeback.md](writeback.md)) |
| `mirror/source.json` | traceability | Dump version, versions of every mod, list of overrides used |
| `assets/<ns>/blockstates/*.json` | client | Variants → `nebulaemirror:mesh/<hash>` |
| `assets/nebulaemirror/meshes/<hash>.json` | client | Baked quads (block-space vertices, sprite-relative 0..16 UVs, `layer`, `cull`, `tint`, `shade`, `emissive`), deduplicated by content |
| `assets/<ns>/textures/block/…` | client | Modded textures. Vanilla textures reference 1.21's own assets (`minecraft:block/black_concrete` and so on); only when no equivalent exists is the 1.12 file shipped under `nebulaemirror:block/legacy/minecraft/…` |
| `assets/<ns>/lang/{en_us,zh_cn}.json` | client | Block names plus each subtype's legacy translation key |
| `assets/nebulaemirror/optifine/ctm/<ns>/*.properties` + `textures/block/ctm/…` | client with Continuity | Chisel's CTM 1.12 connected/patterned/random textures converted to Continuity (OptiFine-format) rules, with the tiles cut out of CTM's sheets (see below) |
| `assets/nebulaemirror/legacy/railcraft_tracks.json` | client | Railcraft outfitted track `"<rail>\|<kit>\|<shape>\|<state>"` → mesh, from the dump's `extras/railcraft_tracks.json` (MirrorDumper 1.2, formerly 0.2.1; `nebgen/tracks.py`). Missing from older dumps: the generator warns and tracks keep the default-kit model |
| `assets/nebulaemirror/textures/moarsigns/…`, `assets/nebulaemirror/roadmark/*.ttf` | client | MoarSigns sign textures and the NebulaeCraft road-marking fonts for the text renderers |
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
- **Alias metas**: some blocks decode several metas into the same state (Chisel `marble2` treats meta 8–15 as variation 7; the world holds 1.1 billion of these). Forward maps them to the state they actually render; reverse writes back the canonical meta (forward records this in `aliases`). Unmodified blocks are never written back, so the original alias meta is preserved.
- **Single-valued properties** (Quark's `prop=blarg`, NebulaeCraft's single-value `subtype`) carry no information and 1.21 doesn't allow them, so they are dropped and added back as constants when mapping to 1.12.
- **Writability** (`writable: false` on a state in `blocks.json`): a mirror state can be written back only if its **properties that 1.12 stores in meta** match some meta. Properties 1.12 computes itself (connections, stair shape, Sakura fence `up`, Railcraft post links) don't restrict writability because the origin recomputes them after write-back. Doors are checked per half (lower half: facing/open; upper half: hinge/powered). Unwritable states are hidden from creative tabs and rejected by the submit lint.
- **Full state space**: 1.21 creates every property combination of a block, so every combination gets a model. Combinations no dump state covers (e.g. Extra Utilities 2 pipe connections) borrow the nearest state and are marked unwritable.
- **Self-check** on every run; any failure is an error and exits non-zero:
  - every meta goes forward → reverse back to the same (name, meta);
  - archetype variants cover the full state space;
  - forward states are legal for their target block;
  - every block has a variant for its default state.
- **CTM → Continuity** (`nebgen/ctm.py`): every sprite the meshes use whose 1.12 `.mcmeta` has a `ctm` section is converted. CTM's own type decides the Continuity method and tile order:

  | CTM 1.12 / Chisel | Continuity | Notes |
  |---|---|---|
  | `ctm` (base + 2×2 `-ctm` sheet) | `ctm_compact` | tiles 0 base, 1 full, 2 vertical, 3 horizontal, 4 inner corners = CTM sheet TL/TR/BL/BR |
  | `ctmh` | `horizontal` | |
  | `pillar` / `ctmv` | `vertical`, side faces only | vertical columns; lying pillars stay unconnected |
  | `pattern` / `v` | `repeat` | one file per face group, so the tile phase matches CTM's world-position formula exactly |
  | `random` / `r`, Chisel `AR` | `random` | same tiles; which block gets which tile differs from 1.12 |
  | Chisel `SCTM` | `fixed` | the unconnected tile |

  Connections use `connect=state` (CTM's default). Animated sheets keep their frames. File names never start with `block_`, which Continuity would read as a block match. CTM `light: 15` marks the quads emissive (the dump already has it for those).
- **TESR-only blocks**: MoarSigns states are `INVISIBLE` in 1.12 yet the dump carries a baked placeholder cube; their meshes are left empty because the mirror's sign renderer draws them.
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

- Existing rule files: `futuremc.json` (all 79 FutureMC blocks → their 1.21 vanilla equivalents) and `quark.json` (49 Quark blocks: wooden buttons/pressure plates/trapdoors, and stairs/slabs/walls of vanilla stones). With these, builders can use vanilla blocks such as `andesite_stairs` in the mirror, and write-back turns them into the corresponding FutureMC/Quark block.
- `target`: map to an existing 1.21 block instead of generating one. For each meta, the 1.21 state is built as fixed `state` + renamed 1.12 properties (`props`: old name → new name, `null` drops it) + value renames (`values`). `metas.<n>` overrides a single meta (`"unknown"` = keep that meta as a placeholder; a dict with `"@target"` sends that meta to a different block and replaces the derived properties entirely, e.g. a FutureMC stripped log with `axis=none` → `stripped_*_wood`, or a soul torch facing up → `soul_torch`; alias metas fall back to the rule of their canonical meta). Target properties and values are checked against the 1.21.1 report; any mismatch is an error. With `"reverse": false` the mirror never writes this block back.
- `archetype`: force an archetype, or `generic`.
- `unknown`: generate nothing; the importer keeps these as placeholders that preserve the original data.

When several 1.12 blocks share one target, reverse priority is: generated block's own target > override's main target > per-meta `@target` redirect; within the same priority the first one wins. Conflicts are listed in the report's `reverseAmbiguous`. For example, a 1.21 acacia trapdoor is written back as `futuremc:acacia_trapdoor`, not Quark's; a 1.21 cartography table has no facing, so it is written back as one fixed meta. Reverse keys only include properties that actually vary across the canonical metas (FutureMC barrel's `open` lives in its TE and is ignored).

## Current results (full modpack dump, 2026-11-07)

- 1755 dumped blocks; 1373 generated (81 stairs, 23 panes, 2 fences, 1 fence gate, 1 door, 1265 generic); 128 mapped onto 1.21 vanilla via overrides; 39,011 states / 14,750 meshes, 0 errors, about 15 seconds.
- World coverage (`ops/scan`): every (name, meta) the world uses is mapped, except the 2 NebulaeCraft blocks removed long ago (`device_apple_iphone` ×1106, `countdown` ×1), which become unknown placeholders.
- Textures: 3611 modded copied; 101 vanilla references point at 1.21's own textures; 1 kept as a 1.12 fallback; Extra Utilities 2's runtime sprites (23) were read back from the atlas.
- Warnings that matter:
  - About 38 blocks reference textures that are missing in 1.12 too. They include 4 NebulaeCraft blocks (`device_ms_surface`, `fire_hydrant`, `info_screen_5`, `screen_door_top`) that need fixing in NebulaeCraft.
  - 66 blocks have no quads (mostly Chisels&Bits, which renders from TE data); P1 handles these.
- CTM: 654 textures converted (365 connected, 125 pattern, 117 random/AR, 26 pillar, 15 horizontal, 6 SCTM) into 1154 property files and 3275 tiles.
  - About 139 blocks have appearance properties that 1.12 derives from neighbours/TE. The mirror shows them as stored; these are mainly Railcraft posts/wires and Quark flowerpots.
  - About 3984 unwritable states, almost all Chisel's unused variations.
