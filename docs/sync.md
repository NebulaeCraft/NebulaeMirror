# Incremental sync (origin 1.12 → mirror 1.21, P2)

Once the full import is done, the mirror keeps itself up to date with the origin. Each run:

1. **Pull** (`ops/sync/pull.py`): over the origin's RCON it sends `save-off` and `save-all flush`, copies `level.dat` and `region/r.*.mca` into a local copy, then sends `save-on`. Only changed region files are copied: with rsync on Linux/macOS, or on Windows by `pull.py` itself comparing region headers (`"copier": "header"`, see [Windows](#windows)).
2. **Find changes**: compare each chunk timestamp in the region headers with the previously recorded value. For chunks whose timestamp moved, compare a hash of their **content** (block IDs/metas, TileEntities, biomes; noise such as LastUpdate, light and entities is left out). Only chunks that really changed are converted, using the same `ChunkConverter` as the full import.
3. **Apply** (on the main thread, at most `tickBudgetMillis` per tick):
   - Chunks the server does not hold in memory: the whole chunk is replaced through the server's own chunk IO. When it is next loaded, the server recomputes lighting and connection shapes, exactly as after the full import.
   - Chunks the server holds (near players, force-loaded, in the loading halo or waiting to unload): diffed block by block against the live chunk, and only what differs is changed. Blocks are written straight into the chunk sections, **with no block callbacks and no neighbour updates**, so a sync never drops chest contents, makes sand fall or triggers redstone. Afterwards the connection shapes (fences, panes, walls, stairs and so on) of changed blocks and their neighbours are recomputed, and clients receive the block changes.
4. **Record**: save the world and flush chunk IO, then write each chunk's applied timestamp and hash to `world/nebulaemirror/sync/state/`. A crash at any point only means the next run redoes some chunks.

Outside checked-out zones the origin is authoritative:

