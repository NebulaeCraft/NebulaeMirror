# Bridge mod (origin 1.12.2, P3 steps 2 and 3)

`legacy/bridge/` is a server-side Forge 1.12.2 mod (`mirrorbridge`, works with SpongeForge; clients do not need it). It does five things:

- locks the zones the mirror checks out, so nothing changes them at the origin meanwhile;
- offers a local HTTP API the mirror uses to lock, unlock and submit;
- applies the write-back patches (see [writeback.md](writeback.md)) to the overworld;
- keeps an undo file per job and can roll a job back.
- relays chat, joins and leaves between the two servers (1.3, formerly 0.3.0; see [chat.md](chat.md)).

## Build and install

```bash
cd legacy/bridge && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./gradlew build
```

Put `legacy/bridge/build/libs/MirrorBridge-1.12.2-1.3.jar` into the origin's `mods/` and restart. Files live in `world/nebulaemirror-bridge/`:

| Path | Contents |
|---|---|
| `inbox/` | Patches to apply (copy the mirror's `world/nebulaemirror/zones/<zone>/patch.nbt` here, any file name) |
| `jobs/<id>/patch.nbt` | The patch a job applied |
| `jobs/<id>/undo.nbt` | What the job replaced, written **before** the first block changes |
| `jobs/<id>/job.json` | Phase, counts, conflicts, unknown names, read-back mismatches, errors |
| `locks.json` | Locked zones |

Job ids are `<date>-<time>-<zone>`, e.g. `20261108-102752-plaza`.

`config/mirrorbridge.cfg`:

| Setting | Default | Meaning |
|---|---|---|
| `tickBudgetMillis` | 10 | Main-thread time per tick for a job |
| `httpEnabled` | true | Run the HTTP API |
| `httpHost` | 127.0.0.1 | Listen address; keep it while the mirror is on the same machine |
| `httpPort` | 21201 | Port |
| `token` | generated on first start | Shared secret; copy it into the mirror's `config/nebulaemirror-sync.json` (`bridge.token`) |

**FAWE**: add `net.kuina.nebulaemirror.bridge.WorldEditLock$LockedExtent` to `extent.allowed-plugins` in `config/FastAsyncWorldEdit/config.yml` (the exact path depends on the FAWE version). Otherwise FAWE ignores the lock mask and logs `Potentially unsafe extent blocked`.

## Commands

| Command | Permission node | Action |
|---|---|---|
| `/mirrorbridge inbox` | `nebulaemirror.bridge.status` | List the inbox |
| `/mirrorbridge apply <file> [force]` | `nebulaemirror.bridge.apply` | Apply an inbox patch; the file is removed from the inbox once the job is done (a refused one stays for a retry) |
| `/mirrorbridge rollback <job> [force]` | `nebulaemirror.bridge.rollback` | Restore what a job replaced (applies its undo file as a new job, which can itself be rolled back) |
| `/mirrorbridge status` / `jobs` | `nebulaemirror.bridge.status` | Current job / last 10 jobs |
| `/mirrorbridge cancel` | `nebulaemirror.bridge.apply` | Stop the running job; whatever was already applied can be rolled back |
| `/mirrorbridge locks` | `nebulaemirror.bridge.status` | Locked zones |
| `/mirrorbridge unlock <zone>` | `nebulaemirror.bridge.lock` | Release a lock by hand (e.g. when the mirror lost the zone); the mirror normally does this |
| — | `nebulaemirror.bridge.bypasslock` | Edit inside locked zones anyway. **Nobody has it by default, operators included** |

The nodes go through Forge's PermissionAPI, which SpongeForge hands to LuckPerms. By default only operators have them (except `bypasslock`). The console and RCON may use every command.

## Zone locks

While the mirror has a zone checked out, the bridge stops:
- players breaking, placing, using buckets on, or right-clicking blocks there (doors, levers, chests, …), with a message naming the zone;
- entities placing blocks, explosions destroying them, and fluids flowing in;
- WorldEdit/FAWE edits (an extent mask, the way WorldGuard does it; see the FAWE note above).

Not covered: `/setblock`, `/fill` and `/clone` run by operators or the console, pistons, and plants growing. A change from any of those makes the zone's `Expect` stop matching, so the submit is refused and lists the coordinates. `submit force` overwrites them.

A submit job unlocks its zone when it finishes cleanly; a refused or failed one leaves the lock in place. Locks survive restarts.

## HTTP API

The mirror uses it; nothing else needs to. Every request carries `X-Mirror-Token: <token>`, and all work runs on the server thread.

| Request | Answer |
|---|---|
| `GET /v1/status` | version, locks, current job |
| `POST /v1/lock` `{zone, min, max, owner}` | locked, or 409 if it overlaps another lock |
| `POST /v1/unlock` `{zone}` | `{unlocked: true/false}` |
| `POST /v1/submit?zone=Z[&force=true]`, body: patch | `{job}`. The zone must be locked here (unless `force`) and every entry must lie inside the lock |
| `GET /v1/job?id=J` | the job report (job.json) |
| `GET /v1/chat?boot=B&since=S&wait=W` | origin chat, joins and leaves after `S` (long poll, at most `W` ≤ 30 s): `{boot, seq, events}`. `since=-1`: only the current `seq`. A different `boot` (the bridge restarted): everything buffered since this start |
| `POST /v1/chat/say` `{text}` | show mirror text (already formatted, `§` codes, at most 1024 characters) to every player |

## How a job runs

1. **Check** (sliced per tick):
   - every block name must be registered on this server;
   - every entry's `Expect` (what the origin had at checkout) must match what the origin has now. An entry whose target block is already there does not count as a conflict, so a partial job can be rolled back.

   The current block and TileEntity of every entry are recorded.
   - Unknown names or positions outside y 0..255 **always** refuse the job.
   - Conflicts refuse it unless `force` is given, and are listed with coordinates.
2. **Undo file** written.
3. **Apply** (sliced per tick).
4. **Read-back** (sliced per tick): every entry's block, meta and TileEntity id is compared with the patch; differences are counted and sampled in the report.
5. For a submit: unlock the zone and save the world, so the mirror's round-trip pull sees the result. Saving does nothing while saving is off, i.e. during a pull.

   Blocks are written straight into the chunk sections, with no `breakBlock`, `onBlockAdded`, neighbour updates or Sponge/FAWE events. So:
   - a replaced chest drops nothing;
   - sand does not fall, water does not start flowing, TNT next to redstone is not primed;
   - removing a log does not make leaves decay.

   The bridge then handles the rest itself:
   - removes the old TileEntity and creates the new one from the patch (C&B, ArchitectureCraft and every other modded TileEntity is created by its id);
   - rebuilds height maps and light for the changed chunks;
   - marks the chunks for saving and sends the changes to players.

A server stop during a job marks it `interrupted` on the next start. If it was already applying, `rollback` restores the previous content.

## Verification: mirror → bridge end to end (2026-11-08)

Setup: the mirror dev server and a Forge 1.12.2 dev server with the bridge, on one machine. The mirror's sync source is pulled from the dev world by `ops/sync/pull.py`.

- **Checkout** of `z1`: the zone was locked at the origin (`/v1/status` listed it), pulled and refreshed, then snapshotted.
- **Edits**: wool, a torch, stairs, fences, a glass pane, a chest with items, a sign, a blue bed and a smooth-stone floor.
- **First submit**: refused with 4 conflicts. Those positions held leftovers from earlier tests on the mirror's copy that the dev world does not have; the samples showed exactly which.
- **`submit force`**:
  - 299 blocks applied in 113 ms, 0 read-back mismatches;
  - zone unlocked, world saved;
  - the mirror pulled, refreshed and reported "all 299 blocks came back from the origin as sent".

  On the mirror afterwards the chest items, sign text, blue bed (its colour went through the 1.12 bed TileEntity) and fence connections were correct.
- **Locks and failures**:
  - `abandon` released the lock;
  - an overlapping checkout was refused;
  - with the bridge stopped, checkout failed with "the origin's bridge is not reachable" and left nothing behind.
- Vanilla 1.12 RCON closes the connection after every packet, so `pull.py` cannot send `save-off` to a plain Forge server. SpongeForge's RCON works; the real origin's pull already succeeded. Test setups can use `"rcon": null`.
- **Not tested here** (needs real players and FAWE): the in-game lock messages, and the FAWE mask. Test both on the origin before relying on them: try to break a block and to `//set` inside a checked-out zone.

## Verification: patches by hand (Forge 1.12.2 dev server, flat world, 2026-11-08)

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
