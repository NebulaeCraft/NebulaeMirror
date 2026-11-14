# 由 TileEntity 决定的外观（P1）

有些 1.12 方块的外观完全来自 TileEntity 数据（Chisels&Bits 体素、ArchitectureCraft 形状、NebulaeCraft 道路文字、MoarSigns、Railcraft 套件轨道的套件）。镜像把这些数据**原样**保存在 `nebulaemirror:legacy` 方块实体里（`Legacy` 标签，写回时原样写回），并在客户端根据它来绘制。

## 数据流

1. **导入时**（`importer/RenderDataDeriver`），TileEntity 里依赖 1.12 数字 ID 的部分被翻译成 1.21 方块状态，单独存放在 `Render` 标签里：
   - Chisels&Bits：`{Kind:"cb", States:[{Id, State}]}`，把 blob 调色板的每个条目（`id | meta<<12`）映射到一个 1.21 状态；
   - ArchitectureCraft：`{Kind:"ac", Base, Second?}`，基础/次要材质对应的 1.21 状态。

   状态由 `StateResolver` 解析：mod 方块走 `forward.json`；原版方块走 Minecraft 自己的 1.12 扁平化表（`BlockStateData`）加上之后的方块状态数据修复。`Render` 是派生数据：写回会忽略它，并且随时可以重新生成。
2. **在客户端**，`LegacyBlockEntity#getRenderData()` 只解码一次（缓存到数据变化为止）。带有旧版数据的方块，其静态模型会被 `LegacyModel` 包装。区块生成 mesh 时，数据通过 Fabric 的 block-view API 传入，发出对应的 quad，直接进入区块 mesh。所以**没有每帧开销**，这一点不同于 BlockEntityRenderer。
3. **文字方块**（roadmark_text、MoarSigns）无法做成 mesh：它们的文字是在运行时光栅化或用字体绘制的。它们使用 `LegacyTextRenderer`，这是一个 BlockEntityRenderer。所有旧版方块实体共用一个类型，所以 `BlockEntityRenderDispatcherMixin` 让 `getRenderer` 对所有不是文字方块的旧版方块实体返回 null。原版和 Sodium 在为 section 生成 mesh 时都会调用 `getRenderer`，所以约 370 万个 C&B/AC 方块实体永远不会进入每帧渲染的列表。文字方块不需要 `Render` 标签，在 P1 之前导入的世界上也能工作。

## Chisels&Bits

- `legacy/ChiselsBlob`：独立实现的 C&B 1.12 紧凑体素格式读取器（zlib + varint 调色板 + 位流；体素索引 `x | y<<4 | z<<8`）。世界里只存在紧凑格式（版本 0）。
- `client/legacy/ChiselsRender`：按面和深度层做贪心网格合并。每种 bit 状态使用它自己的 1.21 模型在该面上的 sprite 和染色，渲染层也跟随该状态。只有当相邻体素是空气或另一种透视状态时才绘制这个面。每个方块的 mesh 只构建一次并缓存。
- 验证：
  - 一个单元测试解码了世界里的一个真实 blob，并把体素计数与独立的 Python 解码器对比（3960/64/72）；
  - 在测试区域的巡览里，单个视角构建了 17,248–29,489 个 C&B mesh（40 万–117 万个 quad），塔楼的竖框和窗框这类细小的细节都能渲染出来。

## ArchitectureCraft

移植自 ArchitectureCraft 1.12（MIT；见 `mirror/mod/THIRD_PARTY_NOTICES.txt`）：

- `ArchitectureShapes`：形状表（EnumShape）加上附带的 108 个 `.objson` 模型；
- `ArchitectureRender`：对 MODEL 类型，模型 → 旋转 `sideRotations[side]·turnRotations[turn]`（栏杆还要加 `offsetX`）→ 方块空间。贴图槽 0/1 = 基础材质（模型 UV / 按面的方向投影），2/3 = 次要材质（默认为基础材质）。mesh 按形状 + 朝向 + 材质共享；
- `ArchitectureRoofs`：程序化生成的屋顶。覆盖了实际用到的 4 种屋顶形状：瓦面 ×58,291、外角、内角、屋脊。智能屋脊/天沟的邻居连接没有复现；世界里没有天沟。
- `ArchitectureWindows`（RenderWindow、WindowShapeKinds）：窗框 / 窗角 / 竖框（形状 30–32；世界里有 764 个窗框和 8 个竖框，都没有玻璃）。只有在没有相匹配的窗户延续的地方才绘制窗框的边，所以 mesh 取决于邻居：区块生成 mesh 时会读取邻居的渲染数据（包括 `Disconnected` 位），得到的窗框标志是 mesh 键的一部分。玻璃（次要材质）保持自己的渲染层。
- 没有移植：覆层（60）和智能屋脊/天沟屋顶；世界里一个都没用到。它们会显示为基础材质的完整方块。
- 验证：在截图里，桥墩、建筑檐口和地铁站道床（铁质屋顶形状）不再显示缺失贴图；每个区域由 12–15 个共享 mesh 覆盖。在位于 (−970, 69, 321) 的车站，每一对相邻的窗框合并成一个窗洞，只在外侧的边上有框。

