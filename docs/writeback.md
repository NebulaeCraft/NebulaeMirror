# Write-back (mirror 1.21 → origin 1.12, P3)

Until step 3, a zone is written back by hand: on the mirror run `/nebulaemirror zone diff <zone>`, copy `world/nebulaemirror/zones/<zone>/patch.nbt` into the origin's `world/nebulaemirror-bridge/inbox/`, run `/mirrorbridge apply patch.nbt` on the origin, then `/nebulaemirror zone abandon <zone>` on the mirror (it re-syncs the zone from the origin, which now has the edits).

Edits made in the mirror go back to the origin one **zone** at a time. A zone is a box that is checked out: from checkout until it is submitted or abandoned, the mirror is authoritative there and the incremental sync leaves it alone.

P3 is built in three steps:

| Step | Contents | Status |
|---|---|---|
| 1 | Reverse mapping (1.21 → 1.12), TileEntity downgrade, zones (checkout / lint / diff / abandon), patch file | **done** (this document) |
| 2 | Bridge mod `legacy/bridge/` (Forge 1.12): apply a patch in ticks with an undo file, `/mirrorbridge rollback`, see [bridge.md](bridge.md) | **done** |
| 3 | Zone lock at the origin, HTTP between mirror and bridge, `zone submit`, round-trip check after submit | to do |

## Commands (permission level 2)

