# TileEntity-driven appearance (P1)

Some 1.12 blocks get their appearance entirely from TileEntity data (Chisels&Bits voxels, ArchitectureCraft shapes). The mirror keeps that data **unchanged** in the `nebulaemirror:legacy` block entity (`Legacy` tag, written back as-is) and draws from it on the client.

## Data flow

1. **At import** (`importer/RenderDataDeriver`), the parts of the TileEntity that depend on 1.12 numeric IDs are translated into 1.21 block states and stored separately in a `Render` tag:
   - Chisels&Bits: `{Kind:"cb", States:[{Id, State}]}`, mapping each blob palette entry (`id | meta<<12`) to a 1.21 state;
   - ArchitectureCraft: `{Kind:"ac", Base, Second?}`, the base/secondary material as 1.21 states.

   States are resolved by `StateResolver`: modded blocks go through `forward.json`; vanilla blocks go through Minecraft's own 1.12 flattening table (`BlockStateData`) plus the subsequent block-state data fixes. `Render` is derived data: write-back ignores it and it can be regenerated at any time.
2. **On the client**, `LegacyBlockEntity#getRenderData()` decodes it once (cached until the data changes). Blocks with legacy data have their static model wrapped in `LegacyModel`. While chunks are meshed, the data is passed in through Fabric's block-view API, the matching quads are emitted, and they go straight into the chunk mesh. So there is **no per-frame cost**, unlike a BlockEntityRenderer.

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
- Not ported: windows (30–32, about 770 in the world) and cladding (60). These show as a full block of the base material for now.
- Verification: in the screenshots, bridge piers, building cornices and the metro station track bed (iron roof shapes) no longer show missing textures; 12–15 shared meshes cover each area.

## Other fixes

- The generator now repairs `.mcmeta` animation files that 1.12 tolerated but 1.21 rejects (trailing commas, UTF-8 BOM): one Chisel file and 32 Sakura fluid files.

## Still to do (P1)

| Item | Plan |
|---|---|
| NebulaeCraft `roadmark_text` | Text in the TE (`RoadmarkText`/`RoadmarkColor`); needs a ported TESR (bundled font rasterised to a texture) |
| moarsigns | Text in the TE; draw it like a vanilla sign |
| Railcraft tanks/multiblocks | Appearance from TE plus neighbours; currently the static base model |
| Chisel CTM | Convert CTM mcmeta to Continuity's format |
| ArchitectureCraft windows/cladding | Port RenderWindow / RenderCladding |

**Note**: a world imported before this point has no `Render` tag, so C&B/AC blocks stay as before (invisible or missing texture). Re-run the import to fill it in.
