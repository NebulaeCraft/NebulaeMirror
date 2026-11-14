# Bridge mod（原服 1.12.2，P3 第 2、3 步）

`legacy/bridge/` 是一个 Forge 1.12.2 服务端 mod（`mirrorbridge`，可与 SpongeForge 一起使用；客户端不需要它）。它做五件事：

- 锁住镜像签出的区域，让原服这边在此期间无法改动它们；
- 提供一个本地 HTTP API，镜像用它来加锁、解锁和提交；
- 把写回 patch（见 [writeback.md](writeback.md)）应用到主世界；
- 为每个 job 保留一个 undo 文件，并且可以回滚 job；
- 在两台服务器之间转发聊天、加入和离开（1.3，原 0.3.0；见 [chat.md](chat.md)）。

## 构建和安装

```bash
cd legacy/bridge && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./gradlew build
```

把 `legacy/bridge/build/libs/MirrorBridge-1.12.2-1.4.jar` 放进原服的 `mods/` 并重启。文件位于 `world/nebulaemirror-bridge/`：

| 路径 | 内容 |
|---|---|
| `inbox/` | 待应用的 patch（把镜像的 `world/nebulaemirror/zones/<zone>/patch.nbt` 拷到这里，文件名随意） |
| `jobs/<id>/patch.nbt` | 某个 job 应用的 patch |
| `jobs/<id>/undo.nbt` | 该 job 替换掉的内容，在修改第一个方块**之前**写入 |
| `jobs/<id>/job.json` | 阶段、计数、冲突、未知名称、回读不一致、错误 |
| `locks.json` | 被锁定的区域 |

job id 的格式是 `<日期>-<时间>-<区域>`，例如 `20261108-102752-plaza`。

`config/mirrorbridge.cfg`：

| 设置 | 默认值 | 含义 |
|---|---|---|
| `tickBudgetMillis` | 10 | 每 tick 用于 job 的主线程时间 |
| `httpEnabled` | true | 是否运行 HTTP API |
| `httpHost` | 127.0.0.1 | 监听地址；镜像在同一台机器上时保持不变 |
| `httpPort` | 21201 | 端口 |
| `token` | 首次启动时生成 | 共享密钥；把它复制到镜像的 `config/nebulaemirror-sync.json`（`bridge.token`） |

**FAWE**：在 `config/FastAsyncWorldEdit/config.yml` 的 `extent.allowed-plugins` 里加上 `net.kuina.nebulaemirror.bridge.WorldEditLock$LockedExtent`（确切路径取决于 FAWE 版本）。否则 FAWE 会忽略锁的 mask，并记录 `Potentially unsafe extent blocked`。

## 命令

| 命令 | 权限节点 | 作用 |
|---|---|---|
| `/mirrorbridge inbox` | `nebulaemirror.bridge.status` | 列出 inbox |
| `/mirrorbridge apply <file> [force]` | `nebulaemirror.bridge.apply` | 应用 inbox 里的一个 patch；job 完成后文件从 inbox 移除（被拒绝的会留下以便重试） |
| `/mirrorbridge rollback <job> [force]` | `nebulaemirror.bridge.rollback` | 恢复某个 job 替换掉的内容（把它的 undo 文件作为一个新 job 应用，这个新 job 自己也可以回滚） |
| `/mirrorbridge status` / `jobs` | `nebulaemirror.bridge.status` | 当前 job / 最近 10 个 job |
| `/mirrorbridge cancel` | `nebulaemirror.bridge.apply` | 停止正在运行的 job；已经应用的部分可以回滚 |
| `/mirrorbridge locks` | `nebulaemirror.bridge.status` | 被锁定的区域 |
| `/mirrorbridge unlock <zone>` | `nebulaemirror.bridge.lock` | 手动解除一个锁（例如镜像丢失了这个区域时）；通常由镜像来做 |
| — | `nebulaemirror.bridge.bypasslock` | 无视锁，在被锁定的区域内编辑。**默认没有人拥有它，OP 也不例外** |

