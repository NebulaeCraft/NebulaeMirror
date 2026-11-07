# NebulaeMirror

A 1.21.1 Fabric mirror server for the 1.12.2 Forge build server, so players can use modern editing tools such as WorldEdit and Axiom. The mirror only needs to match the origin's **block appearance**. Origin → mirror runs as periodic incremental sync; mirror → origin is written back per checked-out zone.

The full design is in the approved technical plan (overall architecture, sync model, plan for mod updates). Phase progress:

- [x] Coverage scanner `ops/scan/` (1.12 world → (name, meta) / TE / entity stats, plus dump coverage check)
- [x] 1.12 dumper `legacy/dumper/` (exports block properties, meta table, baked quads, shapes, textures, lang)
- [x] Generator `mirror/generator/` (dump → 1.21 block definitions, models, forward/reverse mapping), see [docs/generator.md](docs/generator.md)
- [x] Mirror mod `mirror/mod/` (Fabric 1.21.1): block registration, mesh models, items/creative tabs, legacy TE storage, unknown placeholder, see [docs/mirror-mod.md](docs/mirror-mod.md)
- [x] FutureMC/Quark → 1.21 vanilla overrides (`mapping/overrides/`)
- [x] Importer: full offline import (DFU + modded block substitution), see [docs/importer.md](docs/importer.md)
- [x] P1 TileEntity-driven appearance: Chisels&Bits voxels, ArchitectureCraft shapes/roofs, see [docs/legacy-renderers.md](docs/legacy-renderers.md) (roadmark_text, moarsigns, Railcraft tanks, CTM still to do)
- [ ] Online incremental sync (P2)
- [ ] Bridge mod `legacy/bridge/` (zone lock, patch apply/rollback)

## Layout

| Directory | Contents |
|---|---|
| `data/` | Origin server snapshots: world zip, config, mods (gitignored) |
| `legacy/dumper/` | Forge 1.12.2 client mod: exports block appearance data |
| `legacy/bridge/` | Forge 1.12.2 server mod (to do) |
| `mirror/mod/` | Fabric 1.21.1 mod (Gradle 9.8 + Loom, Mojang mappings) |
| `mirror/generator/` | dump → mirror definition generator (Python, stdlib only) |
| `mapping/overrides/` | Hand-curated mappings (format in docs/generator.md) |
| `mapping/snapshots/` | Registry/mapping snapshots for diffing new blocks |
| `ops/scan/` | World scan and coverage check |
| `ops/mc/` | Fetch 1.21.1 official jars and generate reference data |
| `dumps/` `work/` | Local dumps and intermediate files (gitignored) |
| `docs/` | Usage docs |

## Common commands

Scan the world (stdlib Python only; 8 cores take about 5 minutes for the full world):

```bash
python3 -I ops/scan/scan_world.py data/2026.11.06.zip --out work/world-stats.json --workers 8
```

Check whether a dump covers every block the world actually uses:

```bash
python3 -I ops/scan/scan_world.py --stats work/world-stats.json --dump dumps/<dump dir>
```

Build the dumper (needs JDK 8):

```bash
cd legacy/dumper && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./gradlew build
```

For running the dumper see [docs/dumper.md](docs/dumper.md).

Generate the mirror definitions (fetch the 1.21.1 reference data once first):

```bash
JAVA=$(/usr/libexec/java_home -v 21+)/bin/java ops/mc/fetch_121.sh
```

```bash
python3 -I mirror/generator/generate.py --dump dumps/<dump dir>
```

```bash
python3 -I -m unittest discover -s mirror/generator/tests
```
