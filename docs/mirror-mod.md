# 镜像 mod（Fabric 1.21.1）

`mirror/mod/` 把生成器输出里的每个 mod 方块注册到 1.21.1。**服务端和客户端都要安装。** 生成器输出（`work/gen`）在构建时打包进 jar；输出变了就要重新构建并重新分发 jar。

客户端还应安装 **Continuity**（3.0.0+1.21，Fabric），用于 Chisel 的连接、图案和随机贴图；转换好的规则已随 jar 提供（见 [generator.md](generator.md)）。没有 Continuity 时，这些方块显示未连接的贴图。开发客户端默认带 Continuity；加 `-PnoContinuity` 可以对比不带它的效果。

## 构建与测试

需要 Java 21+（本机的 Java 25 可用；编译时使用 `--release 21`）：

```bash
python3 -I mirror/generator/generate.py --dump dumps/full
```

```bash
cd mirror/mod && ./gradlew build
```

输出是 `mirror/mod/build/libs/NebulaeMirror-1.21.1-<版本>.jar`。用 `-PgenDir=...` 可以指定别的生成器输出目录。

自检（会打开游戏窗口并自动退出，约 20 秒）：

```bash
cd mirror/mod && ./gradlew runSelftestClient
```

它会写入 `mirror/mod/run/selftest/`：

- `nebulaemirror-selftest.json`：有多少方块状态和物品拿到了 mesh 模型，以及缺失的列表；
- `nebulaemirror-icons/<mod>.png`：每个创造模式子类型的等轴测渲染图，每个来源 mod 一张，用于和 1.12 游戏对比。

## 工作原理

| 部分 | 说明 |
|---|---|
| 方块注册 | 读取 `blocks.json`。原型（archetype）方块继承原版行为类（`StairBlock`/`IronBarsBlock`/`FenceBlock`/`WallBlock`/`FenceGateBlock`/`DoorBlock`/`TrapDoorBlock`），因此连接和楼梯形状由 1.21 重新计算；它们额外的 1.12 属性会追加上去。其余都是 `MirrorBlock`：属性与 1.12 一致，每个状态的形状、亮度和碰撞来自 dump，`direction`/`axis` 属性参与旋转和镜像（WorldEdit/Axiom 的变换）。所有定义在构造方块之前先校验，所以一个坏定义只会跳过那一个方块 |
| 属性 | bool/int/direction/axis 使用原版属性类型；其他枚举使用 `StringProperty` |
| 物品与创造模式标签页 | 每个来源 mod 一个标签页。每个 1.12 物品子类型变成一个带 `block_state` 组件的物品堆（放置后得到该子类型），并保留原名。无法写回的状态会被隐藏。玩家可以在客户端配置里隐藏标签页、选择图标，见 [creative-tabs.md](creative-tabs.md) |
| 模型 | `PreparableModelLoadingPlugin` 读取 `assets/nebulaemirror/meshes/*.json`，用 Fabric Renderer API 烘焙：每个 quad 的混合模式（solid/cutout/translucent）、自发光（全亮、无着色、无 AO），并为不支持 FRAPI 的渲染器派生出原版路径的 quad。Sodium 0.6+ 实现了 FRAPI |
| 染色 | 草/树叶跟随生物群系；固定颜色不变；`state` 颜色按状态变化（Railcraft 的 16 色立柱、储罐、玻璃等） |
| `nebulaemirror:legacy` 方块实体 | 在 1.12 里带 TileEntity 的普通方块，以及 `nebulaemirror:unknown`，都原样保留原始 NBT（`Legacy` 标签），并同步给客户端供 P1 渲染器使用（见 [legacy-renderers.md](legacy-renderers.md)）。大多数随区块一起生成 mesh；只有文字方块使用方块实体渲染器，客户端有一个 mixin 让其他旧版方块实体不走这个渲染器 |
| `nebulaemirror:unknown` | 未映射的 1.12 方块的占位方块；其数据保留下来用于写回 |
| 增量同步 | `sync/` 包：`/nebulaemirror sync ...` 命令、定时器、变更检测，以及对在线世界/磁盘的应用，见 [sync.md](sync.md)。仅服务端；通用 mixin `ChunkMapAccessor` 用来判断区块是否在内存里 |
| 命令 | 全部在 `/nebulaemirror` 之下：`sync ...`（[sync.md](sync.md)）、`zone ...`（[writeback.md](writeback.md)）、`chat status`（[chat.md](chat.md)）、`stats`（[mod-updates.md](mod-updates.md)）。`/nm` 是短写（mod 1.6+）：它重定向到同一棵命令树，所以 `/nm zone list` 就是 `/nebulaemirror zone list`，权限节点和 Tab 补全都相同。玩家、控制台和 RCON 都能用。如果别的 mod 已经占用了 `/nm`，短写不会注册，日志里会说明 |
| 区域边界 | 签出的区域会在每个客户端上描边（mod 1.7+），自己的为青色，他人的为黄色：服务端发送区域的包围盒（`writeback/ZoneOutlines`），`client/ZoneOutlineRenderer` 负责绘制，见 [writeback.md](writeback.md#游戏内的区域边界) |
| WorldEdit 别名 | `worldedit/` 包：FAWE 风格的短命令（`//s` → `//set` 等），实际执行完整的 WorldEdit 命令；列表在 `config/nebulaemirror-we-aliases.json`，见 [worldedit-aliases.md](worldedit-aliases.md) |

## 当前结果（完整模组包 dump）

- 注册 1373 个方块用时 0.6 秒（81 个楼梯、23 个玻璃板类、2 个栅栏、1 个栅栏门、1 个门、1265 个普通方块）。另有 128 个方块通过 override 映射到 1.21 原版方块，不需要注册。
- 14,750 个 mesh；**全部 47,435 个方块状态和全部 1373 个物品都有 mesh 模型**（其中 346 个状态是故意留空的：C&B、roadmark_text、MoarSigns，它们根据 TE 数据绘制）；共 123 万个 quad。自检能看穿 Continuity 这类包装模型。
- 图标图里，NebulaeCraft（道路标线、标志、交通灯、屏蔽门、灯具）、Quark、Railcraft（包括 16 色变种）、Sakura 等看起来都正确。

## 已知限制（计划在后续阶段处理）

- 物品图标只显示每个方块的默认子类型；放置到世界里是正确的。
- 由 TileEntity 决定的外观由 P1 处理（[legacy-renderers.md](legacy-renderers.md)）。只要 dump 里有 `extras/railcraft_tracks.json`（MirrorDumper 1.2 / 0.2.1），Railcraft 套件轨道就会根据 TE 数据绘制套件；用更旧的 dump 时保持静态的默认套件模型。仍然使用静态模型的还有：Quark 彩色花盆里的内容。
- MoarSigns 的物品图标是空的（1.12 里也是用 TESR 画的）。
- 原版方块不需要这个 mod 做任何事：导入器用 DFU 转换它们。