这些节点走 Forge 的 PermissionAPI，SpongeForge 会把它交给 LuckPerms。默认只有 OP 拥有它们（`bypasslock` 除外）。控制台和 RCON 可以使用所有命令。

## 区域锁

镜像签出某个区域期间，bridge 会阻止：
- 玩家在那里破坏、放置方块，对方块使用桶，或右键点击方块（门、拉杆、箱子等），并给出一条带有区域名的消息；
- 实体放置方块、爆炸破坏方块，以及流体流入；
- WorldEdit/FAWE 的编辑（用 extent mask 实现，和 WorldGuard 的做法一样；见上面关于 FAWE 的说明）。

不在覆盖范围内的：OP 或控制台执行的 `/setblock`、`/fill` 和 `/clone`，活塞，以及植物生长。这些造成的任何变化都会让区域的 `Expect` 对不上，于是提交被拒绝，并列出坐标。`submit force` 会覆盖它们。

提交的 job 干净地完成时会解锁它的区域；被拒绝或失败的 job 保留锁。锁在重启后仍然保留。

## HTTP API

由镜像使用；其他任何东西都不需要用它。每个请求都带有 `X-Mirror-Token: <token>`，所有工作都在服务器线程上执行。

| 请求 | 响应 |
|---|---|
| `GET /v1/status` | 版本、锁、当前 job |
| `POST /v1/lock` `{zone, min, max, owner}` | 已锁定；与另一个锁重叠时返回 409 |
| `POST /v1/unlock` `{zone}` | `{unlocked: true/false}` |
| `POST /v1/submit?zone=Z[&force=true]`，请求体：patch | `{job}`。该区域必须已在这里锁定（除非带 `force`），并且每个条目都必须位于锁的范围内 |
| `GET /v1/job?id=J` | job 报告（job.json） |
| `GET /v1/chat?boot=B&since=S&wait=W` | `S` 之后的原服聊天、加入和离开（长轮询，最长 `W` ≤ 30 秒）：`{boot, seq, events}`。`since=-1`：只返回当前的 `seq`。`boot` 不同（bridge 重启过）：返回本次启动以来缓冲的所有内容 |
| `POST /v1/chat/say` `{text}` | 把镜像的文字（已格式化，使用 `§` 代码，最多 1024 个字符）显示给所有玩家 |

## job 的执行过程

1. **检查**（分摊到多个 tick）：
   - 每个方块名称都必须在这台服务器上注册过；
   - 每个条目的 `Expect`（签出时原服上的内容）必须与原服现在的内容一致。如果某个条目的目标方块已经在那里了，不算冲突，这样部分完成的 job 才能回滚。

   每个条目当前的方块和 TileEntity 都会被记录下来。
   - 未知名称或 y 0..255 之外的位置**一定**会让 job 被拒绝。
   - 冲突会让 job 被拒绝（除非带 `force`），并连同坐标一起列出。
2. 写入 **undo 文件**。
3. **应用**（分摊到多个 tick）。
4. **回读**（分摊到多个 tick）：把每个条目的方块、meta 和 TileEntity id 与 patch 比较；差异会被计数，并在报告里抽样列出。
5. 对于提交：解锁区域并保存世界，这样镜像的往返拉取就能看到结果。保存被关闭期间（即拉取期间）保存不会做任何事。

   方块直接写入区块的 section，没有 `breakBlock`、`onBlockAdded`、邻居更新，也没有 Sponge/FAWE 事件。所以：
   - 被替换的箱子不会掉落任何东西；
   - 沙子不会下落，水不会开始流动，红石旁边的 TNT 不会被点燃；
   - 移除原木不会让树叶腐烂。

   其余的事情由 bridge 自己处理：
   - 移除旧的 TileEntity，并根据 patch 创建新的（C&B、ArchitectureCraft 和其他所有 mod 的 TileEntity 都按它的 id 创建）；
   - 为被修改的区块重建高度图和光照；
   - 把区块标记为待保存，并把变化发送给玩家。

