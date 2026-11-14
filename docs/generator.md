# 生成器（dump → 1.21.1 镜像定义）

`mirror/generator/` 把 MirrorDumper 的 dump 变成 1.21.1 镜像 mod 和同步工具需要的一切。纯 Python 标准库，没有构建步骤。

```bash
python3 -I mirror/generator/generate.py --dump dumps/<dump 目录> [--mc work/mc/1.21.1] [--overrides mapping/overrides] [--out work/gen]
    [--mods data/mods.zip] [--nebulaecraft ../NebulaeCraft/src/main/resources]
```

`--mods`（原服的 mods zip）提供 MoarSigns 的告示牌贴图；`--nebulaecraft`（NebulaeCraft 2.26 源码的资源目录）提供道路标线字体，找不到时回退到 mods zip 里的 NebulaeCraft jar。两者只有 P1 的文字渲染器需要；缺少时生成器给出警告并继续。

`--mc` 需要 1.21.1 参考数据（方块报告 + 原版贴图列表）。先运行一次 `ops/mc/fetch_121.sh` 来生成。输出目录每次运行都会重建；为了避免误删，它只清空自己创建的目录。

## 输出

| 文件 | 使用者 | 内容 |
|---|---|---|
| `mirror/blocks.json` | 镜像 mod | 每个生成的方块：原型、属性（kind/values/`stored`/`derived`/`legacy`）、默认状态、每个状态的亮度/不透明度/形状索引、渲染层、音效、自发光、染色、旧版 TE 标志、物品子类型（及其放置出的状态）和中英文名称。形状去重后放在顶层的 `shapes` 表里 |
| `mirror/forward.json` | 导入器（原服 → 镜像） | `"ns:name" → {target, metas: {meta: {1.21 属性}}}` |
| `mirror/reverse.json` | 提交（镜像 → 原服） | `"1.21 方块" → {keys, map: {"k=v,...": [1.12 名称, meta]}}`。`keys` 只包含存储在 1.12 meta 里的属性；由邻居推导的属性（连接、楼梯形状）被忽略 |
| `mirror/vanilla_metas.json` | 写回 | 原版 1.12 方块 → 规范 meta 及其 1.12 状态，来自 dump 的 meta 表；mod 对它们运行 DataFixer 来建立原版方块的反向表（见 [writeback.md](writeback.md)） |
| `mirror/source.json` | 可追溯性 | dump 版本、每个 mod 的版本、用到的 override 列表 |
| `assets/<ns>/blockstates/*.json` | 客户端 | variants → `nebulaemirror:mesh/<hash>` |
| `assets/nebulaemirror/meshes/<hash>.json` | 客户端 | 烘焙后的 quad（方块空间顶点、相对于 sprite 的 0..16 UV、`layer`、`cull`、`tint`、`shade`、`emissive`），按内容去重 |
| `assets/<ns>/textures/block/…` | 客户端 | mod 贴图。原版贴图引用 1.21 自己的资源（`minecraft:block/black_concrete` 等）；只有在没有对应贴图时才把 1.12 的文件放到 `nebulaemirror:block/legacy/minecraft/…` 下一起发布 |
| `assets/<ns>/lang/{en_us,zh_cn}.json` | 客户端 | 方块名称，以及每个子类型的旧版翻译键 |
| `assets/nebulaemirror/optifine/ctm/<ns>/*.properties` + `textures/block/ctm/…` | 装了 Continuity 的客户端 | Chisel 的 CTM 1.12 连接/图案/随机贴图转换成的 Continuity（OptiFine 格式）规则，瓦片从 CTM 的图集里切出（见下文） |
| `assets/nebulaemirror/legacy/railcraft_tracks.json` | 客户端 | Railcraft 套件轨道 `"<rail>\|<kit>\|<shape>\|<state>"` → mesh，来自 dump 的 `extras/railcraft_tracks.json`（MirrorDumper 1.2，原 0.2.1；`nebgen/tracks.py`）。旧 dump 里没有：生成器给出警告，轨道保持默认套件的模型 |
| `assets/nebulaemirror/textures/moarsigns/…`、`assets/nebulaemirror/roadmark/*.ttf` | 客户端 | 供文字渲染器使用的 MoarSigns 告示牌贴图和 NebulaeCraft 道路标线字体 |
| `report.json` / `report.md` | 人 | 计数、错误、警告（按类型分组并列出方块）、缺失贴图、外来的物品子类型等 |

## 关键规则

