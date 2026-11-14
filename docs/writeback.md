# 写回（镜像 1.21 → 原服 1.12，P3）

在镜像里做的编辑以**区域**为单位写回原服。区域是一个被签出的长方体范围：从签出到提交或放弃为止，这个范围内以镜像为准，增量同步不会动它。在原服，bridge mod（[bridge.md](bridge.md)）会锁住同一个范围。

## 工作流程

1. 在镜像上用 WorldEdit 选好范围（`//wand`、`//pos1` / `//pos2` 等），然后执行 `/nebulaemirror zone checkout <name>`。也可以直接给出两个角：`/nebulaemirror zone checkout <name> <from> <to>`。bridge 在原服锁住这个范围；镜像执行拉取，用原服的内容刷新这个范围并做快照。
2. 在镜像里建造（WorldEdit、Axiom、手工）。
3. 执行 `/nebulaemirror zone lint <name>`，直到没有错误。
4. 执行 `/nebulaemirror zone submit <name>`：
   1. 再做一次 lint；
   2. 把有变化的方块发送给 bridge；
   3. bridge 做检查、写入 undo 文件、应用、回读、解锁并保存；
   4. 镜像执行拉取，用原服的内容刷新区域，并把发送的每个方块与回来的内容比较。它会显示「全部 N 个方块从原服回来时与发送的一致」，或者列出差异。

   之后区域关闭（文件保留在 `zones/_closed/<name>-<time>/`）。

   如果签出之后原服上有方块被改动过，提交会被拒绝，并给出这些方块的坐标。区域保持签出和锁定状态；`submit <name> force` 会覆盖它们。
5. 或者执行 `/nebulaemirror zone abandon <name>`：在原服解锁，丢弃镜像上的编辑（用原服的内容刷新）。

在原服撤销一次提交：`/mirrorbridge rollback <job>`（job id 由 `submit` 打印）。

配置（镜像，`config/nebulaemirror-sync.json`）：

```json
"bridge": {"url": "http://127.0.0.1:21201", "token": "<原服 config/mirrorbridge.cfg 里的 token>"}
```

没有配置 `bridge` 时，签出不会锁定原服（会有提示），`submit` 也不可用。仍然可以用 `zone diff` 写出 patch，再手动用 `/mirrorbridge apply` 应用。

## 命令和权限节点

权限节点通过 fabric-permissions-api 检查，所以由 LuckPerms-Fabric 管理。没有权限 mod 时，每个节点回退到表中所示的 OP 等级。下面的每个命令都在 `/nebulaemirror` 之下，也可以用短写 `/nm`（mod 1.6+）：`/nm zone checkout <name>`、`/nm zone submit <name>` 等，权限节点相同。

| 命令 | 节点（回退等级） | 作用 |
|---|---|---|
| `zone checkout <name>` | `nebulaemirror.zone.checkout` (2) | 使用你当前在主世界的 WorldEdit 选区。非长方体选区（球体、多边形等）使用它的包围盒，并会有提示。镜像上需要 WorldEdit for Fabric；只使用 WorldEdit 的核心 API（用 7.3.8 测试过）。从控制台执行时使用控制台的 WorldEdit 会话（`//world world_minecraft:overworld`、`//pos1 x,y,z`、`//pos2 x,y,z`） |
| `zone checkout <name> <from> <to>` | `nebulaemirror.zone.checkout` (2) | 同上，但明确给出两个角（玩家可以用 `~`）。名称为 1–32 个字符，只能用 `a-z 0-9 _ -`；最多 256³ 个方块；区域之间不能重叠 |
| `zone list` / `info <name>` | `nebulaemirror.zone.view` (2) | 已签出的区域、锁定状态、上一次提交 |
| `zone lint <name>` / `diff <name>` | `nebulaemirror.zone.view` (2) | 与快照比较并检查；`diff` 还会写出 `patch.nbt`。完整报告在 `lint.json` |
| `zone submit <name> [force]` | `nebulaemirror.zone.submit` (2) | 见工作流程 |
| `zone abandon <name>` | `nebulaemirror.zone.abandon` (2) | 见工作流程 |
| — | `nebulaemirror.zone.others` (3) | 对别人签出的区域执行 lint / diff / submit / abandon，并在其中建造（控制台始终可以） |
| — | `nebulaemirror.edit.anywhere`（无人拥有） | 修改自己签出区域之外的方块（反正下次同步时会被原服的内容重新覆盖） |
| `sync status` | `nebulaemirror.sync.status` (2) | |
| `sync now` / `apply` / `refresh` / `cancel` | `nebulaemirror.sync.run` (2) | |
| `sync baseline` / `reload` | `nebulaemirror.sync.admin` (3) | |
| `stats` | `nebulaemirror.stats` (2) | |