- **Edits outside zones are re-applied.** Every chunk whose content changes in the mirror is recorded in `world/nebulaemirror/sync/mirror-edits.bin`, which survives restarts. That covers players, WorldEdit, Axiom, commands, water and fire. The next sync re-applies those chunks from the origin even if the origin did not change them. `status` shows how many are waiting; the run summary says how many were re-applied, and how many had no origin data (left as they are).
- **What is not recorded**: changes the mirror makes itself (the sync's patches, relighting, UpgradeData when a chunk loads).
- **Edits outside zones are also refused up front** (`"protectOutsideZones": true`, the default). Players cannot break, place, use items on blocks or use buckets outside zones they checked out. Empty-handed interaction such as opening doors still works and is reverted at the next sync. WorldEdit edits by players skip those blocks. The node `nebulaemirror.edit.anywhere` lifts this; nobody has it by default. See [writeback.md](writeback.md). Clicks with the WorldEdit wand, the navigation wand and items bound to WorldEdit tools are left to WorldEdit (selecting points works everywhere; the tools' edits are limited like any WorldEdit edit).

Use zones (or `exclude`) for areas that must not be overwritten.

## Deployment

1. **Origin** (1.12 server): in `server.properties`, set `enable-rcon=true`, `rcon.port` and `rcon.password`. Let the mirror machine reach the world directory over ssh (create a key pair and add the public key to `authorized_keys` on the origin).
2. **First local copy** on the mirror machine: on the mirror machine, create `pull.json` from [ops/sync/pull.example.json](../ops/sync/pull.example.json) and run one pull:

   ```bash
   python3 -I ops/sync/pull.py --config /srv/mirror/pull.json
   ```

   The RCON password goes in the file named by `rcon.passwordFile` (path relative to `pull.json`, `chmod 600`), or in the environment variable `NEBULAEMIRROR_RCON_PASSWORD`. It is never passed on the command line.
3. **Full import from that copy** (see [importer.md](importer.md)): `-Dnebulaemirror.import=/srv/mirror/world12`. Besides the world, the import writes the sync **baseline**: what each chunk looked like when it was imported.
4. **Configure the sync**: on first start the server creates `config/nebulaemirror-sync.json`, with sync off:

   ```json
   {
     "source": "/srv/mirror/world12",
     "pullCommand": ["python3", "-I", "/srv/mirror/NebulaeMirror/ops/sync/pull.py", "--config", "/srv/mirror/pull.json"],
     "pullTimeoutSeconds": 1800,
     "intervalMinutes": 15,
     "threads": 4,
     "tickBudgetMillis": 15,
     "maxQueuedChunks": 256,
     "checkpointChunks": 4000,
     "regions": "",
     "exclude": [ {"x1": 1600, "z1": -700, "x2": 1700, "z2": -600} ]
   }
   ```

   | Field | Meaning |
   |---|---|
   | `source` | The local 1.12 copy (contains `level.dat` and `region/`). Empty means sync is off |
   | `pullCommand` | Runs in the server directory before each automatic or `now` run; a non-zero exit cancels the run. Empty: apply `source` as it is |
   | `intervalMinutes` | Automatic run interval; 0 means command-triggered only. The first automatic run is 1 minute after start |
   | `threads` | Conversion threads (defaults to half the cores) |
   | `tickBudgetMillis` | Main-thread time per tick for applying chunks |
   | `maxQueuedChunks` | Maximum chunks converted but not yet applied (bounds memory) |
   | `checkpointChunks` | Save and record progress every this many chunks |
   | `regions` | `"x1,z1,x2,z2"`: sync only this region range (when only that range was imported; regions outside it are never synced) |
   | `exclude` | Block boxes never touched (checked-out zones, see [writeback.md](writeback.md), are excluded the same way, also from `refresh`) (`y1`/`y2` optional; whole height if omitted). Fully covered chunks are skipped; partly covered chunks are loaded and only the uncovered blocks are synced. These chunks are not recorded, so they sync once the exclusion is removed |
   | `protectOutsideZones` | `true`: players and WorldEdit may only change blocks inside zones they checked out |
   | `bridge` | `{"url", "token"}` of the origin's bridge mod for zone lock and submit (see [writeback.md](writeback.md)); empty url = no lock, no submit |
   | `chat` | Chat, join and leave sync with the origin through the bridge; off by default. See [chat.md](chat.md) |

   After editing, run `/nebulaemirror sync reload` (no restart needed).
5. **Keep the server running**: on macOS use [ops/launchd/net.kuina.nebulaemirror.server.plist](../ops/launchd/net.kuina.nebulaemirror.server.plist) (start it with [ops/server/run.sh](../ops/server/run.sh); it restarts after a crash but not after a normal `stop`). The sync timer is inside the mod, so launchd doesn't need a separate scheduled job.

**Migrating a world imported before P2** (no `world/nebulaemirror/sync/baseline.json`): sync refuses to run. If the mirror world was imported from **exactly** the current `source`, run `/nebulaemirror sync baseline` once. It reads every chunk and records it as applied, without changing the world; the full world takes a few minutes. Otherwise, re-import.

## Windows

Both the origin and the mirror can run on Windows Server. Differences from the steps above:

- **Python 3** (python.org installer) is needed on the mirror machine to run `pull.py`. In `pullCommand`, use the full path to `python.exe`, not the `py` launcher.
- **Copy with `"copier": "header"`** (done by `pull.py` itself, no extra tools). `remote` is the origin world folder:
  - same machine: a local path such as `D:\mc\world`;
  - another machine: a read-only SMB share of the origin's world folder such as `\\ORIGIN\mcworld`, readable by the Windows account that runs the mirror server.

  See [ops/sync/pull.windows.example.json](../ops/sync/pull.windows.example.json).

  How it decides:
  - A region file is copied when its first 8 KiB differ from the copy's. That part is the chunk location table and save-timestamp table, which 1.12 rewrites on every chunk save.
  - It is also copied when a chunk was saved in the same second as the previous copy (so the header could not tell). Do not change the clock of either machine in a way that makes them disagree.
  - `level.dat` is copied every time, and region files the origin no longer has are removed.

  **Do not use robocopy** (`"copier": "robocopy"` still works but warns). It decides by file size and modification time. Windows does not update those for files a program keeps open, and the 1.12 server keeps its region files open until it stops: `save-all flush` writes them but does not close them. So robocopy skips every changed region file while the origin runs. In the first deployment this showed up as "all 1553 region files skipped" in the pull log. It also made a submit's round-trip check refresh the zone from the stale copy (reverting it in the mirror) and `sync now` report 0 changed regions until the origin was restarted.
- Write paths in JSON with doubled backslashes (`"D:\\mirror\\world12"`).
- Keep the server running however you already do (a console window, Task Scheduler "at startup", or a service wrapper); the sync timer is inside the mod. launchd and `run.sh` are macOS only.

Example `config/nebulaemirror-sync.json` on Windows:

```json
{
  "source": "D:\\mirror\\world12",
  "pullCommand": ["C:\\Python313\\python.exe", "-I", "D:\\mirror\\sync\\pull.py", "--config", "D:\\mirror\\sync\\pull.json"],
  "pullTimeoutSeconds": 4200,
  "intervalMinutes": 15
}
```

## Commands (permission level 2; also usable over the mirror's RCON)

| Command | Action |
|---|---|
| `/nebulaemirror sync status` | Config summary, progress of the current run, result of the last run |
| `/nebulaemirror sync now` | pull + apply |
| `/nebulaemirror sync apply` | Apply what is in `source` without pulling |
| `/nebulaemirror sync refresh <cx1> <cz1> <cx2> <cz2>` | Force a re-sync of that chunk range by origin content (ignores the recorded state; undoes mirror-side edits); no pull |
| `/nebulaemirror sync baseline` | Record `source` as already applied (see migration above) |
| `/nebulaemirror sync cancel` | Cancel the current run. A running pull is never interrupted (on Windows a kill would skip `save-on`); the run stops as soon as the pull has finished |
| `/nebulaemirror sync reload` | Re-read the config and reset the timer |

At most one run at a time; a scheduled run that comes up while one is running is skipped. Each run writes `world/nebulaemirror/sync/last-run.json` and appends a line to `history.jsonl`. Fields include: chunks whose timestamp changed, chunks whose content changed, disk writes, live patches with changed blocks/BEs/shape counts, excluded chunks, unknown blocks and errors.

## Verification (9 regions around spawn, 2026-11-07)

The test edits were made with a test-only tool that edits the 1.12 copy and re-saves the whole region file, so the timestamp of every chunk in the region changes:

- A gold block, two fences, a NebulaeCraft asphalt block and a stone block (replacing a pressure plate) were placed near spawn, and one C&B block was removed. The same was done on a far chunk. After `apply`, 2048 chunks had a new timestamp but **only 4 had changed content**: the 2 near spawn were patched live, the 2 far ones were written to disk. Every check passed, including fence connections and the removal of the C&B block entity; neighbouring C&B blocks were untouched.
- Applying again after a restart changed nothing, so the recorded state persisted. `refresh` on 72 already-synced live chunks changed **0** blocks.
- Exclusions: in the same chunk, the block inside the exclusion was left alone and the one outside was synced. After the exclusion was removed, the next `apply` redid only that chunk.
- Pull: a failing pull command (non-zero exit) cancels the run and the failure shows in `status`. `cancel` during a pull waits for the pull to finish, then stops the run. Server `stop` during a pull shuts down within about 2 s, and the pull finishes on its own. The interval timer fires on schedule.
- Throughput: `refresh` over all 9,216 chunks took 33.5 s (7,354 written to disk, 1,862 patched live, 0 blocks changed in the live patches), with no "Can't keep up" warnings. Typical incremental changes are tens to hundreds of chunks, which take a few seconds.
- `ops/sync/tests`: RCON order (`save-off` → `save-all flush` → copy → `save-on`); `save-on` still sent when the copy fails or on SIGTERM; the header copier (first copy then nothing; a region whose content changed with unchanged size and time is copied; a same-second save is copied again; `level.dat` every time; removed regions; dry run; through a full pull with RCON); robocopy exit codes and dry run; wrong password; concurrent runs on the same copy. Robocopy itself was only run as a stand-in script, not on Windows.

## Development

```bash
cd mirror/mod && ./gradlew runImportServer -PimportRunDir=run/p2 -PsyncDebug
```

`-PsyncDebug` (`-Dnebulaemirror.sync.debug=true`) logs every block a live patch changes and every shape update, to find states that get rewritten on every run. Code: `mirror/mod/src/main/java/net/kuina/nebulaemirror/sync/`. Unit tests: `SyncUnitTest`.

## Notes and limitations

- **The first real-origin test is still to do**: the RCON/copy flow was only tested against a fake RCON server and local directories. Before going live, run `pull.py --dry-run` once against the origin to confirm SpongeForge's `save-all flush` and the copy both work.
- `pullTimeoutSeconds` is only a last resort for a hung pull, and keep it above `pull.py`'s own `rconTimeoutSeconds + copyTimeoutSeconds`. If it fires, the pull is killed, and the origin may need a manual `save-on`; the error message says so.
- Floating blocks that 1.21 does not allow (e.g. lanterns with no support) are removed by vanilla UpgradeData during the full import. A sync puts them back from the origin; it never deletes a block just because of a connection-shape update.
- Live patches keep the leaf `distance` the game computed instead of DFU's guess, so leaves are not rewritten every run.
- Chunks deleted on the origin (never deleted in normal play; only external editors do this) are only counted (`chunksMissingInSource`); the mirror keeps them.
- Entities are not synced (the full import doesn't bring them in either).
- After a mod update, chunks whose content didn't change are not re-converted. Old unknown placeholders are resolved in P4 (`resolve-unknown`). To re-convert an area now, use `refresh`.
- A held chunk whose neighbouring chunks are not loaded skips the connection-shape update on that side and gets it later when it changes.
