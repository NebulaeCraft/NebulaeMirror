# Importer (1.12.2 world → 1.21.1 mirror world)

Part of `mirror/mod`: when the mirror server starts with the right JVM argument, it converts the 1.12 overworld region files into the server's world directory before any world is loaded, then the server starts as normal. This is the "full offline import" from the plan; the online incremental sync (P2) will reuse the same chunk converter.

## Usage

1. Prepare a void flat world in the mirror server's `server.properties` (example in [ops/server/server.properties](../ops/server/server.properties)):

   ```properties
   level-type=minecraft\:flat
   generator-settings={"layers"\:[],"biome"\:"minecraft\:the_void","features"\:false,"lakes"\:false,"structure_overrides"\:[]}
   generate-structures=false
   ```

2. Get a copy of the 1.12 world (a directory containing `level.dat` and `region/`; rsync is enough).
3. Start the server with the import arguments:

   ```bash
   java -Dnebulaemirror.import=/path/to/world12 -jar fabric-server-launch.jar nogui
   ```

   Optional arguments:
   - `-Dnebulaemirror.import.regions=x1,z1,x2,z2`: import only that range of regions (for testing);
   - `-Dnebulaemirror.import.threads=8`;
   - `-Dnebulaemirror.import.overwrite=true`: allow overwriting an existing `region/`. By default it refuses to overwrite.

4. Results are written to `world/nebulaemirror-import.json`: chunk/block counts, unknown blocks (by name:meta), errors, and the 1.12 spawn point. The spawn is applied to the mirror world automatically on first start.

For development, `cd mirror/mod && ./gradlew runImportServer -PimportSource=/abs/path/world12 [-PimportRegions=...]` uses `run/import/`.

## Conversion flow (`ChunkConverter`)

1. **Lift modded blocks out**: decode 1.12 IDs and metas through the source world's `level.dat` FML registry; IDs are never hard-coded. Every non-`minecraft` block is replaced with air in the arrays and recorded in a side table together with its TileEntity.
2. **Vanilla upgrade**: the remaining purely vanilla chunk goes through Minecraft's own DataFixer (1343 → 3955). It uses the same context the server would for a "flat overworld", so no below-zero regeneration or terrain blending is scheduled. Signs, banners, beds, flower pots, double plants and so on are all handled by the official logic.
3. **Write modded blocks back**: target states from `forward.json`, written into the 1.21 section palettes:
   - Generated blocks that had a TE in 1.12, and unmapped blocks (`nebulaemirror:unknown`), get a `nebulaemirror:legacy` block entity whose `Legacy` tag = `{Name, Meta, TileEntity?}`, kept unchanged for write-back. For C&B/ArchitectureCraft a derived `Render` tag is added as well (see [legacy-renderers.md](legacy-renderers.md)).
   - Blocks whose target depends on neighbours (stairs/walls/fences/panes/fence gates/doors, including FutureMC/Quark mapped onto vanilla) are queued in `UpgradeData`, so 1.21 recomputes their connections and shapes on first load.
4. **Finish**: remove `below_zero_retrogen`/`blending_data`/`Heightmaps`, set `Status=full` and `isLightOn=false`. The server relights the chunk and rebuilds heightmaps on load.

Also handled:
- **Biomes**: modded biomes are mapped to the vanilla biome with the same climate (the generator's `biomes.json`; `sakura:bamboo_forest` → jungle, `maple_forest` → forest), then upgraded by DFU.
- **Scheduled ticks**: those belonging to modded blocks are dropped.
- **Large chunks**: chunks over 1 MB are written as `.mcc` external files.

## Verification (9 regions around spawn, x 2..4 / z −3..−1)

- 9216 chunks, **0 failures, 18 seconds** (10 threads); output 108 MB (input 94 MB). Full world estimate: about 1.5 million chunks, around 50 minutes, about 12 GB.
- `/nebulaemirror stats <chunk range>` was compared with the `ops/scan` result for the same area:
  - Every modded namespace is **identical** (NebulaeCraft 837,595 = 837,569 + 26 unknown iPhones; Chisel 8,178,652; C&B 280,686; Railcraft 2,289,266, and so on).
  - The 683,864 FutureMC/Quark blocks all landed on the right vanilla blocks.
  - Total non-air blocks 166,542,391 → 166,542,388. The 3 vanilla blocks lost were removed by 1.21 during the vanilla upgrade's own shape updates (floating torches and the like); a normal 1.21 upgrade does the same. Write-back diffs against the mirror's own checkout snapshot, so these 3 are not deleted from the origin.
  - 302,346 legacy block entities (C&B, ArchitectureCraft, Railcraft tanks, NebulaeCraft tdt/roadmark_text and so on).
- Screenshot tour (`./gradlew runTourClient -PtourWorld=world -PtourFile=/abs/tour.txt`, after copying the world into `run/tour/saves/`): roads/parking markings, viaduct and catenary, cable-stayed bridge, station, cherry trees and buildings render correctly.

## Full-world import (2026-11-07, from the `2026.11.06` backup)

- 1553 regions / **1,497,442 chunks, 0 failures, 31 minutes** (10 cores, `-Xmx8G`); output 7.9 GB.
- Matches the `ops/scan` full-world scan exactly:
  - 1,279,648,994 modded blocks;
  - 1,107 unknown placeholders (`device_apple_iphone` ×1106, `countdown` ×1).
- Also recorded in the report:
  - 3,723,724 legacy block entities;
  - 1,603,803 shape-update positions;
  - 9,010 TileEntities dropped (from blocks that map onto vanilla blocks without a legacy BE, e.g. FutureMC barrels);
  - 410,592 entities dropped (not imported yet).
- Screenshot tour at 6 spots across the map (the 6 chunk clusters with the most NebulaeCraft blocks): skyscraper facades and interiors, roads, crosswalks, viaducts, NebulaeCraft colour-block pixel art, and Sakura maple and cherry trees all render correctly.

Development command used:

```bash
cd mirror/mod && ./gradlew runImportServer -PimportSource=/abs/path/world12-full -PimportRunDir=run/full
```

## Known limitations

| Item | Status |
|---|---|
| ArchitectureCraft shape blocks | Models and roofs render (P1); windows/cladding temporarily show the base material as a full block |
| Chisels&Bits | Renders (P1) |
| Entities (armor stands, item frames, paintings, mobs, minecarts) | Not imported yet (139 dropped in the test area). Decorative entities are later work |
| Modded items in vanilla containers | Can't exist in 1.21 and are dropped (only the mirror's copy; the origin is unaffected) |
| Online incremental sync, skipping locked zones | P2 |