## NebulaeCraft roadmark_text

移植自 NebulaeCraft 2.26 的 `RenderRoadmarkText`。`RoadmarkText`/`RoadmarkColor`（白色或黄色）用 mod 的道路标线字体（先用拉丁字体，其他字符用完整字体）光栅化成一张 180×390 的贴图，平铺在方块上方 0.01 处，拉伸到 1.25（一个字符）或 1.75（两个及以上字符）× 1.875 个方块，并按 `facing` 旋转。贴图按文字 + 颜色共享（世界里有 1347 个方块）。可视距离和 1.12 一样是 224 格；即使方块本身在屏幕之外也会绘制，因为这个 quad 比方块大。字体来自生成器输出（`assets/nebulaemirror/roadmark/`）。

## MoarSigns

移植自 MoarSigns 1.12 的 `MoarSignRenderer`/`ModelMoarSign`/`TileEntityMoarSign`（LGPL-3.0，见 `THIRD_PARTY_NOTICES.txt`）：24×12×2 的牌面（立式告示牌还有立杆），使用告示牌的贴图（`texture` + `isMetal`；世界里用到 12 种原版材质的贴图），根据 1.12 的 metadata 摆放（立式的旋转、墙上的朝向、平放/贴地标志），然后是最多四行 JSON 文字，带有 MoarSigns 的逐行偏移、大小、可见性和阴影（`settings`，NBT 版本 1 和 2）以及它的宽度限制。世界里有 4692 个告示牌。方块的 1.12 占位模型不会被绘制（它在 1.12 里是 `INVISIBLE`）。

## Railcraft 储罐

世界里全部 11,785 个储罐壁/阀门都没有成型（`pattern: -1`，没有主方块，没有流体）。没有成型的 Railcraft 12 储罐壁就是一个普通的染色柱状模型，静态 mesh 已经能还原它（包括 16 种颜色），所以不需要渲染器。成型的储罐需要流体 TESR；只有出现了成型的储罐才需要重新考虑。

## Railcraft 套件轨道

`railcraft:track_outfitted`（世界里有 3080 个，TileEntity 为 `railcraft:track.outfitted` / `.ticking`）的外观来自 TileEntity：`rail`（轨道类型）、`kit`（轨道套件）和套件的渲染状态。Railcraft 12 的 `OutfittedTrackModel` 按（类型, 套件, 形状, 状态）把它们组合起来：COMPOSITE 套件绘制该类型的轨道模型加上套件模型，UNIFIED 套件（道岔、Y 形岔、交叉）绘制一个合并的模型。本服的 Railcraft 构建还会在电气 / 高速 / 高速电气 / 强化轨道上隐藏 COMPOSITE 套件（止冲挡除外），除非玩家开着轨道护目镜的光环，所以例如强化加速轨道看起来就像普通的强化轨道。

镜像不会重新组合这些。数据是这样流动的：

1. **Dumper 1.2**（原 0.2.1）（`TrackKitExporter`，只用反射）为每种轨道类型 × 套件 × 形状 × 渲染状态构造扩展方块状态，并在光环关闭的情况下烘焙 Railcraft 自己的组合模型。结果写到 `extras/railcraft_tracks.json`（见 [dumper.md](dumper.md)）。
2. **生成器**（`nebgen/tracks.py`）把每个变体变成一个普通的 mesh，与其他所有 mesh 一起去重，另外生成 `assets/nebulaemirror/legacy/railcraft_tracks.json`（`"<rail>|<kit>|<shape>|<state>"` → `nebulaemirror:mesh/<hash>`）。
3. **客户端**：模型加载插件加载这张表，并把其中的 mesh 作为额外模型烘焙。`client/legacy/OutfittedTrack` 是一个随区块生成 mesh 的 `LegacyRender`（不是 BlockEntityRenderer）。区块生成 mesh 时，它计算出键，查到烘焙好的 mesh，把它发到区块 mesh 里，并抑制静态的默认套件模型。键未知时（旧的生成器输出、未知的套件），和以前一样绘制静态模型。

