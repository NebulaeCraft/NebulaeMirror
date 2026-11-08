# NebulaeMirror

A 1.21.1 Fabric mirror server for the 1.12.2 Forge build server, so players can use modern editing tools such as WorldEdit and Axiom. The mirror only needs to match the origin's **block appearance**. Origin → mirror runs as periodic incremental sync; mirror → origin is written back per checked-out zone.

The full design is in the approved technical plan (overall architecture, sync model, plan for mod updates). Phase progress:

- [x] Coverage scanner `ops/scan/` (1.12 world → (name, meta) / TE / entity stats, plus dump coverage check)
- [x] 1.12 dumper `legacy/dumper/` (exports block properties, meta table, baked quads, shapes, textures, lang)
- [x] Generator `mirror/generator/` (dump → 1.21 block definitions, models, forward/reverse mapping), see [docs/generator.md](docs/generator.md)
- [x] Mirror mod `mirror/mod/` (Fabric 1.21.1): block registration, mesh models, items/creative tabs, legacy TE storage, unknown placeholder, see [docs/mirror-mod.md](docs/mirror-mod.md)
- [x] FutureMC/Quark → 1.21 vanilla overrides (`mapping/overrides/`)
- [x] Importer: full offline import (DFU + modded block substitution), see [docs/importer.md](docs/importer.md)
- [x] P1 TileEntity-driven appearance: Chisels&Bits voxels, ArchitectureCraft shapes/roofs/windows, NebulaeCraft roadmark_text, MoarSigns, Chisel CTM → Continuity, see [docs/legacy-renderers.md](docs/legacy-renderers.md) (clients need Continuity), Railcraft outfitted-track kits (need a MirrorDumper 1.2 / 0.2.1 dump, see [docs/dumper.md](docs/dumper.md))
- [x] P2 online incremental sync: pull (RCON `save-off`/`save-all flush` + rsync + `save-on`), timestamp + content-hash change detection, IO replacement of unloaded chunks / block diff of loaded chunks, in-mod timer + launchd keep-alive, see [docs/sync.md](docs/sync.md) (still to do: the first test against the real origin)
- [x] P3 write-back, see [docs/writeback.md](docs/writeback.md):
  - [x] step 1: reverse mapping (vanilla via DataFixer, mods via reverse.json), TileEntity/item downgrade, zones (`/nebulaemirror zone checkout|lint|diff|abandon`), patch file
  - [x] step 2: bridge mod `legacy/bridge/` (apply patch quietly, undo file, rollback, conflict check), see [docs/bridge.md](docs/bridge.md)
  - [x] step 3: zone lock at the origin (Forge events + FAWE mask), local HTTP API with token, `zone submit` with round-trip check, LuckPerms permission nodes
- [x] Chat sync: chat, joins and leaves both ways through the bridge, formats and switches in the mirror's sync config, see [docs/chat.md](docs/chat.md)
- [x] WorldEdit short commands (`//s`, `//r`, `//c`, …, configurable), see [docs/worldedit-aliases.md](docs/worldedit-aliases.md)
- [x] Client config for creative tabs (hide per mod, choose the icon), see [docs/creative-tabs.md](docs/creative-tabs.md)

## Versions

All three mods use two-part versions `1.x`; every release bumps the minor number. The renumbering on 2026-11-08 kept each mod's minor number: mirror mod 1.2 (was 0.1.0, plus the WorldEdit short commands), bridge 1.3 (same code as 0.3.0), dumper 1.2 (same code as 0.2.1). Since then: mirror mod 1.3 (creative tab config); mirror mod 1.4, bridge 1.4, dumper 1.3 (all in-game messages in Chinese). The version is set in `mirror/mod/gradle.properties` (`mod_version`), and for the Forge mods in `build.gradle` together with the `VERSION` constant in `MirrorBridge` / `MirrorDumper`.

## Layout

| Directory | Contents |
|---|---|
| `data/` | Origin server snapshots: world zip, config, mods (gitignored) |
| `legacy/dumper/` | Forge 1.12.2 client mod: exports block appearance data |
| `legacy/bridge/` | Forge 1.12.2 server mod: applies write-back patches (undo, rollback), zone locks, chat relay |
| `mirror/mod/` | Fabric 1.21.1 mod (Gradle 9.8 + Loom, Mojang mappings) |
| `mirror/generator/` | dump → mirror definition generator (Python, stdlib only) |
| `mapping/overrides/` | Hand-curated mappings (format in docs/generator.md) |
| `mapping/snapshots/` | Registry/mapping snapshots for diffing new blocks |
| `ops/scan/` | World scan and coverage check |
| `ops/sync/` | Origin pull script (RCON + rsync) and its tests |
| `ops/server/` `ops/launchd/` | Mirror server config, start script and launchd keep-alive |
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

Sync tests (pull script):

```bash
python3 -I -m unittest discover -s ops/sync/tests
```