| Command | Action |
|---|---|
| `/nebulaemirror zone checkout <name> <from> <to>` | Refresh the box from the origin (pull first if a `pullCommand` is configured), snapshot it, and exclude it from the sync. Names are 1–32 characters of `a-z 0-9 _ -`; at most 256³ blocks; zones may not overlap |
| `/nebulaemirror zone list` / `info <name>` | Checked-out zones |
| `/nebulaemirror zone lint <name>` | Compare the zone with its snapshot and check everything that would be written back. Prints errors/warnings with sample coordinates; full report in `lint.json` |
| `/nebulaemirror zone diff <name>` | Lint, and when there are no errors write `patch.nbt` (what step 3's `submit` will send) |
| `/nebulaemirror zone abandon <name>` | Drop the zone and refresh it from the origin, discarding the mirror's edits |

Files: `world/nebulaemirror/zones/<name>/` holds `zone.json`, `baseline.nbt` (snapshot), `lint.json` and `patch.nbt`. Zones survive restarts. While sync is off (development), checkout takes the mirror's current content as the origin's and says so.

## What is written back

Only blocks whose **1.12 result** differs from the snapshot. A block that only changed in a way 1.12 does not store (fence/pane/wall connections, stair shape, leaf distance, snowy grass) is not written: the origin recomputes those itself. Unchanged C&B blocks, chests and other blocks keep their original 1.12 data at the origin because they are never sent.

Each block goes through `ReverseTable` in this order:

1. Air → air.
2. `nebulaemirror:unknown` → the preserved name, meta and TileEntity, unchanged.
3. A block with a `nebulaemirror:legacy` block entity (C&B, ArchitectureCraft, NebulaeCraft TEs, MoarSigns, Railcraft tanks, …): name and meta from its **current state**, so rotating it in the mirror rotates it at the origin, plus the preserved 1.12 TileEntity with the new coordinates. Moving or copying these with WorldEdit, Axiom or `/clone` keeps their content.
4. Vanilla blocks that 1.12 vanilla also has → vanilla (see below), with the TileEntity downgraded.
5. Mirror blocks and FutureMC/Quark override targets → the generator's `reverse.json` (e.g. `minecraft:andesite_stairs` → `quark:stone_andesite_stairs`).
6. Anything else is an error (e.g. cherry planks, spruce signs: 1.12 has nothing to show).

### Vanilla blocks

The vanilla table is not hand-written. At first use it runs Minecraft's own DataFixer forward over every canonical vanilla 1.12 meta and inverts the result:

- the canonical metas come from the dump's meta table, via the generator's `vanilla_metas.json`;
- the numeric ids come from the origin's `level.dat`.

Of 1,650 canonical metas, every one maps back to itself. 67 1.21 states have more than one 1.12 preimage, and one meta is chosen for each. Examples: leaves' `check_decay` is lost, as is the 1.12 skull "nodrop" bit. For water and lava, a source is the still block and anything else flows. Floor skulls face up. A comparator's block matches its powered state.

Blocks that 1.12 split differently are handled explicitly:

- **Doors**: the lower half stores facing/open, the upper half hinge/powered.
- **Double plants**: the upper half is `double_plant:10`.
- **TileEntity-carried data**: beds (colour), banners (colour, patterns), skulls (type, rotation, player profile), flower pots (plant) and note blocks (note) become one 1.12 block plus its TileEntity.

### TileEntities and items

| 1.21 | 1.12 |
|---|---|
| Sign front text | `Text1..4` (JSON kept). Back text, text colour and glowing are dropped with a warning |
| Chest, trapped chest, furnace, dispenser, dropper, hopper, brewing stand, shulker box, jukebox | Items converted (see below); custom name, lock, loot table kept; furnace progress reset |
| Banner | `Base` and `Patterns` (1.12 counts colours as dye damage); 1.14+ patterns dropped with a warning |
| Skull / player head | `SkullType`, `Rot`, `Owner` (Id, Name, skin textures). Piglin heads are an error |
| Spawner | Entity type and timing; `SpawnPotentials` dropped with a warning |
| Command blocks, structure blocks | Error (not written back) |

Items: the id table is built the same way as the vanilla block table, from DataFixer's item ID table, item flattening and later renames. It covers 499 items. Count, damage (variant or durability), custom name, lore and enchantments (1.12 numeric ids) are kept; other components are dropped with a warning. Items with no 1.12 form are left out with a warning.

### Lint

| Code | Level | Meaning |
|---|---|---|
| `no_1_12_form` | error | Block, state or block entity 1.12 cannot represent |
| `outside_height` | error | Non-air block outside y 0..255 |
| `waterlogged` | warning | The water is dropped |
| `lossy` | warning | Written back with something left out (see the tables above) |

`diff` writes no patch while there are errors.

## Patch format (`patch.nbt`, gzip NBT)

```
{Version: 1, Zone: "name", Created: <ms>, Min: [I; x, y, z], Max: [I; x, y, z],
 Entries: [{Pos: [I; x, y, z], Name: "minecraft:wool", Meta: 14b,
            Expect: {Name, Meta},          what the origin had at checkout (for conflict detection)
            TileEntity: {id, x, y, z, ...}}, ...]}
```

Names, not numeric ids: the bridge resolves them against the origin's live registry when it applies the patch.

## Verification (dev server, 9 regions around spawn, 2026-11-08)

- `VanillaReverseTest`, which runs vanilla Minecraft inside JUnit, checks:
  - all 1,650 canonical vanilla metas round-trip;
  - flattening renames, stairs, fences, doors, double plants, lit furnace and liquids;
  - beds, flower pots, player heads with profile, wall skulls and banners with patterns;
  - chest items (wool, enchanted and named sword, short grass; a 1.21-only item left out);
  - signs, and `reverse.json` priority.
- Checkout of a 61×71×51 zone near spawn: refreshed from the origin (0 blocks changed), snapshot in 56 ms. These edits gave the patch below:
  - wool, oak stairs, andesite stairs, waterlogged fence, chest with items, sign, asphalt subtype, player head and fern pot;
  - two C&B blocks moved with `/clone`;
  - cherry planks.

  Results:
  - cherry planks → `no_1_12_form` error and no patch;
  - after removing them, a patch with every block and TileEntity correct; the two C&B blocks carry their original 1.12 data;
  - the water that flowed from the waterlogged fence shows up as `flowing_water` entries.
- With the zone checked out, an origin change inside it was not synced and one outside it was; `abandon` restored the zone (the held-back change arrived, the mirror's edits were gone).
- Zones survive a restart, overlapping checkouts are refused, and a manual `sync refresh` also skips checked-out zones.

## Not done yet (steps 2 and 3)

- Locking the zone at the origin. Until then, edits made at the origin inside a checked-out zone would be overwritten by a submit; the patch's `Expect` lets the bridge detect and refuse that.
- Sending the patch over HTTP (step 3; applying it with undo and rollback is done, [bridge.md](bridge.md)), and the round-trip check after submit.
- Permission nodes for the mirror's zone commands (LuckPerms-Fabric, via fabric-permissions-api) come with `submit` in step 3.
- Selection from WorldEdit/Axiom: `checkout` takes coordinates for now.