- **原版方块不在这里生成**；导入器用 Minecraft 自带的 DFU 处理它们。mod 方块保留原来的命名空间（例如 `nebulaecraft:asphalt`），所以可以直接在 WorldEdit/Axiom 里写它们的名字。
- **原型（archetype）**：1.12 里 `BlockStairs`/`BlockDoor`/`BlockTrapDoor`/`BlockFenceGate`/`BlockWall`/`BlockFence`/`BlockPane` 的子类映射到对应的 1.21 行为，所以连接和楼梯形状在 1.21 里重新计算。这些方块的额外属性（例如颜色）会保留。其余都是 `generic`，原样保留 1.12 的属性：
  - `facing` 类属性 → `direction`；
  - 连续整数 → `int`；
  - `axis` → `axis`；
  - 其他 → 字符串枚举。

  WorldEdit/Axiom 里的旋转和镜像依赖前三种类型。
- **物品子类型**：物品 damage 不等于方块 meta（在 NebulaeCraft 里，meta 1 = 朝南，而物品 1 = subtype1）。放置出的状态按以下顺序确定：
  1. `getPickBlock`；
  2. `damageDropped`；
  3. 翻译键里出现的属性取值；
  4. 物品 damage = 方块 meta。
- **别名 meta**：有些方块把多个 meta 解码成同一个状态（Chisel 的 `marble2` 把 meta 8–15 都当作 variation 7；世界里有 11 亿个这样的方块）。正向映射把它们映射到实际渲染的状态；反向映射写回规范 meta（正向映射在 `aliases` 里记录这一点）。没有改动过的方块永远不会被写回，所以原来的别名 meta 得以保留。
- **单值属性**（Quark 的 `prop=blarg`、NebulaeCraft 的单值 `subtype`）不携带任何信息，1.21 也不允许，所以它们被丢弃，映射回 1.12 时再作为常量补上。
- **可写性**（`blocks.json` 里状态上的 `writable: false`）：只有当镜像状态里**1.12 存储在 meta 中的属性**能对上某个 meta 时，它才能被写回。1.12 自己计算的属性（连接、楼梯形状、Sakura 栅栏的 `up`、Railcraft 立柱的连接）不限制可写性，因为写回之后原服会重新计算它们。门按上下半分别检查（下半：facing/open；上半：hinge/powered）。不可写的状态在创造模式标签页里隐藏，并被提交时的 lint 拒绝。
- **完整状态空间**：1.21 会为方块创建所有属性组合，所以每个组合都要有模型。dump 里没有任何状态覆盖的组合（例如 Extra Utilities 2 管道的连接）借用最接近的状态，并标记为不可写。
- 每次运行都做**自检**；任何失败都是错误，并以非零状态退出：
  - 每个 meta 正向 → 反向之后回到同一个（name, meta）；
  - 原型的 variants 覆盖完整的状态空间；
  - 正向映射的状态对目标方块是合法的；
  - 每个方块的默认状态都有 variant。
- **CTM → Continuity**（`nebgen/ctm.py`）：mesh 用到的每个 sprite，只要它的 1.12 `.mcmeta` 里有 `ctm` 段，就会被转换。CTM 自己的类型决定 Continuity 的方法和瓦片顺序：

  | CTM 1.12 / Chisel | Continuity | 备注 |
  |---|---|---|
  | `ctm`（基础图 + 2×2 的 `-ctm` 图集） | `ctm_compact` | 瓦片 0 基础、1 完整、2 竖向、3 横向、4 内角 = CTM 图集的 左上/右上/左下/右下 |
  | `ctmh` | `horizontal` | |
  | `pillar` / `ctmv` | `vertical`，仅侧面 | 竖直的柱子；横放的柱子保持不连接 |
  | `pattern` / `v` | `repeat` | 每组面一个文件，所以瓦片相位与 CTM 的世界坐标公式完全一致 |
  | `random` / `r`、Chisel `AR` | `random` | 瓦片相同；哪个方块得到哪个瓦片与 1.12 不同 |
  | Chisel `SCTM` | `fixed` | 未连接的瓦片 |

  连接使用 `connect=state`（CTM 的默认值）。动画图集保留它们的帧。文件名从不以 `block_` 开头，否则 Continuity 会把它当成方块匹配。CTM 的 `light: 15` 会把 quad 标记为自发光（dump 里这些 quad 已经带有该标记）。
- **仅由 TESR 绘制的方块**：MoarSigns 的状态在 1.12 里是 `INVISIBLE`，但 dump 里带有一个烘焙出的占位立方体；它们的 mesh 留空，因为镜像的告示牌渲染器会绘制它们。
- **原型规格**在每次运行时都会对照 1.21.1 方块报告里真实的属性集合进行检查，所以 Minecraft 版本变化会立刻被发现。