文件：`world/nebulaemirror/zones/<name>/` 里有 `zone.json`、`baseline.nbt`（快照）、`lint.json`、`patch.nbt`，提交之后还有 `verify.json`。区域在重启后仍然保留。同步关闭时（开发环境），签出会把镜像当前的内容当作原服的内容，并会有提示。

## 游戏内的区域边界

从签出到区域被提交或放弃为止，每位玩家都能看到它的边界（mod 1.7+，服务端和客户端都需要），样式类似 WorldEditCUI 的选区框：

- 长方体的 12 条棱，面上不画任何东西；
- 玩家自己签出的区域为**青色**，其他人的为**黄色**（包括从控制台签出的区域）；
- 被方块挡住的部分画得比较淡，所以在建筑内部也能找到边界。

这个框就是区域的方块范围，即从最小角到最大方块的远侧。颜色取决于是谁签出的，与权限无关：即使有 `nebulaemirror.zone.others`，别的玩家的区域仍然是黄色。

工作原理：

- 服务端把活动区域的列表（`nebulaemirror:zones`；每个区域 6 个 int，以及它是否属于接收的玩家）发送给每位加入的玩家；每当有区域变为活动或不再活动时，发送给所有人。区域在签出完成（已锁定、已刷新、已做快照）后变为活动。原服应用了提交之后，或执行 `abandon` 时，它不再活动。被拒绝或失败的提交会让区域保持签出状态，所以边界也保留。
- 客户端（`client/ZoneOutlineRenderer`）只负责绘制；它保留这份列表，直到下一份到来或断开连接。区域都在主世界，所以其他维度里什么都不画。
- 低于 1.7 的客户端没有这个通道，不会收到任何东西。

2026-11-14 用开发客户端巡览做了检查（新增巡览指令 `cmd <name> <command...>`，以玩家身份执行服务器命令），一个区域由玩家签出，另一个区域的 `zone.json` 写的是别的所有者：前者是青色，后者是黄色，从外面和从里面看都是如此（隔着墙时较淡）；放弃之后各自消失。同一个渲染器的早期版本（带面上的网格、只有一种颜色）也在「Fabulous!」画质下、以及区域位于摄像机背后时检查过（屏幕上没有多余的东西）。`ZoneOutlineTest` 覆盖了数据包和线条几何。

## 写回的内容

只写回 **1.12 结果**与快照不同的方块。如果一个方块只是以 1.12 不存储的方式发生了变化（栅栏/玻璃板/墙的连接、楼梯形状、树叶 distance、积雪的草方块），它不会被写回：原服会自己重新计算这些。没有变化的 C&B 方块、箱子和其他方块在原服保持原有的 1.12 数据，因为它们根本不会被发送。

每个方块按以下顺序经过 `ReverseTable`：

1. 空气 → 空气。
2. `nebulaemirror:unknown` → 保留下来的名称、meta 和 TileEntity，原样不变。
3. 带有 `nebulaemirror:legacy` 方块实体的方块（C&B、ArchitectureCraft、NebulaeCraft 的 TE、MoarSigns、Railcraft 储罐等）：名称和 meta 来自它的**当前状态**，所以在镜像里旋转它，原服那边也会旋转；再加上保留下来的 1.12 TileEntity，并换上新坐标。用 WorldEdit、Axiom 或 `/clone` 移动或复制这些方块时，它们的内容会保留。
4. 1.12 原版也有的原版方块 → 原版（见下文），TileEntity 做降级。
5. 镜像方块和 FutureMC/Quark 的 override 目标 → 生成器的 `reverse.json`（例如 `minecraft:andesite_stairs` → `quark:stone_andesite_stairs`）。
6. 其他任何东西都是错误（例如樱花木板、云杉告示牌：1.12 没有可以对应的东西）。

