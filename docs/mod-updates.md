# Updating when the origin's mods change

What to do when the 1.12.2 origin server updates **FutureMC, NebulaeCraft, Railcraft, Quark, Chisel, ...** or adds/removes a mod, so blocks are added, removed or changed. The mirror only has to match the origin's **block appearance**, so everything below is about keeping three things in step with the origin's mod list: the dump, the generated definitions (and the jar built from them), and the world.

## The short version

1. Build a 1.12.2 client with the **new** mod versions and take a full dump ([dumper.md](dumper.md)).
2. Compare it with the previous dump (`ops/scan/diff_dumps.py`).
3. Re-run the generator, fix what `report.md` complains about (overrides for FutureMC/Quark, renderers for new TE-driven blocks).
4. Build the mirror jar (bump the minor version), test, and give the new jar to the server **and every client**.
5. Re-sync the parts of the world whose conversion changed (`refresh` or re-import). The sync does **not** do this by itself.

Do these in this order, and do 1–4 **before** the origin switches to the new mods when you can. Then there is no period in which the origin has blocks the mirror does not know.

## What happens to each kind of change

| Change at the origin | Mirror work | World work |
|---|---|---|
| New block, nobody has placed it yet | Dump, generate, build, deploy. It shows up in the creative tab of its mod automatically | None |
| New block, already placed at the origin | Same | The placed blocks are `nebulaemirror:unknown` placeholders until their chunks are converted again: `refresh` (below) |
| New block that has a 1.21 vanilla equivalent (FutureMC, some Quark) | Write an override **before the first deploy** (below) | None if done first; otherwise `refresh` |
| Block removed, and none are left in the origin world | Dump, generate, build, deploy | None |
| Block removed, but stale blocks are still in the origin's region files | Same | `refresh` after deploying: they become unknown placeholders instead of vanishing |
| Block's properties, meta meaning or item subtypes changed | Same; check `CHANGED` in the dump diff | `refresh` or re-import: chunks whose content did not change are **not** converted again |
| Textures/models changed only | Dump, generate, build, deploy | None (clients just need the new jar) |
| New mod added | Same as new blocks. Check whether its blocks need a TileEntity renderer | As for new blocks |
| Mod removed | Its blocks disappear from the dump; delete its entries from `mapping/overrides/` | As for removed blocks |

## Step by step

### 1. Dump with the new versions

Follow [dumper.md](dumper.md): a real playable 1.12.2 Forge client with the **same** mod versions as the origin after the update. NebulaeCraft comes from the **source build** of the version you are mirroring (currently 2.26), not necessarily the jar on the server. Leave out server-only mods.

- Run a full dump (no `mods=` filter). Railcraft has to be dumped in full, otherwise the outfitted-track models are missing.
- Check `manifest.json` → `summary` and `errors.json`. For Railcraft, `summary.trackKitVariants` must not be 0 (4214 for Railcraft 12.1.1). New errors after a mod update usually mean the dumper's reflection no longer fits (the 0.2.0 → 0.2.1 `getName` / `func_176610_l` problem was exactly that); fix `legacy/dumper/` and bump its version.
- Copy the dump to `dumps/` and repoint the `dumps/full` symlink.

### 2. See what changed

```bash
python3 -I ops/scan/diff_dumps.py dumps/<old dump> dumps/<new dump>
```

It prints mods whose version changed, `added` and `removed` blocks, and `CHANGED` blocks whose property names/values or meta → state table differ. `CHANGED` is the dangerous one: the same stored (name, meta) now means something else, so converted chunks are wrong until they are re-converted. Vanilla blocks are skipped (DataFixer handles them).

