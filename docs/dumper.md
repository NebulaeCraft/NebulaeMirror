# MirrorDumper（1.12.2 客户端 mod）

导出 1.21 镜像还原**每个方块外观**所需的全部数据。它读取的是 Minecraft 烘焙之后的模型，所以 OBJ、Forge v1 blockstate、multipart、自定义 IBakedModel，以及 NebulaeCraft 的自发光包装模型，都用同一种方式处理，不需要针对某个 mod 做适配。

## 在哪里运行

客户端必须是一个**真实可玩的 1.12.2 Forge 客户端**，并加载：

- 与原服**版本相同**的所有内容 mod（来自 `data/mods.zip`）；
- 用源码构建的 **2.26** 版 NebulaeCraft jar，而不是服务器上现在的 2.25；
- 客户端渲染依赖，例如 **CTM**（Chisel 在客户端需要它）；
- `MirrorDumper-1.12.2-<版本>.jar`（构建输出在 `legacy/dumper/build/libs/`）。

不要放服务端专用的插件和 mod：SpongeForge、Nucleus、LuckPerms、Prism、ServerListPlus、Dynmap 等。

## 如何运行

两种方式都可以：

1. **自动模式**：在启动器里加上下面的 JVM 参数。主菜单出现后会执行一次 dump，然后游戏退出：
   `-Dmirrordump.auto=true -Dmirrordump.exit=true`
   可选：`-Dmirrordump.mods=nebulaecraft,chisel`（只 dump 这些命名空间）、`-Dmirrordump.maxStates=4096`。
2. **游戏内命令**：在任意单人世界里执行
   `/mirrordump [mods=a,b] [vanillaModels] [noVanillaTextures] [maxStates=N]`

运行期间游戏会卡住几秒到几分钟。输出在 `<游戏目录>/mirrordump/<时间戳>/`；把它拷到本仓库的 `dumps/`。

## 为 Railcraft 套件轨道重新 dump（MirrorDumper 1.2，原 0.2.1）

只有当 dump 里包含 `extras/railcraft_tracks.json` 时，镜像才会根据 TileEntity 绘制套件轨道（道岔、Y 形岔、止冲挡、加速器等）。用 0.1.0 做的 dump（例如 `dumps/full-20261107-145222`）没有这个文件；在正式客户端上用 0.2.0 做的 dump 也没有：0.2.0 按名字查找 `TrackType.getName()`，但在 Railcraft 的正式 jar 里这个方法被重新混淆成了 `func_176610_l`，所以导出失败，`errors.json` 里是 `NoSuchMethodException`。（开发客户端会把它映射回 `getName`，掩盖了这个 bug。）这些情况下生成器都会警告 `dump has no extras/railcraft_tracks.json`，轨道保持默认套件的模型。要补上它，在装有完整 1.12 客户端的机器上操作（`dumps/full` 是指向当前完整 dump 的软链接，即用 0.2.1 做的 `full-20261107-200107`；有了新 dump 之后要重新指向）：

1. 在这里构建 dumper，把客户端 `mods/` 里的 `MirrorDumper-1.12.2-0.1.0.jar` 换成 `legacy/dumper/build/libs/MirrorDumper-1.12.2-1.3.jar`（删掉所有 0.2.0 或 0.2.1 的 jar）。
2. 像平常一样做完整 dump（自动模式或 `/mirrordump`）。Railcraft 必须完整 dump：如果传了 `mods=`，要包含 `railcraft`。执行 `/mirrordump` 时不要开着轨道护目镜的光环：烘焙还原的是玩家平时看到的样子，而 Railcraft（本服的构建）在电气/高速/强化轨道上会隐藏套件（止冲挡除外），除非开着那个光环。
3. 检查日志里的 `Dump finished: …` 一行，以及 `manifest.json` → `summary.trackKitVariants`。它不能是 0；Railcraft 12.1.1 的模组包是 4214（每种轨道类型 × 套件 × 形状 × 状态，共 1046 个不同的模型）。与 Railcraft 有关的问题记在 `errors.json` 里，位于 `railcraft:track_outfitted` / stage `trackKits` 之下。
4. 把 dump 拷到 `dumps/`，重新运行生成器（`report.md` 里有 `tracks.variants` / `tracks.meshes` 的计数）并重新构建 mod。不需要重新导入：客户端读取的是导入时已经存下的 TileEntity 数据。