### 原版方块

原版方块的反向表不是手写的。首次使用时，它对每个规范的 1.12 原版 meta 正向运行 Minecraft 自己的 DataFixer，再把结果反转：

- 规范 meta 来自 dump 的 meta 表，经由生成器的 `vanilla_metas.json`；
- 数字 id 来自原服的 `level.dat`。

1,650 个规范 meta 全部能映射回自身。有 67 个 1.21 状态对应不止一个 1.12 原像，每个都选定了一个 meta。例如：树叶的 `check_decay` 会丢失，1.12 头颅的「nodrop」位也一样。对水和岩浆，源头是静止方块，其余都是流动方块。地面上的头颅朝上。比较器的方块与它的充能状态对应。

1.12 里拆分方式不同的方块做了专门处理：

- **门**：下半存储 facing/open，上半存储 hinge/powered。
- **双层植物**：上半是 `double_plant:10`。
- **数据存在 TileEntity 里的方块**：床（颜色）、旗帜（颜色、图案）、头颅（类型、旋转、玩家档案）、花盆（植物）和音符盒（音符）变成一个 1.12 方块加上它的 TileEntity。

### TileEntity 和物品

| 1.21 | 1.12 |
|---|---|
| 告示牌正面文字 | `Text1..4`（保留 JSON）。背面文字、文字颜色和发光会被丢弃，并给出警告 |
| 箱子、陷阱箱、熔炉、发射器、投掷器、漏斗、酿造台、潜影盒、唱片机 | 物品做转换（见下文）；保留自定义名称、锁、战利品表；熔炉进度重置 |
| 旗帜 | `Base` 和 `Patterns`（1.12 用染料 damage 表示颜色）；1.14+ 的图案被丢弃，并给出警告 |
| 头颅 / 玩家头颅 | `SkullType`、`Rot`、`Owner`（Id、Name、皮肤材质）。猪灵头颅是错误 |
| 刷怪笼 | 实体类型和计时；`SpawnPotentials` 被丢弃，并给出警告 |
| 命令方块、结构方块 | 错误（不写回） |

物品：id 表的建立方式与原版方块表相同，来自 DataFixer 的物品 ID 表、物品扁平化以及之后的改名。它覆盖 499 种物品。数量、damage（变种或耐久）、自定义名称、lore 和附魔（1.12 的数字 id）会保留；其他组件被丢弃，并给出警告。没有 1.12 形式的物品会被略去，并给出警告。

### Lint

| 代码 | 级别 | 含义 |
|---|---|---|
| `no_1_12_form` | 错误 | 1.12 无法表示的方块、状态或方块实体 |
| `outside_height` | 错误 | y 0..255 之外的非空气方块 |
| `waterlogged` | 警告 | 水会被丢弃 |
| `lossy` | 警告 | 写回时有内容被略去（见上面的表格） |

有错误时 `diff` 不会写出 patch。

## Patch 格式（`patch.nbt`，gzip NBT）

```
{Version: 1, Zone: "name", Created: <ms>, Min: [I; x, y, z], Max: [I; x, y, z],
 Entries: [{Pos: [I; x, y, z], Name: "minecraft:wool", Meta: 14b,
            Expect: {Name, Meta},          签出时原服上的内容（用于冲突检测）
            TileEntity: {id, x, y, z, ...}}, ...]}
```

使用名称而不是数字 id：bridge 在应用 patch 时，对照原服当前的注册表来解析它们。

## 验证（开发服务器，出生点周围 9 个 region，2026-11-08）