Also re-scan a fresh copy of the origin world and check coverage ([README](../README.md#common-commands)):

```bash
python3 -I ops/scan/scan_world.py <origin world copy> --out work/world-stats.json --workers 8
python3 -I ops/scan/scan_world.py --stats work/world-stats.json --dump dumps/<new dump>
```

`blocksMissingFromDump` should only list blocks that are really gone (today the 2 old NebulaeCraft blocks). Anything else means the dump client lacks a mod or has the wrong version. The stats file also tells you how many blocks of a given `ns:name:meta` the origin still holds (`states`), which decides the "removed" case below. It has counts only, no coordinates.

### 3. Generate and read the report

```bash
python3 -I mirror/generator/generate.py --dump dumps/full
```

Errors must be 0 (the self-check also proves every meta goes forward and back to itself). In `work/gen/report.md` look at:

- `override for a block that is not in the dump`: the block was removed or renamed; delete or rename the entry in `mapping/overrides/`.
- Warnings grouped by type, for the **new** blocks: missing textures, blocks with no quads, `reverseAmbiguous`.
- Texture lists: textures missing in 1.12 too are an origin-mod bug, not a mirror one.

Vanilla-equivalent blocks (FutureMC, Quark):

- FutureMC backports 1.13–1.16 blocks. A new FutureMC block is generated as an ordinary mod block unless an override maps it onto the 1.21 block. Add the rule to `mapping/overrides/futuremc.json` (format in [generator.md](generator.md#hand-written-mappings-mappingoverridesjson)): `target`, the property renames in `props`, value renames in `values`, per-meta exceptions in `metas`. The generator checks every target property and value against the 1.21.1 block report, so a wrong rule is an error, not silent damage.
- **Decide this before the first deploy.** If `futuremc:new_block` is generated and shipped, the mirror world will contain it; changing the rule later turns it into a different block and needs a `refresh`.
- When several 1.12 blocks map onto one vanilla block, check `reverseAmbiguous` in the report: it decides which 1.12 block a builder's vanilla block is written back as.
- Quark: same, in `quark.json`. Quark-only blocks are simply generated.
- A block that must stay a placeholder, because it is gone or broken but still in the dump: `{"unknown": true}`. That is how `device_apple_iphone` is handled. A block that is no longer in the dump needs nothing: its (name, meta) has no mapping and the importer makes it an unknown placeholder by itself.

### 4. Check the renderers for mod-specific code

The mirror has code that depends on a mod's internal data. After a version update of these mods, look at them:

| Mod | What the mirror depends on | Check |
|---|---|---|
| Railcraft | Outfitted tracks: TE ids `railcraft:track.outfitted` / `.ticking`, the NBT names `rail`, `kit` and each kit's state fields, the track-kit table in [legacy-renderers.md](legacy-renderers.md#railcraft-outfitted-tracks) | `trackKitVariants`, `errors.json`, `report.md` `tracks.variants`; new kits or renamed NBT fields need a change in `OutfittedTrackKey`/`OutfittedTrack` |
| Chisels & Bits | The compact voxel format (`ChiselsBlob`) | A new blob format version is not read; `ChiselsBlobTest` has a real blob |
| ArchitectureCraft | The shape table and 108 `.objson` models bundled in the jar, roof and window logic | New shapes or models need porting; unknown shapes are not drawn correctly |
| Chisel | CTM `.mcmeta` rules → Continuity | `ctm.*` counts in `report.md`; new CTM types are not converted |
| MoarSigns | Sign textures (from `--mods`, the origin's mods zip) and TE fields | Pass the new mods zip to the generator |
| NebulaeCraft | `roadmark_text` renderer and fonts (`--nebulaecraft`, the 2.26 source resources) | Point `--nebulaecraft` at the new source |
| Quark | Flower pot contents are static | None |

A new TileEntity-driven block of any mod shows only its static model until a renderer exists ([legacy-renderers.md](legacy-renderers.md)). The data is kept unchanged in the legacy block entity either way, so nothing is lost and write-back still works.

### 5. Build, test, deploy

```bash
python3 -I -m unittest discover -s mirror/generator/tests
cd mirror/mod && ./gradlew build && ./gradlew runSelftestClient
```

The self-test lists block states and items without a mesh model (`nebulaemirror-selftest.json`). Bump `mod_version` in `mirror/mod/gradle.properties` (every release bumps the minor number, see the README) and note it in the README's Versions section.

The generated registry is bundled in the jar, so **the new jar goes to the mirror server and to every client**; players with an old jar cannot join (the block registries differ). Players need Continuity as before. Restart the mirror server; changes to blocks are never hot-loaded. Rebuild the bridge/dumper only if their own code changed.

### 6. Bring the world in line

The sync compares chunk **content** at the origin, so after a mapping change it re-converts only chunks that changed at the origin. Chunks that did not change keep whatever the mirror holds. Three tools:

| Tool | Use for | Cost and side effects |
|---|---|---|
| Nothing | New blocks not yet placed; removed blocks with 0 left in the origin world | |
| `/nebulaemirror sync refresh <cx1> <cz1> <cx2> <cz2>` | Re-convert a known chunk range from the origin with the new mapping | About 275 chunks/s (9,216 chunks took 33 s); the whole world would take roughly 90 minutes. **Undoes mirror edits** in that range, except checked-out zones. Needs a fresh pull first (`sync now`) so `source` is current |
| Full re-import ([importer.md](importer.md)) | A mapping change that touches many areas | About 31 minutes offline for the whole world. Replaces the whole mirror world, **including edits and zones that have not been submitted**: submit or abandon all zones first. It writes a new sync baseline |

How to choose the chunk range: there is no tool that lists where a block is. Use what you know (the area builders placed the new blocks in, where the changed block is used), or refresh the whole inhabited range. Counting a block in the origin world copy (`states` in the stats file) tells you whether any exist.

**Removed blocks.** A block missing from the jar is not in the registry any more, so a saved mirror chunk that still names it will most likely load that position as air, as any removed mod does (not tested here). If the fresh scan finds none of the block in the origin world, no mirror chunk holds it (outside zones that are still checked out and unsynced edits), and you can deploy. If stale ones remain in the origin's region files (this is how the iPhone blocks still exist), run `refresh` over their area right after the restart so they become unknown placeholders, which keep the original data for write-back.

Order for an update with removals or mapping changes: `sync now` → deploy and restart → `refresh` the affected areas → `/nebulaemirror sync status` (the unknown-block count in `last-run.json` and `nebulaemirror-import.json`'s unknown list should only hold blocks you expect).

`resolve-unknown` (automatic re-conversion of old placeholders) is planned but **does not exist yet**; `refresh` is the way until then.

### 7. Check

- `/nebulaemirror stats <chunk range>` against `ops/scan` for the same area: every modded namespace should match, and only the expected blocks are unknown.
- Place the new blocks from the creative tab, look at them (the self-test icon sheets in `run/selftest/nebulaemirror-icons/` compare with the 1.12 game), and write one back with a small zone (`zone lint`, `zone submit`) to prove the reverse mapping: the submit's round-trip check reports any difference.
- For removed blocks: a placeholder breaks and places back to the original name/meta; the sync summary has no unexpected errors.

## When a mod update also changes the world format

If the origin's update migrates data inside the world (renumbered metas, converted TileEntities), that happens at the origin; the mirror sees it as ordinary content changes and re-converts the chunks that were saved. Do the dump and generator steps first, so that the mapping is already the new one when the first changed chunks arrive.

## Checklist

- [ ] Origin mod versions and the new `data/mods.zip` known; dump client has the same ones (NebulaeCraft from source)
- [ ] Full dump, `errors.json` checked, Railcraft `trackKitVariants` ≠ 0, `dumps/full` repointed
- [ ] `diff_dumps.py` read: added / removed / `CHANGED`
- [ ] Fresh origin scan: coverage, counts of removed blocks
- [ ] Overrides updated (new FutureMC/Quark blocks, stale entries deleted)
- [ ] Generator: 0 errors, `report.md` read
- [ ] Renderers checked for Railcraft / C&B / ArchitectureCraft / Chisel / MoarSigns / NebulaeCraft
- [ ] Tests and self-test pass, version bumped, README Versions updated
- [ ] Jar given to the server and all clients, server restarted
- [ ] `refresh` (or re-import) for changed areas; zones submitted or abandoned first when re-importing
- [ ] Stats and a write-back round trip checked