job 运行期间服务器停止的话，下次启动时它会被标记为 `interrupted`。如果它已经开始应用，`rollback` 可以恢复之前的内容。

## 验证：镜像 → bridge 端到端（2026-11-08）

环境：镜像开发服务器和一台装了 bridge 的 Forge 1.12.2 开发服务器，在同一台机器上。镜像的同步源由 `ops/sync/pull.py` 从开发世界拉取。

- **签出** `z1`：区域在原服被锁定（`/v1/status` 里能看到），拉取并刷新，然后做了快照。
- **编辑**：羊毛、火把、楼梯、栅栏、玻璃板、装有物品的箱子、告示牌、蓝色的床和平滑石地板。
- **第一次提交**：因 4 处冲突被拒绝。这些位置上是镜像副本里早先测试留下的残留，而开发世界里没有；抽样结果准确地指出了是哪些。
- **`submit force`**：
  - 113 毫秒内应用了 299 个方块，回读不一致为 0；
  - 区域解锁，世界保存；
  - 镜像拉取、刷新，并报告全部 299 个方块从原服回来时与发送的一致。

  之后在镜像上，箱子里的物品、告示牌文字、蓝色的床（它的颜色经过了 1.12 的床 TileEntity）和栅栏的连接都是正确的。
- **锁和故障**：
  - `abandon` 释放了锁；
  - 重叠的签出被拒绝；
  - 停掉 bridge 时，签出失败并提示无法连接原服的 bridge，没有留下任何东西。
- 原版 1.12 的 RCON 每个数据包之后都会关闭连接，所以 `pull.py` 无法向普通的 Forge 服务器发送 `save-off`。SpongeForge 的 RCON 可以；对真实原服的拉取已经成功过。测试环境可以用 `"rcon": null`。
- **此处未测试**（需要真实玩家和 FAWE）：游戏内的锁定提示，以及 FAWE mask。在依赖它们之前，先在原服上测试这两项：试着在已签出的区域内破坏一个方块，以及执行 `//set`。

## 验证：手动应用 patch（Forge 1.12.2 开发服务器，超平坦世界，2026-11-08）

测试用的 patch 是用 1.21 的 NBT 代码写出的，和镜像写出的一样。结果：

- **A**（10 个方块）：
  - 羊毛、装有羊毛和一把带命名与附魔的剑的箱子、带文字的告示牌、带旋转的玩家头颅和蕨类花盆，结果都完全正确；
  - 还放置了一个沙子、一个流动的水、荧石，以及红石块旁边的 TNT；5 秒后沙子没有下落，水没有扩散，也没有 TNT 或下落方块实体。
- **B** 把 A 的箱子替换成石头：没有掉落物品。
- **C**：它的 `Expect` 对不上 → 被拒绝，并给出位置和原服上的内容。文件留在 inbox 里；之后用 `force` 应用了它。
- **D** 使用了 `quark:stone_andesite_stairs`（开发服务器上没有安装）→ 即使带 `force` 也被拒绝，没有任何改动。
- **E**：20,000 个方块在 177 毫秒内完成检查和应用。
- **回滚**：
  - 在 B 和 C 已经改动了 A 的两个方块的情况下回滚 A → 被拒绝，并列出这两个位置；
  - 依次回滚 C、B、A，再回滚 E，恢复了一切：回滚 B 时箱子连同物品一起回来了，任何地方都没有掉落物品；
  - 每次回滚自身也可以撤销。
- 开发：`./gradlew runServer`（世界在 `legacy/bridge/run/world`）从标准输入读取控制台命令。服务器停止时 Gradle 会报告失败，因为 1.12 服务器的 `System.exit` 会结束 gradle 进程；在此之前世界已经保存。

尚未在真实原服（SpongeForge + FAWE + 全部 mod）上试过。在那里使用之前，先在一个不重要的地方应用一个小 patch 并回滚。
