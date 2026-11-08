# MirrorDumper (1.12.2 client mod)

Exports everything the 1.21 mirror needs to reproduce **every block's appearance**. It reads models after Minecraft has baked them, so OBJ, Forge v1 blockstates, multipart, custom IBakedModel, and NebulaeCraft's emissive wrapper are all handled the same way, with no per-mod adaptation.

## Where to run it

The client must be a **real, playable 1.12.2 Forge client**, loading:

- the **same versions** of every content mod as the origin server (from `data/mods.zip`);
- the **2.26** NebulaeCraft jar built from source, not the 2.25 one currently on the server;
- client-side rendering dependencies such as **CTM**, which Chisel needs on the client;
- `MirrorDumper-1.12.2-<version>.jar` (built output is in `legacy/dumper/build/libs/`).

Leave out server-only plugins and mods: SpongeForge, Nucleus, LuckPerms, Prism, ServerListPlus, Dynmap, and similar.

## How to run it

Either way works:

1. **Auto mode**: add these JVM arguments in the launcher. The dump runs once the main menu appears, then the game exits:
   `-Dmirrordump.auto=true -Dmirrordump.exit=true`
   Optional: `-Dmirrordump.mods=nebulaecraft,chisel` (only dump these namespaces), `-Dmirrordump.maxStates=4096`.
2. **In-game command**: in any single-player world, run
   `/mirrordump [mods=a,b] [vanillaModels] [noVanillaTextures] [maxStates=N]`

The game freezes for a few seconds up to a few minutes while it runs. Output goes to `<game dir>/mirrordump/<timestamp>/`; copy it into this repo's `dumps/`.

## Re-dumping for Railcraft outfitted tracks (MirrorDumper 1.2, formerly 0.2.1)

The mirror draws outfitted tracks (turnouts, wyes, buffer stops, boosters, …) from their TileEntity only once the dump contains `extras/railcraft_tracks.json`. Dumps made with 0.1.0 (such as `dumps/full-20261107-145222`) don't have it, and neither does a 0.2.0 dump from a production client: 0.2.0 looked up `TrackType.getName()` by name, but in Railcraft's production jar that method is reobfuscated to `func_176610_l`, so the export failed with `NoSuchMethodException` in `errors.json`. (The dev client remaps it back to `getName`, which hid the bug.) For all of these the generator then warns `dump has no extras/railcraft_tracks.json` and tracks keep the default-kit model. To fill it in, on the machine with the full 1.12 client (`dumps/full` is a symlink to the current full dump, `full-20261107-200107` made with 0.2.1; repoint it after a new dump):

1. Build the dumper here and replace `MirrorDumper-1.12.2-0.1.0.jar` in the client's `mods/` with `legacy/dumper/build/libs/MirrorDumper-1.12.2-1.3.jar` (remove any 0.2.0 or 0.2.1 jar).
2. Run a full dump as usual (auto mode or `/mirrordump`). Railcraft must be dumped in full: if you pass `mods=`, include `railcraft`. Don't wear the track goggles' aura during `/mirrordump`: the bake reproduces what players normally see, and Railcraft (this server's build) hides kits on electric/high-speed/reinforced rails, except buffer stops, unless that aura is on.
3. Check the log line `Dump finished: …` and `manifest.json` → `summary.trackKitVariants`. It must not be 0; the Railcraft 12.1.1 pack gives 4214 ( every rail type × kit × shape × state, 1046 distinct models). Railcraft-related problems are logged in `errors.json` under `railcraft:track_outfitted` / stage `trackKits`.
4. Copy the dump into `dumps/`, re-run the generator (`report.md` counts `tracks.variants` / `tracks.meshes`) and rebuild the mod. No re-import is needed: the client reads the TileEntity data the import already stored.

The extra file only adds models; everything else in the dump is unchanged, so a full re-dump with 1.2 (or 0.2.1) replaces the earlier dump one to one. Verified on the modpack client with `20261107-200107`: `trackKitVariants` 4214, no track errors.

## Output format (schema 1)

| File | Contents |
|---|---|
| `manifest.json` | MC/Forge versions, mod list with versions, options, summary stats |
| `blocks/index.json` | Every block: file path, state count, flags |
| `blocks/<ns>/<name>.json` | Properties, meta table (`roundTrip`/`invalid`), `storedProperties`/`derivedProperties`, item subtypes (with `item` id), and for each state: light, opacity, render layers, bounds/collision, whether it has a TE, `drop` (damageDropped) / `pick` (pick-block damage), model references and tint colours |
| `models/<h0h1>/<sha1>.json` | Baked quads of one (state, render layer), deduplicated by content. Vertices are block-space coordinates plus sprite-relative 0..16 UVs; fields include `cull`, `tint`, `shade`, `emissive` |
| `textures/<ns>/…png(.mcmeta)` | Original textures at source resolution (CTM mcmeta references are followed). Sprites generated at runtime (e.g. Railcraft's `*_0..N`) are read back from the atlas |
| `sprites.json` | Size, frame count and source of each sprite (`copied`/`atlas`/`missing`/`vanilla-skipped`) |
| `lang/<ns>/{en_us,zh_cn}.lang` | Language files for each namespace |
| `biomes.json` | Biome temperature, rainfall and grass/foliage/water colours |
| `extras/railcraft_tracks.json` | (1.2+, formerly 0.2.1+) Railcraft outfitted tracks: the composite model baked for every rail type × kit × shape × kit render state, keyed `<rail>\|<kit>\|<shape>\|<state>` with the TileEntity's own NBT names, each with the same `ao`/`particle`/`layers` refs as a state's `model`. Written only when Railcraft is loaded and dumped in full |
| `errors.json` | Every exception, by block, state and stage |

By default `minecraft:*` blocks only get properties and the meta table, with no quads exported. The mirror uses 1.21's own vanilla assets, and vanilla blocks are converted by DFU. The vanilla textures that modded models reference are copied by default (`noVanillaTextures` turns this off). The generator prefers the renamed 1.21 texture and only falls back to these 1.12 PNGs when there is no 1.21 equivalent.

## What's been verified (NebulaeCraft dev environment, NebulaeCraft 2.26 + Railcraft)

- 851 blocks / 18,341 states / 6,053 models, 0 errors, 4.6 s.
- Meta encodings match the source: horizontal facing `meta=index-2`; tdt `range<<2|horizontalIndex`.
- Pane connection properties are classified as `derivedProperties`.
- The OBJ fire extinguisher box baked to 56 quads.
- The lamp's glowing faces are flagged `emissive`.
- Railcraft's runtime-split sprites read back from the atlas match the source sheet tiles exactly.

## Known gaps (to be covered by other components)

- `roadmark_text` has no quads; it is drawn entirely by a TESR, which will be ported in P1.
- Chisels&Bits / ArchitectureCraft / Railcraft tank appearances depend on TE data; the dump only contains their base models. Railcraft outfitted tracks are the exception: 1.2 (0.2.1) bakes their TE-driven variants into `extras/railcraft_tracks.json`.
- For CTM blocks only the base (unconnected) quads are exported; connection rules stay in the copied mcmeta.
- There are still 1106 `nebulaecraft:device_apple_iphone` and 1 `nebulaecraft:countdown` in the world. Both were removed from NebulaeCraft long ago (absent from 2.25 and 2.26), and the origin's FML registry still reserves their IDs. The mirror treats them as unknown blocks and passes them through unchanged.