键只来自 1.12 的 TileEntity（`legacy/OutfittedTrackKey`），所以**不需要 `Render` 标签，也不需要重新导入**。形状来自镜像方块的 `shape` 属性，取不到时回退到 1.12 的 meta。状态是对每种套件的 `getRenderState()` 的移植：

| 套件 | 状态 |
|---|---|
| turnout | switched +1，`Reversed` +2，mirrored（`Direction`）+4；switched = 没有 `locked` 且（`Switched` 或 `sprung`） |
| wye | （mirrored ≠ switched）+1，（mirrored ≠ 形状为 east_west）+2 |
| buffer | `direction` |
| control | `powered` 异或 `reversed` |
| transition | `reversed` +1，`powered` +2 |
| one_way | `powered` +1，`direction` +2 |
| booster、activator、launcher、priming、embarking、whistle、messenger、dumping、routing | `powered` |
| disembarking、gated | 按 Railcraft 的实现移植 |
| junction，以及没有移植的套件（locking、delayed、detector、coupler、locomotive、throttle；世界里都没有） | 0 |

世界里用到 6 种轨道类型和 11 种套件（强化道岔 ×1917、止冲挡 ×571、Y 形岔 ×248、加速器 ×248 等），合计 86 个不同的键。道岔状态是所保存的 TileEntity 的快照：区块保存的那一刻处于扳动状态的道岔会一直显示为扳动状态。

- 验证：
  - `OutfittedTrackKeyTest` 用世界里真实的 TileEntity NBT 检查键。生成器测试检查变体 → mesh 的去重和这张表，以及没有该文件的 dump 只会产生一条警告。
  - 完整模组包 dump `20261107-200107`（MirrorDumper 0.2.1）：4214 个变体，没有轨道相关的错误。模型与之前开发客户端的 dump 一致：quad 相同，UV 因为图集更大而相差不到 0.01 个纹素。生成时 0 个错误，得到 1046 个轨道 mesh，包含所有套件贴图。
  - 对导入后的世界做的巡览加载了全部 4214 个变体，绘制了 449 条轨道，0 个未匹配的键。在截图里，(−491, 68, −359) 处的铁质道岔和 Y 形岔，以及 (−497, 67, −234) 处车站里的强化道岔都显示出了尖轨，尽头有止冲挡。(−3000, 102, 599) 处的铁质控制轨道显示出了控制板，斜坡上的也一样。高速加速轨道显示为普通轨道（套件被隐藏）。

## Chisel 连接贴图（CTM）

在生成时转换成 Continuity 规则（见 [generator.md](generator.md)）；客户端需要 Continuity。在 Chisel 铁/殷钢板材处 (−529, 83, 138) 分别在有和没有 Continuity 的情况下检查过：有的时候，相邻方块合并成大块面板，只在外侧有边框，和 1.12 里一样。

## 其他修复

- 生成器现在会修复 1.12 能容忍但 1.21 会拒绝的 `.mcmeta` 动画文件（末尾多余的逗号、UTF-8 BOM）：一个 Chisel 文件和 32 个 Sakura 流体文件。

## 未覆盖的内容

| 项目 | 状态 |
|---|---|
| Railcraft 套件轨道 | 只要 dump 是用 MirrorDumper 1.2 / 0.2.1 做的（`extras/railcraft_tracks.json`），就根据 TE 数据绘制；用更旧的 dump 时是默认套件的模型。locking/delayed/detector/coupler/locomotive/throttle 套件（世界里没有）始终显示状态 0 |
| ArchitectureCraft 覆层、智能屋脊/天沟屋顶 | 世界里没有用到；显示为基础材质的完整方块 |
| CTM `random`/`AR` | 瓦片与 1.12 相同，但哪个方块得到哪个瓦片不同 |
| CTM `pillar` | 只有竖直的柱子会连接 |

**注意**：在 P1 之前导入的世界没有 `Render` 标签，所以 C&B/AC 方块（包括窗户）保持原样（不可见或缺失贴图）。重新运行导入来补上它。文字方块和 CTM 不需要重新导入就能工作。
