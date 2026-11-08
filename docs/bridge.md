# Bridge mod (origin 1.12.2, P3 step 2)

`legacy/bridge/` is a server-side Forge 1.12.2 mod (`mirrorbridge`, works with SpongeForge; clients do not need it). It applies the write-back patches the mirror produces (`/nebulaemirror zone diff`, see [writeback.md](writeback.md)) to the overworld, with an undo file per job and rollback. Step 3 adds the zone lock and the HTTP endpoint the mirror submits to; until then patches are applied by hand from an inbox folder.

## Build and install

```bash
cd legacy/bridge && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./gradlew build
```

Put `legacy/bridge/build/libs/MirrorBridge-1.12.2-0.1.0.jar` into the origin's `mods/` and restart. Files live in `world/nebulaemirror-bridge/`:

| Path | Contents |
|---|---|
| `inbox/` | Patches to apply (copy the mirror's `world/nebulaemirror/zones/<zone>/patch.nbt` here, any file name) |
| `jobs/<id>/patch.nbt` | The patch a job applied |
| `jobs/<id>/undo.nbt` | What the job replaced, written **before** the first block changes |
| `jobs/<id>/job.json` | Phase, counts, conflicts, unknown names, errors |

Job ids are `<date>-<time>-<zone>`, e.g. `20261108-102752-plaza`. `config/mirrorbridge.cfg` has `tickBudgetMillis` (default 10: main-thread time per tick).

## Commands

| Command | Permission node | Action |
|---|---|---|
| `/mirrorbridge inbox` | `nebulaemirror.bridge.status` | List the inbox |
| `/mirrorbridge apply <file> [force]` | `nebulaemirror.bridge.apply` | Apply an inbox patch; the file is removed from the inbox once the job is done (a refused one stays for a retry) |
| `/mirrorbridge rollback <job> [force]` | `nebulaemirror.bridge.rollback` | Restore what a job replaced (applies its undo file as a new job, which can itself be rolled back) |
| `/mirrorbridge status` / `jobs` | `nebulaemirror.bridge.status` | Current job / last 10 jobs |
| `/mirrorbridge cancel` | `nebulaemirror.bridge.apply` | Stop the running job; whatever was already applied can be rolled back |

The nodes go through Forge's PermissionAPI, which SpongeForge hands to LuckPerms. By default only operators have them, and the console and RCON may use everything.

## How a job runs

1. **Check** (sliced per tick):
   - every block name must be registered on this server;
   - every entry's `Expect` (what the origin had at checkout) must match what the origin has now. An entry whose target block is already there does not count as a conflict, so a partial job can be rolled back.

   The current block and TileEntity of every entry are recorded.
   - Unknown names or positions outside y 0..255 **always** refuse the job.
   - Conflicts refuse it unless `force` is given, and are listed with coordinates.
2. **Undo file** written.
3. **Apply** (sliced per tick).

   Blocks are written straight into the chunk sections, with no `breakBlock`, `onBlockAdded`, neighbour updates or Sponge/FAWE events. So:
   - a replaced chest drops nothing;
   - sand does not fall, water does not start flowing, TNT next to redstone is not primed;
   - removing a log does not make leaves decay.

   The bridge then handles the rest itself:
   - removes the old TileEntity and creates the new one from the patch (C&B, ArchitectureCraft and every other modded TileEntity is created by its id);
   - rebuilds height maps and light for the changed chunks;
   - marks the chunks for saving and sends the changes to players.

A server stop during a job marks it `interrupted` on the next start. If it was already applying, `rollback` restores the previous content.

## Verification (Forge 1.12.2 dev server, flat world, 2026-11-08)

Test patches were written with the 1.21 NBT code, as the mirror writes them. Results:

- **A** (10 blocks):
  - wool, a chest with wool and a named, enchanted sword, a sign with text, a player skull with rotation and a fern pot all came out exactly right;
  - a sand block, a flowing-water block, glowstone, and TNT next to a redstone block were placed too; 5 s later the sand had not fallen, the water had not spread, and there were no TNT or falling-block entities.
- **B** replaced A's chest with stone: no items dropped.
- **C**: its `Expect` did not match → refused, with the position and what the origin had. The file stayed in the inbox; `force` then applied it.
- **D** used `quark:stone_andesite_stairs` (not installed on the dev server) → refused even with `force`, and nothing changed.
- **E**: 20,000 blocks checked and applied in 177 ms.
- **Rollback**:
  - rolling back A while B and C had changed two of its blocks → refused, both positions listed;
  - rolling back C, B, A, then E restored everything: the chest came back with its items for B's rollback, and no items dropped anywhere;
  - every rollback is itself undoable.
- Development: `./gradlew runServer` (world in `legacy/bridge/run/world`) reads console commands from stdin. Gradle reports a failure when the server stops, because the 1.12 server's `System.exit` ends the gradle process; the world is saved first.

Not yet tried on the real origin (SpongeForge + FAWE + all mods). Before using it there, apply a small patch to an unimportant spot and roll it back.