- `VanillaReverseTest` 在 JUnit 里运行原版 Minecraft，检查：
  - 全部 1,650 个规范原版 meta 都能往返；
  - 扁平化改名、楼梯、栅栏、门、双层植物、点燃的熔炉和液体；
  - 床、花盆、带档案的玩家头颅、墙上的头颅和带图案的旗帜；
  - 箱子里的物品（羊毛、带附魔和命名的剑、矮草丛；一个仅 1.21 才有的物品被略去）；
  - 告示牌，以及 `reverse.json` 的优先级。
- 签出出生点附近一个 61×71×51 的区域：用原服内容刷新（修改了 0 个方块），快照用时 56 毫秒。下面这些编辑产生了后面所说的 patch：
  - 羊毛、橡木楼梯、安山岩楼梯、含水的栅栏、装有物品的箱子、告示牌、沥青的子类型、玩家头颅和蕨类花盆；
  - 用 `/clone` 移动的两个 C&B 方块；
  - 樱花木板。

  结果：
  - 樱花木板 → `no_1_12_form` 错误，没有生成 patch；
  - 移除它们之后，得到一个每个方块和 TileEntity 都正确的 patch；两个 C&B 方块带着它们原来的 1.12 数据；
  - 从含水栅栏流出的水以 `flowing_water` 条目的形式出现。
- 区域签出期间，原服在区域内的修改没有被同步，区域外的修改被同步了；`abandon` 恢复了区域（之前被挡住的修改到来了，镜像上的编辑消失了）。
- 区域在重启后仍然保留，重叠的签出被拒绝，手动的 `sync refresh` 也会跳过已签出的区域。

## 完整闭环的验证

见 [bridge.md](bridge.md#验证镜像--bridge-端到端2026-11-08)：
- 签出在原服锁定了区域；
- 一次提交因冲突被拒绝，然后被强制提交；
- 299 个方块被应用、回读，往返结果完全一致，包括箱子里的物品、告示牌文字和床的颜色；
- 放弃时解锁了区域；
- 连不上 bridge 时干净地失败。

## 在区域之外建造

在签出的区域之外，镜像处处跟随原服：

- **事先拒绝**：玩家在那里不能破坏或放置方块、对方块使用物品，也不能使用桶；他们会看到一条消息，提示先签出一个区域。玩家的 WorldEdit 编辑会跳过区域之外的方块，并提示一次。玩家可以在自己签出的区域内建造；有 `nebulaemirror.zone.others` 时可以在任何区域内建造。在 config/nebulaemirror-sync.json 里设置 `"protectOutsideZones": false` 可以关闭这个限制。用 WorldEdit 魔杖、导航魔杖和绑定了 WorldEdit 工具的物品点击，会交给 WorldEdit 处理（选点在任何地方都可以；工具产生的编辑和其他 WorldEdit 编辑一样受限）。
- **在下一次同步时还原**：仍然在那里发生的任何变化。这包括 `/setblock`、`/fill` 之类的命令、控制台的 WorldEdit、Axiom（它没有服务端钩子可以用来拒绝，但它的写入会照常标记区块）、开门、水、火和植物生长。见 [sync.md](sync.md)。

尚未用真实玩家试过的：游戏内的拒绝提示，以及玩家使用 WorldEdit。记录和重新覆盖已经测试过（`/setblock`、`/fill`、控制台 `//set`、重启、`sync apply`）。

## 故障排查

- **提交的往返校验报告「N 个方块中有 N 个不一致」，而且区域在镜像里看起来还是老样子**（而原服上能看到修改）：拉取没有复制有变化的 region 文件，所以镜像用过时的副本刷新了区域。在 Windows 上，robocopy 对运行中的原服保持打开的 region 文件就是这样；请在 pull.json 里使用 `"copier": "header"`（见 [sync.md](sync.md#windows)）。原服不受影响。下一次用 header 复制器的拉取，或者原服重启后的任意一次拉取，都会把修改带进镜像。

## 尚未完成

- 不读取 Axiom 的选区（Axiom 没有服务端的选区 API）；请用 WorldEdit 选择，或直接给出两个角。
- 尚未在有真实玩家和 FAWE 的原服上测试：游戏内的锁，以及 FAWE mask（见 [bridge.md](bridge.md)）。
