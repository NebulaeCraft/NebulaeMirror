# Write-back (mirror 1.21 → origin 1.12, P3)

Edits made in the mirror go back to the origin one **zone** at a time. A zone is a box that is checked out: from checkout until it is submitted or abandoned, the mirror is authoritative there and the incremental sync leaves it alone. At the origin the bridge mod ([bridge.md](bridge.md)) locks the same box.

## Workflow

1. Select the area with WorldEdit on the mirror (`//wand`, `//pos1` / `//pos2`, …), then `/nebulaemirror zone checkout <name>`. Alternatively give the corners: `/nebulaemirror zone checkout <name> <from> <to>`. The bridge locks the box at the origin; the mirror pulls, refreshes the box from the origin and snapshots it.
2. Build in the mirror (WorldEdit, Axiom, by hand).
3. `/nebulaemirror zone lint <name>` until there are no errors.
4. `/nebulaemirror zone submit <name>`:
   1. lint again;
   2. send the changed blocks to the bridge;
   3. the bridge checks, writes its undo file, applies, reads back, unlocks and saves;
   4. the mirror pulls, refreshes the zone from the origin and compares every sent block with what came back. It prints "all N blocks came back from the origin as sent", or the differences.

   The zone is then closed (files kept in `zones/_closed/<name>-<time>/`).

   If blocks were changed at the origin since the checkout, the submit is refused with their coordinates. The zone stays checked out and locked; `submit <name> force` overwrites them.
5. Or `/nebulaemirror zone abandon <name>`: unlock at the origin, drop the mirror's edits (refreshed from the origin).

Undoing a submit at the origin: `/mirrorbridge rollback <job>` (the job id is printed by `submit`).

Configuration (mirror, `config/nebulaemirror-sync.json`):

```json
"bridge": {"url": "http://127.0.0.1:21201", "token": "<token from the origin's config/mirrorbridge.cfg>"}
```

Without `bridge`, checkout does not lock the origin (it says so) and `submit` is unavailable. The patch can still be written with `zone diff` and applied with `/mirrorbridge apply` by hand.

## Commands and permission nodes

Nodes are checked through fabric-permissions-api, so LuckPerms-Fabric manages them. Without a permissions mod each falls back to the operator level shown.

| Command | Node (fallback level) | Action |
|---|---|---|
| `zone checkout <name>` | `nebulaemirror.zone.checkout` (2) | Your current WorldEdit selection in the overworld. A non-cuboid selection (sphere, polygon, …) uses its bounding box, and says so. Needs WorldEdit for Fabric on the mirror; uses only WorldEdit's core API (tested with 7.3.8). From the console it uses the console's WorldEdit session (`//world world_minecraft:overworld`, `//pos1 x,y,z`, `//pos2 x,y,z`) |
| `zone checkout <name> <from> <to>` | `nebulaemirror.zone.checkout` (2) | Same with explicit corners (`~` works for players). Names are 1–32 characters of `a-z 0-9 _ -`; at most 256³ blocks; zones may not overlap |
| `zone list` / `info <name>` | `nebulaemirror.zone.view` (2) | Checked-out zones, lock state, last submit |
| `zone lint <name>` / `diff <name>` | `nebulaemirror.zone.view` (2) | Compare with the snapshot and check; `diff` also writes `patch.nbt`. Full report in `lint.json` |
| `zone submit <name> [force]` | `nebulaemirror.zone.submit` (2) | See the workflow |
| `zone abandon <name>` | `nebulaemirror.zone.abandon` (2) | See the workflow |
| — | `nebulaemirror.zone.others` (3) | lint / diff / submit / abandon zones checked out by someone else, and build inside them (the console always may) |
| — | `nebulaemirror.edit.anywhere` (nobody) | Change blocks outside one's checked-out zones (they are re-applied from the origin at the next sync anyway) |
| `sync status` | `nebulaemirror.sync.status` (2) | |
| `sync now` / `apply` / `refresh` / `cancel` | `nebulaemirror.sync.run` (2) | |
| `sync baseline` / `reload` | `nebulaemirror.sync.admin` (3) | |
| `stats` | `nebulaemirror.stats` (2) | |

Files: `world/nebulaemirror/zones/<name>/` holds `zone.json`, `baseline.nbt` (snapshot), `lint.json`, `patch.nbt`, and after a submit `verify.json`. Zones survive restarts. While sync is off (development), checkout takes the mirror's current content as the origin's and says so.

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

## Verification of the full loop

See [bridge.md](bridge.md#verification-mirror--bridge-end-to-end-2026-11-08):
- checkout locked the zone at the origin;
- a submit was refused for conflicts and then forced;
- 299 blocks were applied, read back and round-tripped exactly, including chest items, sign text and bed colour;
- abandon unlocked the zone;
- an unreachable bridge failed cleanly.

## Building outside zones

The mirror follows the origin everywhere outside checked-out zones:

- **Refused up front**: players cannot break or place blocks there, use items on blocks, or use buckets; they see a message telling them to check out a zone. WorldEdit edits by players skip blocks outside their zones and say so once. A player may build inside zones they checked out, or inside any zone with `nebulaemirror.zone.others`. Turn this off with `"protectOutsideZones": false` in config/nebulaemirror-sync.json. Clicks with the WorldEdit wand, the navigation wand and items bound to WorldEdit tools are left to WorldEdit (selecting points works everywhere; the tools' edits are limited like any WorldEdit edit).
- **Reverted at the next sync**: whatever still changes there. That includes commands such as `/setblock` and `/fill`, the console's WorldEdit, Axiom (it has no server-side hook to refuse it, but its writes mark chunks the usual way), opening doors, water, fire and growing plants. See [sync.md](sync.md).

Not yet tried with a real player: the in-game refusal messages and WorldEdit for players. The recording and re-applying was tested (`/setblock`, `/fill`, console `//set`, restart, `sync apply`).

## Troubleshooting

- **A submit's round trip reports "N of N blocks differ", and the zone looks as before in the mirror** (while the origin shows the change): the pull did not copy the changed region file, so the mirror refreshed the zone from a stale copy. On Windows this is what robocopy does with region files the running origin keeps open; use `"copier": "header"` in pull.json (see [sync.md](sync.md#windows)). The origin is not affected. The next pull with the header copier, or any pull after an origin restart, brings the change into the mirror.

## Not done yet

- Axiom selections are not read (Axiom has no server-side selection API); select with WorldEdit, or give the corners.
- Not tested yet with real players and FAWE at the origin: the in-game lock and the FAWE mask (see [bridge.md](bridge.md)).
