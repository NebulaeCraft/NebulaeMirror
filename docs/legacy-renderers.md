# TileEntity-driven appearance (P1)

Some 1.12 blocks get their appearance entirely from TileEntity data (Chisels&Bits voxels, ArchitectureCraft shapes, NebulaeCraft road text, MoarSigns, Railcraft outfitted-track kits). The mirror keeps that data **unchanged** in the `nebulaemirror:legacy` block entity (`Legacy` tag, written back as-is) and draws from it on the client.

## Data flow

1. **At import** (`importer/RenderDataDeriver`), the parts of the TileEntity that depend on 1.12 numeric IDs are translated into 1.21 block states and stored separately in a `Render` tag:
   - Chisels&Bits: `{Kind:"cb", States:[{Id, State}]}`, mapping each blob palette entry (`id | meta<<12`) to a 1.21 state;
   - ArchitectureCraft: `{Kind:"ac", Base, Second?}`, the base/secondary material as 1.21 states.

   States are resolved by `StateResolver`: modded blocks go through `forward.json`; vanilla blocks go through Minecraft's own 1.12 flattening table (`BlockStateData`) plus the subsequent block-state data fixes. `Render` is derived data: write-back ignores it and it can be regenerated at any time.
2. **On the client**, `LegacyBlockEntity#getRenderData()` decodes it once (cached until the data changes). Blocks with legacy data have their static model wrapped in `LegacyModel`. While chunks are meshed, the data is passed in through Fabric's block-view API, the matching quads are emitted, and they go straight into the chunk mesh. So there is **no per-frame cost**, unlike a BlockEntityRenderer.
3. **Text blocks** (roadmark_text, MoarSigns) cannot be meshed: their text is rasterised or drawn with the font at run time. They use `LegacyTextRenderer`, a BlockEntityRenderer. All legacy block entities share one type, so `BlockEntityRenderDispatcherMixin` makes `getRenderer` return null for every legacy block entity that is not a text block. Vanilla and Sodium both ask `getRenderer` while meshing a section, so the ~3.7 million C&B/AC block entities never enter the per-frame list. Text blocks need no `Render` tag and work on worlds imported before P1.

## Chisels&Bits

- `legacy/ChiselsBlob`: an independent reader for C&B 1.12's compact voxel format (zlib + varint palette + bit stream; voxel index `x | y<<4 | z<<8`). Only the compact format (version 0) exists in the world.
- `client/legacy/ChiselsRender`: greedy meshing per face and depth layer. Each bit state uses its own 1.21 model's per-face sprite and tint, and the render layer follows that state. A face is drawn only if the neighbouring voxel is air or a different see-through state. Each block's mesh is built once and cached.
- Verification:
  - a unit test decodes a real blob from the world and compares voxel counts with an independent Python decoder (3960/64/72);
  - in a test-area tour, a single view built 17,248–29,489 C&B meshes (400k–1.17M quads), and thin details such as tower mullions and window frames render.

## ArchitectureCraft

Ported from ArchitectureCraft 1.12 (MIT; see `mirror/mod/THIRD_PARTY_NOTICES.txt`):

- `ArchitectureShapes`: the shape table (EnumShape) plus the 108 bundled `.objson` models;
- `ArchitectureRender`: for MODEL kinds, model → rotation `sideRotations[side]·turnRotations[turn]` (plus `offsetX` for banisters) → block space. Texture slots 0/1 = base material (model UVs / projected from the face direction), 2/3 = secondary material (defaults to the base). Meshes are shared by shape + orientation + materials;
- `ArchitectureRoofs`: procedural roofs. Covers the 4 roof shapes in use: tile ×58,291, outer corner, inner corner, ridge. Smart ridge/valley neighbour connections are not reproduced; the world has no valleys.
- `ArchitectureWindows` (RenderWindow, WindowShapeKinds): window frame / corner / mullion (shapes 30–32; 764 frames and 8 mullions in the world, none with glass). A frame edge is drawn only where no matching window continues, so the mesh depends on the neighbours: their render data is read while the chunk is meshed (including the `Disconnected` bits) and the resulting frame flags are part of the mesh key. Glass (the secondary material) keeps its own render layer.
- Not ported: cladding (60) and smart ridge/valley roofs; the world uses none of them. They would show as a full block of the base material.
- Verification: in the screenshots, bridge piers, building cornices and the metro station track bed (iron roof shapes) no longer show missing textures; 12–15 shared meshes cover each area. At the station at (−970, 69, 321) each pair of adjacent window frames joins into one opening with frames only on the outer edges.

## NebulaeCraft roadmark_text

Ported from NebulaeCraft 2.26 `RenderRoadmarkText`. `RoadmarkText`/`RoadmarkColor` (white or yellow) are rasterised with the mod's road-marking fonts (Latin font first, full font for other characters) into a 180×390 texture, laid flat 0.01 above the block and stretched over 1.25 (one character) or 1.75 (two or more) × 1.875 blocks, rotated by `facing`. Textures are shared per text + colour (1347 blocks in the world). View distance 224 blocks as in 1.12, and drawn even when the block itself is off-screen, since the quad is larger than the block. The fonts come from the generator output (`assets/nebulaemirror/roadmark/`).

## MoarSigns

Ported from MoarSigns 1.12 `MoarSignRenderer`/`ModelMoarSign`/`TileEntityMoarSign` (LGPL-3.0, see `THIRD_PARTY_NOTICES.txt`): the 24×12×2 board (+ stick for standing signs) with the sign's texture (`texture` + `isMetal`; the world uses 12 vanilla-material textures), placed from the 1.12 metadata (standing rotation, wall side, flat/ground flags), then up to four rows of JSON text with MoarSigns' per-row offset, size, visibility and shadow (`settings`, NBT versions 1 and 2) and its width limits. 4692 signs in the world. The block's 1.12 placeholder model is not drawn (it was `INVISIBLE` in 1.12).