## 手写映射 `mapping/overrides/*.json`

```json
{
  "blocks": {
    "futuremc:smooth_stone": {"target": "minecraft:smooth_stone"},
    "futuremc:stripped_spruce_log": {"target": "minecraft:stripped_spruce_log", "props": {"axis": "axis"}},
    "quark:example": {
      "target": "minecraft:example",
      "state": {"type": "bottom"},
      "props": {"variant": null, "facing": "facing"},
      "values": {"facing": {"up": "north"}},
      "metas": {"5": "unknown", "6": {"type": "top"}}
    },
    "somemod:odd_block": {"archetype": "generic"},
    "nebulaecraft:device_apple_iphone": {"unknown": true}
  }
}
```

- 现有的规则文件：`futuremc.json`（全部 79 个 FutureMC 方块 → 对应的 1.21 原版方块）和 `quark.json`（49 个 Quark 方块：木质按钮/压力板/活板门，以及原版石材的楼梯/台阶/墙）。有了它们，建筑者可以在镜像里使用 `andesite_stairs` 这样的原版方块，写回时会变成对应的 FutureMC/Quark 方块。
- `target`：映射到已有的 1.21 方块，而不是生成一个新方块。对每个 meta，1.21 状态由固定的 `state` + 改名后的 1.12 属性（`props`：旧名 → 新名，`null` 表示丢弃）+ 取值改名（`values`）组成。`metas.<n>` 覆盖单个 meta（`"unknown"` = 把这个 meta 保留为占位方块；带 `"@target"` 的字典把这个 meta 送到另一个方块，并完全替换推导出的属性，例如 `axis=none` 的 FutureMC 去皮原木 → `stripped_*_wood`，或朝上的灵魂火把 → `soul_torch`；别名 meta 使用其规范 meta 的规则）。目标属性和取值会对照 1.21.1 报告检查；任何不匹配都是错误。加上 `"reverse": false` 后，镜像永远不会把这个方块写回。
- `archetype`：强制指定原型，或 `generic`。
- `unknown`：什么都不生成；导入器把这些方块保留为占位方块，原始数据不变。

多个 1.12 方块共用一个目标时，反向映射的优先级是：生成方块自己的目标 > override 的主目标 > 单个 meta 的 `@target` 重定向；同一优先级内先出现的胜出。冲突列在报告的 `reverseAmbiguous` 里。例如，1.21 的金合欢木活板门写回成 `futuremc:acacia_trapdoor`，而不是 Quark 的；1.21 的制图台没有朝向，所以写回成一个固定的 meta。反向映射的键只包含在各规范 meta 之间确实有变化的属性（FutureMC 木桶的 `open` 存在 TE 里，被忽略）。

## 当前结果（完整模组包 dump，2026-11-07）

- dump 了 1755 个方块；生成 1373 个（81 个楼梯、23 个玻璃板类、2 个栅栏、1 个栅栏门、1 个门、1265 个普通方块）；128 个通过 override 映射到 1.21 原版；39,011 个状态 / 14,750 个 mesh，0 个错误，约 15 秒。
- 世界覆盖率（`ops/scan`）：世界里用到的每个（name, meta）都有映射，只有 2 个很久以前移除的 NebulaeCraft 方块除外（`device_apple_iphone` ×1106、`countdown` ×1），它们变成 unknown 占位方块。
- 贴图：复制了 3611 张 mod 贴图；101 个原版引用指向 1.21 自己的贴图；1 张保留为 1.12 回退贴图；Extra Utilities 2 的运行时 sprite（23 个）从图集读回。
- 值得注意的警告：
  - 约 38 个方块引用了在 1.12 里就缺失的贴图。其中包括 4 个 NebulaeCraft 方块（`device_ms_surface`、`fire_hydrant`、`info_screen_5`、`screen_door_top`），需要在 NebulaeCraft 里修复。
  - 66 个方块没有 quad（大多是 Chisels&Bits，它根据 TE 数据渲染）；由 P1 处理。
- CTM：转换了 654 张贴图（365 张连接、125 张图案、117 张随机/AR、26 张柱状、15 张横向、6 张 SCTM），得到 1154 个 properties 文件和 3275 个瓦片。
  - 约 139 个方块有 1.12 根据邻居/TE 推导的外观属性。镜像按存储的值显示它们；主要是 Railcraft 的立柱/电线和 Quark 的花盆。
  - 约 3984 个不可写的状态，几乎全是 Chisel 没用到的 variation。
