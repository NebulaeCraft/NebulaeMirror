# Mirror mod (Fabric 1.21.1)

`mirror/mod/` registers every modded block from the generator output in 1.21.1. **Both server and client need it.** The generator output (`work/gen`) is bundled into the jar at build time; when it changes, rebuild the jar and redistribute it.

Clients should also install **Continuity** (3.0.0+1.21, Fabric) for Chisel's connected, patterned and random textures; the jar ships the converted rules (see [generator.md](generator.md)). Without Continuity those blocks show their unconnected texture. The dev client runs with Continuity; pass `-PnoContinuity` to compare without it.

## Build and test

Requires Java 21+ (the local Java 25 works; it compiles with `--release 21`):

```bash
python3 -I mirror/generator/generate.py --dump dumps/full
```

```bash
cd mirror/mod && ./gradlew build
```

The output is `mirror/mod/build/libs/nebulaemirror-<version>.jar`. Pass a different generator output directory with `-PgenDir=...`.

Self-test (opens a game window and exits automatically, about 20 seconds):

```bash
cd mirror/mod && ./gradlew runSelftestClient
```

It writes into `mirror/mod/run/selftest/`:

- `nebulaemirror-selftest.json`: how many block states and items got a mesh model, and the list of missing ones;
- `nebulaemirror-icons/<mod>.png`: every creative subtype rendered isometrically, one sheet per source mod, for comparing against the 1.12 game.

## How it works

| Part | Description |
|---|---|
| Block registration | Reads `blocks.json`. Archetype blocks subclass the vanilla behaviour class (`StairBlock`/`IronBarsBlock`/`FenceBlock`/`WallBlock`/`FenceGateBlock`/`DoorBlock`/`TrapDoorBlock`), so 1.21 recomputes connections and stair shapes; their extra 1.12 properties are appended. Everything else is `MirrorBlock`: properties match 1.12, per-state shapes, light and collision come from the dump, and `direction`/`axis` properties take part in rotation and mirroring (WorldEdit/Axiom transforms). Definitions are validated before any block is constructed, so one bad definition only skips that block |
| Properties | bool/int/direction/axis use vanilla property types; other enums use `StringProperty` |
| Items and creative tabs | One tab per source mod. Each 1.12 item subtype becomes an item stack with the `block_state` component (placing it gives that subtype) and its original name. States that can't be written back are hidden |
| Models | `PreparableModelLoadingPlugin` reads `assets/nebulaemirror/meshes/*.json` and bakes them with the Fabric Renderer API: per-quad blend mode (solid/cutout/translucent), emissive (full brightness, no shading, no AO), and vanilla-path quads derived for renderers without FRAPI. Sodium 0.6+ implements FRAPI |
| Tints | grass/foliage follow the biome; constant colours are fixed; `state` colours vary per state (Railcraft's 16-colour posts, tanks, glass and so on) |
| `nebulaemirror:legacy` BlockEntity | Generic blocks that had a TileEntity in 1.12, plus `nebulaemirror:unknown`, keep the original NBT unchanged (`Legacy` tag) and sync it to clients for P1 renderers (see [legacy-renderers.md](legacy-renderers.md)). Most are meshed with their chunk; only text blocks use a block-entity renderer, and a client mixin hides that renderer from all other legacy block entities |
| `nebulaemirror:unknown` | Placeholder for unmapped 1.12 blocks; its data is preserved for write-back |

## Current results (full modpack dump)

- 1373 blocks registered in 0.6 s (81 stairs, 23 panes, 2 fences, 1 fence gate, 1 door, 1265 generic). 128 blocks are mapped onto 1.21 vanilla blocks through overrides and don't need registering.
- 14,750 meshes; **all 47,435 block states and all 1373 items have mesh models** (346 states are intentionally empty: C&B, roadmark_text, MoarSigns, drawn from TE data); 1.23 million quads. The self-test looks through wrapping models such as Continuity's.
- In the icon sheets, NebulaeCraft (road markings, signs, traffic lights, screen doors, lamps), Quark, Railcraft (including the 16-colour variants), Sakura and others look right.

## Known limitations (planned for later phases)

- Item icons show only each block's default subtype; in-world placement is correct.
- TileEntity-driven looks are covered by P1 ([legacy-renderers.md](legacy-renderers.md)). Railcraft outfitted tracks draw their kit from TE data once the dump has `extras/railcraft_tracks.json` (MirrorDumper 0.2.1); with an older dump they keep the static default-kit model. Still from the static model: Quark coloured flowerpot contents.
- MoarSigns item icons are empty (1.12 drew them with the TESR too).
- Vanilla blocks need nothing from this mod: the importer converts them with DFU.