## Railcraft tanks

All 11,785 tank walls/valves in the world are unformed (`pattern: -1`, no master, no fluid). An unformed Railcraft 12 tank wall is a plain tinted column model, which the static mesh already reproduces (including the 16 colours), so no renderer is needed. Formed tanks would need the fluid TESR; revisit only if formed tanks appear.

## Railcraft outfitted tracks

`railcraft:track_outfitted` (3080 in the world, TileEntities `railcraft:track.outfitted` / `.ticking`) takes its look from the TileEntity: `rail` (track type), `kit` (track kit) and the kit's render state. Railcraft 12's `OutfittedTrackModel` composes them per (type, kit, shape, state): a COMPOSITE kit draws the type's rail model plus the kit model, a UNIFIED kit (turnout, wye, junction) draws one combined model. This server's Railcraft build also hides COMPOSITE kits on electric / high-speed / high-speed-electric / reinforced rails (except buffer stops) unless the player wears the track goggles' aura, so for example a reinforced booster looks like a plain reinforced rail.

The mirror doesn't recompose any of that. The data flows like this:

1. **Dumper 0.2.1** (`TrackKitExporter`, reflection only) builds the extended block state for every rail type × kit × shape × render state and bakes Railcraft's own composite model with the aura off. The result goes to `extras/railcraft_tracks.json` (see [dumper.md](dumper.md)).
2. **Generator** (`nebgen/tracks.py`) turns each variant into an ordinary mesh, deduplicated with all the others, plus `assets/nebulaemirror/legacy/railcraft_tracks.json` (`"<rail>|<kit>|<shape>|<state>"` → `nebulaemirror:mesh/<hash>`).
3. **Client**: the model loading plugin loads the table and bakes its meshes as extra models. `client/legacy/OutfittedTrack` is a chunk-meshed `LegacyRender` (no BlockEntityRenderer). While the chunk is meshed it computes the key, looks up the baked mesh, emits it into the chunk mesh and suppresses the static default-kit model. When the key is unknown (old generator output, unknown kit), the static model is drawn as before.

The key comes from the 1.12 TileEntity alone (`legacy/OutfittedTrackKey`), so there is **no `Render` tag and no re-import**. The shape comes from the mirror block's `shape` property, falling back to the 1.12 meta. The state is a port of each kit's `getRenderState()`:

| Kit | State |
|---|---|
| turnout | switched +1, `Reversed` +2, mirrored (`Direction`) +4; switched = not `locked` and (`Switched` or `sprung`) |
| wye | (mirrored ≠ switched) +1, (mirrored ≠ shape is east_west) +2 |
| buffer | `direction` |
| control | `powered` xor `reversed` |
| transition | `reversed` +1, `powered` +2 |
| one_way | `powered` +1, `direction` +2 |
| booster, activator, launcher, priming, embarking, whistle, messenger, dumping, routing | `powered` |
| disembarking, gated | ported as in Railcraft |
| junction, and the kits that aren't ported (locking, delayed, detector, coupler, locomotive, throttle; none in the world) | 0 |

The world uses 6 rail types and 11 kits (reinforced turnout ×1917, buffer ×571, wye ×248, booster ×248, …), which works out to 86 distinct keys. Switch states are a snapshot of the saved TileEntity: a turnout switched at the moment the chunk was saved stays shown switched.

- Verification:
  - `OutfittedTrackKeyTest` checks the keys against real TileEntity NBT from the world. The generator test checks variant → mesh deduplication and the table, and that a dump without the file only produces a warning.
  - Full modpack dump `20261107-200107` (MirrorDumper 0.2.1): 4214 variants, no track errors. The models match the earlier dev-client dump: same quads, with UVs differing by less than 0.01 texel because of the larger atlas. Generated with 0 errors into 1046 track meshes, all kit textures included.
  - A tour of the imported world loaded all 4214 variants and drew 449 tracks, with 0 unmatched keys. In the screenshots, the iron turnouts and wyes at (−491, 68, −359) and the reinforced turnouts in the station at (−497, 67, −234) show their switch rails, with the buffer stop at the end. The iron control tracks at (−3000, 102, 599) show the control plates, including on the slope. The high-speed boosters show as plain rails (hidden kit).

## Chisel connected textures (CTM)

Converted at generation time into Continuity rules (see [generator.md](generator.md)); the client needs Continuity. Checked with and without Continuity at Chisel iron/invar plating (−529, 83, 138): with it, adjacent blocks merge into large panels with borders only on the outside, as in 1.12.

## Other fixes

- The generator now repairs `.mcmeta` animation files that 1.12 tolerated but 1.21 rejects (trailing commas, UTF-8 BOM): one Chisel file and 32 Sakura fluid files.

## Not covered

| Item | Status |
|---|---|
| Railcraft outfitted tracks | Drawn from TE data once the dump is made with MirrorDumper 0.2.1 (`extras/railcraft_tracks.json`); with an older dump, the default kit model. Kits locking/delayed/detector/coupler/locomotive/throttle (not in the world) always show state 0 |
| ArchitectureCraft cladding, smart ridge/valley roofs | Not used in the world; full block of the base material |
| CTM `random`/`AR` | Same tiles as 1.12, but which block gets which tile differs |
| CTM `pillar` | Only vertical columns connect |

**Note**: a world imported before P1 has no `Render` tag, so C&B/AC blocks (including windows) stay as before (invisible or missing texture). Re-run the import to fill it in. Text blocks and CTM work without re-importing.
