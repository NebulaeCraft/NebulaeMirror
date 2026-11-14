# 增量同步（原服 1.12 → 镜像 1.21，P2）

全量导入完成之后，镜像会自己跟上原服。每次运行：

1. **拉取**（`ops/sync/pull.py`）：通过原服的 RCON 发送 `save-off` 和 `save-all flush`，把 `level.dat` 和 `region/r.*.mca` 复制到本地副本，然后发送 `save-on`。只复制有变化的 region 文件：在 Linux/macOS 上用 rsync，在 Windows 上由 `pull.py` 自己比较 region 文件头（`"copier": "header"`，见 [Windows](#windows)）。
2. **查找变化**：把 region 文件头里每个区块的时间戳与之前记录的值比较。对时间戳有变动的区块，再比较其**内容**的哈希（方块 ID/meta、TileEntity、生物群系；LastUpdate、光照、实体这类噪声不计入）。只有真正变化的区块才会被转换，使用与全量导入相同的 `ChunkConverter`。
3. **应用**（在主线程上，每 tick 最多 `tickBudgetMillis`）：
   - 服务器内存里没有的区块：通过服务器自己的区块 IO 整块替换。下次加载时，服务器会重新计算光照和连接形状，和全量导入之后完全一样。
   - 服务器持有的区块（玩家附近、强制加载、处于加载外圈或等待卸载）：与在线区块逐方块比较，只修改不同的地方。方块直接写入区块的 section，**没有方块回调，也没有邻居更新**，所以同步永远不会让箱子掉出物品、让沙子下落或触发红石。之后会重新计算被修改的方块及其邻居的连接形状（栅栏、玻璃板、墙、楼梯等），并把方块变化发送给客户端。
4. **记录**：保存世界并刷新区块 IO，然后把每个区块已应用的时间戳和哈希写到 `world/nebulaemirror/sync/state/`。任何时刻崩溃，都只意味着下次运行要重做一部分区块。

在签出的区域之外，以原服为准：

- **区域之外的编辑会被重新覆盖。** 镜像里内容发生变化的每个区块都记录在 `world/nebulaemirror/sync/mirror-edits.bin` 里，重启后仍然保留。这涵盖了玩家、WorldEdit、Axiom、命令、水和火。下一次同步会用原服的内容重新覆盖这些区块，即使原服没有改动它们。`status` 显示有多少区块在等待；运行摘要会说明重新覆盖了多少，以及有多少没有原服数据（保持原样）。
- **不会被记录的**：镜像自己做的修改（同步打的补丁、重新计算光照、区块加载时的 UpgradeData）。
- **区域之外的编辑也会被事先拒绝**（`"protectOutsideZones": true`，默认值）。玩家在自己签出的区域之外不能破坏、放置方块，不能对方块使用物品，也不能使用桶。开门这类空手交互仍然可以，并会在下一次同步时还原。玩家的 WorldEdit 编辑会跳过这些方块。权限节点 `nebulaemirror.edit.anywhere` 可以解除这个限制；默认没有人拥有它。见 [writeback.md](writeback.md)。用 WorldEdit 魔杖、导航魔杖和绑定了 WorldEdit 工具的物品点击，会交给 WorldEdit 处理（选点在任何地方都可以；工具产生的编辑和其他 WorldEdit 编辑一样受限）。

对不能被覆盖的区域，请使用区域（或 `exclude`）。

## 部署

1. **原服**（1.12 服务器）：在 `server.properties` 里设置 `enable-rcon=true`、`rcon.port` 和 `rcon.password`。让镜像机器能通过 ssh 访问世界目录（创建一对密钥，把公钥加到原服的 `authorized_keys` 里）。
2. 镜像机器上的**第一份本地副本**：在镜像机器上，参照 [ops/sync/pull.example.json](../ops/sync/pull.example.json) 创建 `pull.json`，并执行一次拉取：

   ```bash
   python3 -I ops/sync/pull.py --config /srv/mirror/pull.json
   ```

   RCON 密码放在 `rcon.passwordFile` 指定的文件里（路径相对于 `pull.json`，`chmod 600`），或者放在环境变量 `NEBULAEMIRROR_RCON_PASSWORD` 里。它永远不会通过命令行传递。
3. **从这份副本做全量导入**（见 [importer.md](importer.md)）：`-Dnebulaemirror.import=/srv/mirror/world12`。除了世界本身，导入还会写入同步的**基线**：每个区块被导入时的样子。
4. **配置同步**：服务器首次启动时会创建 `config/nebulaemirror-sync.json`，同步处于关闭状态：

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

   | 字段 | 含义 |
   |---|---|
   | `source` | 本地的 1.12 副本（包含 `level.dat` 和 `region/`）。留空表示关闭同步 |
   | `pullCommand` | 每次自动运行或 `now` 运行之前，在服务器目录里执行；非零退出码会取消这次运行。留空：直接应用 `source` 里现有的内容 |
   | `intervalMinutes` | 自动运行的间隔；0 表示只由命令触发。第一次自动运行在启动后 1 分钟 |
   | `threads` | 转换线程数（默认是核心数的一半） |
   | `tickBudgetMillis` | 每 tick 用于应用区块的主线程时间 |
   | `maxQueuedChunks` | 已转换但尚未应用的区块上限（限制内存占用） |
   | `checkpointChunks` | 每处理这么多区块就保存并记录一次进度 |
   | `regions` | `"x1,z1,x2,z2"`：只同步这个 region 范围（当只导入了这个范围时使用；范围之外的 region 永远不会同步） |
   | `exclude` | 永远不碰的方块范围（签出的区域也以同样的方式被排除，包括对 `refresh`，见 [writeback.md](writeback.md)）（`y1`/`y2` 可选；省略则为整个高度）。被完全覆盖的区块直接跳过；被部分覆盖的区块会被加载，只同步未覆盖的方块。这些区块不会被记录，所以去掉排除之后它们会同步一次 |
   | `protectOutsideZones` | `true`：玩家和 WorldEdit 只能修改自己签出的区域内的方块 |
   | `bridge` | 原服 bridge mod 的 `{"url", "token"}`，用于区域锁和提交（见 [writeback.md](writeback.md)）；url 为空 = 不加锁、不能提交 |
   | `chat` | 经 bridge 与原服同步聊天、加入和离开；默认关闭。见 [chat.md](chat.md) |

   修改之后执行 `/nebulaemirror sync reload`（不需要重启）。
5. **保持服务器运行**：在 macOS 上使用 [ops/launchd/net.kuina.nebulaemirror.server.plist](../ops/launchd/net.kuina.nebulaemirror.server.plist)（它用 [ops/server/run.sh](../ops/server/run.sh) 启动服务器；崩溃后会重启，正常 `stop` 之后不会）。同步定时器在 mod 内部，所以 launchd 不需要另外的定时任务。

**迁移 P2 之前导入的世界**（没有 `world/nebulaemirror/sync/baseline.json`）：同步会拒绝运行。如果镜像世界**正是**从当前的 `source` 导入的，执行一次 `/nebulaemirror sync baseline`。它会读取每个区块并把它记录为已应用，不改动世界；整个世界需要几分钟。否则请重新导入。

## Windows

原服和镜像都可以运行在 Windows Server 上。与上述步骤的不同之处：

- 镜像机器上需要 **Python 3**（python.org 的安装包）来运行 `pull.py`。在 `pullCommand` 里使用 `python.exe` 的完整路径，不要用 `py` 启动器。
- **用 `"copier": "header"` 复制**（由 `pull.py` 自己完成，不需要额外的工具）。`remote` 是原服的世界目录：
  - 同一台机器：本地路径，例如 `D:\mc\world`；
  - 另一台机器：原服世界目录的只读 SMB 共享，例如 `\\ORIGIN\mcworld`，运行镜像服务器的 Windows 账户要能读取它。

  见 [ops/sync/pull.windows.example.json](../ops/sync/pull.windows.example.json)。

  它的判断方式：
  - region 文件的前 8 KiB 与副本不同时就复制。这部分是区块位置表和保存时间戳表，1.12 每次保存区块都会重写它。
  - 如果某个区块与上一次复制在同一秒内保存（文件头因此无法区分），也会复制。不要把任何一台机器的时钟改得让两者不一致。
  - `level.dat` 每次都复制，原服已经没有的 region 文件会被删除。

  **不要使用 robocopy**（`"copier": "robocopy"` 仍然可用，但会给出警告）。它根据文件大小和修改时间来判断。对于被程序保持打开的文件，Windows 不会更新这两项，而 1.12 服务器在停止之前一直保持 region 文件打开：`save-all flush` 会写入它们，但不会关闭。所以原服运行期间，robocopy 会跳过所有有变化的 region 文件。在第一次部署中，这表现为拉取日志里的「all 1553 region files skipped」。它还导致提交的往返校验用过时的副本刷新了区域（把镜像里的区域还原了），以及 `sync now` 报告 0 个变化的 region，直到原服重启为止。
- JSON 里的路径要写成双反斜杠（`"D:\\mirror\\world12"`）。
- 用你平常的方式保持服务器运行即可（控制台窗口、任务计划程序的「启动时」、或服务包装器）；同步定时器在 mod 内部。launchd 和 `run.sh` 只适用于 macOS。

Windows 上的 `config/nebulaemirror-sync.json` 示例：

```json
{
  "source": "D:\\mirror\\world12",
  "pullCommand": ["C:\\Python313\\python.exe", "-I", "D:\\mirror\\sync\\pull.py", "--config", "D:\\mirror\\sync\\pull.json"],
  "pullTimeoutSeconds": 4200,
  "intervalMinutes": 15
}
```

## 命令（权限等级 2；也可以通过镜像的 RCON 使用）

`/nm` 是 `/nebulaemirror` 的短写（mod 1.6+）：`/nm sync status`、`/nm sync now` 等。

| 命令 | 作用 |
|---|---|
| `/nebulaemirror sync status` | 配置摘要、当前运行的进度、上一次运行的结果 |
| `/nebulaemirror sync now` | 拉取 + 应用 |
| `/nebulaemirror sync apply` | 不拉取，直接应用 `source` 里的内容 |
| `/nebulaemirror sync refresh <cx1> <cz1> <cx2> <cz2>` | 按原服内容强制重新同步这个区块范围（忽略已记录的状态；撤销镜像侧的编辑）；不拉取 |
| `/nebulaemirror sync baseline` | 把 `source` 记录为已应用（见上文的迁移说明） |
| `/nebulaemirror sync cancel` | 取消当前运行。正在进行的拉取永远不会被打断（在 Windows 上杀掉它会跳过 `save-on`）；拉取一结束，运行就会停止 |
| `/nebulaemirror sync reload` | 重新读取配置并重置定时器 |

同一时间最多只有一次运行；有运行在进行时到点的定时运行会被跳过。每次运行都会写入 `world/nebulaemirror/sync/last-run.json`，并在 `history.jsonl` 里追加一行。字段包括：时间戳有变化的区块、内容有变化的区块、磁盘写入次数、在线补丁数及其修改的方块/方块实体/形状计数、被排除的区块、unknown 方块和错误。

## 验证（出生点周围 9 个 region，2026-11-07）

测试用的编辑是用一个仅供测试的工具做的，它编辑 1.12 副本并重新保存整个 region 文件，所以该 region 里每个区块的时间戳都会变化：

- 在出生点附近放置了一个金块、两个栅栏、一个 NebulaeCraft 沥青方块和一个石头（替换掉一个压力板），并移除了一个 C&B 方块。在一个远处的区块上做了同样的操作。`apply` 之后，2048 个区块有了新的时间戳，但**只有 4 个的内容发生了变化**：出生点附近的 2 个以在线补丁方式应用，远处的 2 个写入磁盘。所有检查都通过，包括栅栏的连接和 C&B 方块实体的移除；相邻的 C&B 方块没有受影响。
- 重启后再次应用，没有任何变化，说明记录的状态持久化了。对 72 个已同步的在线区块执行 `refresh`，修改了 **0** 个方块。
- 排除：在同一个区块里，排除范围内的方块没有被动，范围外的方块被同步了。去掉排除之后，下一次 `apply` 只重做了那一个区块。
- 拉取：失败的拉取命令（非零退出码）会取消这次运行，失败原因显示在 `status` 里。拉取期间执行 `cancel` 会等拉取结束，然后停止运行。拉取期间服务器 `stop` 会在大约 2 秒内关闭，拉取自己继续完成。间隔定时器按时触发。
- 吞吐量：对全部 9,216 个区块执行 `refresh` 用了 33.5 秒（7,354 个写入磁盘，1,862 个以在线补丁方式应用，在线补丁里修改了 0 个方块），没有出现「Can't keep up」警告。典型的增量变化是几十到几百个区块，需要几秒钟。
- `ops/sync/tests`：RCON 顺序（`save-off` → `save-all flush` → 复制 → `save-on`）；复制失败或收到 SIGTERM 时仍然发送 `save-on`；header 复制器（第一次复制，之后什么都不复制；内容变了但大小和时间没变的 region 会被复制；同一秒内的保存会再次复制；`level.dat` 每次都复制；被移除的 region；空跑；以及带 RCON 的完整拉取）；robocopy 的退出码和空跑；错误的密码；对同一份副本的并发运行。robocopy 本身只用替身脚本跑过，没有在 Windows 上运行。

## 开发

```bash
cd mirror/mod && ./gradlew runImportServer -PimportRunDir=run/p2 -PsyncDebug
```

`-PsyncDebug`（`-Dnebulaemirror.sync.debug=true`）会记录在线补丁修改的每个方块和每次形状更新，用来找出每次运行都被重写的状态。代码：`mirror/mod/src/main/java/net/kuina/nebulaemirror/sync/`。单元测试：`SyncUnitTest`。

## 注意事项和限制

- **对真实原服的首次测试还没有做**：RCON/复制流程只在假的 RCON 服务器和本地目录上测试过。上线之前，先对原服执行一次 `pull.py --dry-run`，确认 SpongeForge 的 `save-all flush` 和复制都能工作。
- `pullTimeoutSeconds` 只是拉取卡死时的最后手段，要让它大于 `pull.py` 自己的 `rconTimeoutSeconds + copyTimeoutSeconds`。如果它触发了，拉取会被杀掉，原服可能需要手动执行 `save-on`；错误信息里会说明这一点。
- 1.21 不允许的悬空方块（例如没有支撑的灯笼）在全量导入时会被原版的 UpgradeData 移除。同步会从原服把它们放回来；它从不因为连接形状更新就删除方块。
- 在线补丁保留游戏计算出的树叶 `distance`，而不是 DFU 猜的值，所以树叶不会每次运行都被重写。
- 在原服被删除的区块（正常游戏中不会删除；只有外部编辑器会这样做）只会被计数（`chunksMissingInSource`）；镜像保留它们。
- 实体不同步（全量导入也不会带入实体）。
- mod 更新之后，内容没有变化的区块不会被重新转换。旧的 unknown 占位方块将在 P4（`resolve-unknown`）里处理。现在要重新转换某个区域，请使用 `refresh`。
- 服务器持有的区块如果相邻区块没有加载，会跳过那一侧的连接形状更新，等它以后发生变化时再补上。