这个额外的文件只是增加了模型；dump 里的其他内容都不变，所以用 1.2（或 0.2.1）完整重做的 dump 可以一对一地替换之前的 dump。已在模组包客户端上用 `20261107-200107` 验证：`trackKitVariants` 为 4214，没有轨道相关的错误。

## 输出格式（schema 1）

| 文件 | 内容 |
|---|---|
| `manifest.json` | MC/Forge 版本、带版本号的 mod 列表、选项、摘要统计 |
| `blocks/index.json` | 所有方块：文件路径、状态数、标志 |
| `blocks/<ns>/<name>.json` | 属性、meta 表（`roundTrip`/`invalid`）、`storedProperties`/`derivedProperties`、物品子类型（带 `item` id），以及每个状态的：亮度、不透明度、渲染层、包围盒/碰撞、是否有 TE、`drop`（damageDropped）/ `pick`（中键选取的 damage）、模型引用和染色颜色 |
| `models/<h0h1>/<sha1>.json` | 某个（状态, 渲染层）烘焙后的 quad，按内容去重。顶点是方块空间坐标加上相对于 sprite 的 0..16 UV；字段包括 `cull`、`tint`、`shade`、`emissive` |
| `textures/<ns>/…png(.mcmeta)` | 原始分辨率的原贴图（会跟随 CTM mcmeta 里的引用）。运行时生成的 sprite（例如 Railcraft 的 `*_0..N`）从图集里读回 |
| `sprites.json` | 每个 sprite 的尺寸、帧数和来源（`copied`/`atlas`/`missing`/`vanilla-skipped`） |
| `lang/<ns>/{en_us,zh_cn}.lang` | 每个命名空间的语言文件 |
| `biomes.json` | 生物群系的温度、降雨量，以及草/树叶/水的颜色 |
| `extras/railcraft_tracks.json` | （1.2+，原 0.2.1+）Railcraft 套件轨道：为每种轨道类型 × 套件 × 形状 × 套件渲染状态烘焙出的组合模型，键为 `<rail>\|<kit>\|<shape>\|<state>`，使用 TileEntity 自己的 NBT 名称，每项带有与状态的 `model` 相同的 `ao`/`particle`/`layers` 引用。只有加载了 Railcraft 并完整 dump 时才会写出 |
| `errors.json` | 所有异常，按方块、状态和阶段记录 |

默认情况下，`minecraft:*` 方块只导出属性和 meta 表，不导出 quad。镜像使用 1.21 自己的原版资源，原版方块由 DFU 转换。mod 模型引用到的原版贴图默认会被复制（`noVanillaTextures` 可关闭）。生成器优先使用改名后的 1.21 贴图，只有在 1.21 没有对应贴图时才回退到这些 1.12 的 PNG。

## 已验证的内容（NebulaeCraft 开发环境，NebulaeCraft 2.26 + Railcraft）

- 851 个方块 / 18,341 个状态 / 6,053 个模型，0 个错误，4.6 秒。
- meta 编码与源码一致：水平朝向 `meta=index-2`；tdt 为 `range<<2|horizontalIndex`。
- 玻璃板的连接属性被归类为 `derivedProperties`。
- OBJ 的灭火器箱烘焙出 56 个 quad。
- 灯具的发光面带有 `emissive` 标记。
- Railcraft 运行时切分的 sprite 从图集读回后，与原图集里的瓦片完全一致。

## 已知缺口（由其他组件补上）

- `roadmark_text` 没有 quad；它完全由 TESR 绘制，将在 P1 里移植。
- Chisels&Bits / ArchitectureCraft / Railcraft 储罐的外观取决于 TE 数据；dump 里只有它们的基础模型。Railcraft 套件轨道是例外：1.2（0.2.1）会把它们由 TE 决定的变体烘焙进 `extras/railcraft_tracks.json`。
- 对 CTM 方块只导出基础（未连接）的 quad；连接规则保留在复制过来的 mcmeta 里。
- 世界里仍有 1106 个 `nebulaecraft:device_apple_iphone` 和 1 个 `nebulaecraft:countdown`。两者很久以前就从 NebulaeCraft 里移除了（2.25 和 2.26 都没有），而原服的 FML 注册表仍然保留着它们的 ID。镜像把它们当作 unknown 方块，原样传递。
