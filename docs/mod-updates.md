# 原服 mod 变化时的更新

1.12.2 原服更新了 **FutureMC、NebulaeCraft、Railcraft、Quark、Chisel 等**，或者增删了某个 mod，导致方块新增、移除或改变时该做什么。镜像只需要还原原服的**方块外观**，所以下面的一切都是为了让三样东西和原服的 mod 列表保持一致：dump、生成的定义（以及由它构建的 jar）、世界。

## 使用辅助程序

`ops/update/update.py`（只用 Python 标准库）负责从「原服的新 `data/mods.zip` 已经放好」到「新 jar 已经构建好」之间的所有步骤，只有 dump 本身除外，它仍然在装有 1.12.2 客户端的那台机器上进行。辅助程序从不部署任何东西，也不会碰镜像服务器或原服。

**dump 之前：**

```bash
python3 -I ops/update/update.py prepare
```

它把 `data/mods.zip` 和上一次 dump（`dumps/full/manifest.json`，按 jar 文件名）做对比，列出 dump 客户端要改的地方：要替换的 jar、原服新增的 jar、已经没有的 jar，以及 NebulaeCraft（源码构建）和 MirrorDumper 是否需要替换。要拷过去的 jar 连同清单（`CHANGES.txt`）一起放在 `work/update/client-mods/`。第一次使用时，它分不清新 mod 和服务端专用 mod，会列出上次 dump 没有加载的所有 jar；完成过一次 `run` 之后，它会记住 zip 里的 jar 列表（`work/update/mods-baseline.json`），之后只列出真正新增的。

**dump 之后**（dump 客户端产生的 zip，或一个目录）：

```bash
python3 -I ops/update/update.py run ~/Downloads/20261120-101500.zip
```

| # | 步骤 | 何时停下 |
|---|---|---|
| 1 | 把 dump 导入到 `dumps/full-<时间戳>` | zip 不是 dump，或含有不安全的路径 |
| 2 | 检查 dump：是完整 dump、没有被截断、Railcraft 的 `trackKitVariants` ≠ 0、客户端的 jar 与 `data/mods.zip` 一致、NebulaeCraft 版本与源码的 `VERSION` 一致；列出上一次 dump 没有的 `errors.json` 条目。然后把 `dumps/full` 指向新 dump | 以上任何一项不通过（加 `--accept-dump` 可继续） |
| 3 | 与上一次 dump 对比（`diff_dumps.py`）：mod 版本、新增、移除、`CHANGED`，以及只是外观变了的方块 | |
| 4 | 统计新增 / 移除 / `CHANGED` 的方块在原世界里的数量（`work/world-stats.json`，或 `--stats <文件>`，或用 `--world <世界副本>` 重新扫描），列出所在区块需要 `refresh` 的方块；检查 dump 覆盖率 | |
| 5 | 列出指向已不存在方块的 override。对每个新方块，如果它与某个 1.21 原版方块同名，或者它的 mod 已有 override 文件（FutureMC、Quark），就把规则草稿写到 `work/update/<dump>/override-suggestions.json` | 这类方块还没有 override：把规则加进 `mapping/overrides/`，或加 `--accept-generated` 把它们当作普通 mod 方块发布 |
| 6 | 运行生成器，只显示相对于之前 `report.json` 的差异：新的警告、变化的计数、新的 `reverseAmbiguous` 条目、带 TileEntity 的新方块 | 生成器报告了错误 |
| 7 | 对版本有变化的 mod，提示对应的渲染器检查项（下文第 4 步的表格） | |
| 8 | 生成器测试、`mod_version` + 1、`./gradlew build`、`./gradlew runSelftestClient` | 测试或构建失败，或自检发现没有模型的状态或物品 |
| 9 | 在 README 的「版本」段落里记下新版本 | |
| 10 | 写出 `work/update/<dump>/report.md`（需要关注的点排在最前），并把 jar 拷到同一目录（`--copy-to <目录>` 还会再拷一份到别处，例如 Windows 镜像机器能读到的共享目录） | |

停下时它会说明原因（退出码 2）。修好之后再执行**同一条命令**：dump 不会被导入两次，每个 dump 只升一次版本号（`work/update/state.json`），对比的对象也仍然是更新之前的那个 dump。`--no-build` 在第 7 步之后结束，方便先看报告；`--no-bump` 和 `--skip-selftest` 跳过对应的部分；当 `dumps/full` 不是上一次的 dump 时，用 `--old <dump 目录>` 指定它。

仍然需要手动做的事会列在报告末尾：把 jar 交给镜像服务器和所有客户端、重启、对受影响的区域执行 `refresh`、游戏内核对（下文第 5–7 步）、修复渲染器、提交。本页余下的内容完整地描述每一步，既用于手动操作，也用于理解辅助程序的报告。

测试：`python3 -I -m unittest discover -s ops/update/tests`。

## 简要流程

1. 用**新版本**的 mod 搭一个 1.12.2 客户端，做一次完整 dump（[dumper.md](dumper.md)）。
2. 与上一次 dump 对比（`ops/scan/diff_dumps.py`）。
3. 重新运行生成器，处理 `report.md` 里指出的问题（FutureMC/Quark 的 override、新的 TE 驱动方块的渲染器）。
4. 构建镜像 jar（小版本号加一）、测试，并把新 jar 交给服务器和**所有客户端**。
5. 对转换结果有变化的那部分世界重新同步（`refresh` 或重新导入）。同步**不会**自动做这件事。

请按这个顺序做，并且尽量在原服切换到新 mod **之前**完成第 1–4 步。这样就不会出现原服有方块而镜像不认识的空档期。

## 各类变化的处理

| 原服的变化 | 镜像要做的 | 世界要做的 |
|---|---|---|
| 新方块，还没有人放置 | dump、生成、构建、部署。它会自动出现在所属 mod 的创造模式标签页里 | 无 |
| 新方块，原服已经放置了 | 同上 | 在所在区块重新转换之前，已放置的方块是 `nebulaemirror:unknown` 占位方块：执行 `refresh`（见下文） |
| 新方块，且有对应的 1.21 原版方块（FutureMC、部分 Quark） | **首次部署之前**写好 override（见下文） | 事先写好则无；否则 `refresh` |
| 方块被移除，原世界里一个也不剩 | dump、生成、构建、部署 | 无 |
| 方块被移除，但原服的 region 文件里还有残留 | 同上 | 部署后 `refresh`：它们会变成 unknown 占位方块，而不是消失 |
| 方块的属性、meta 含义或物品子类型变了 | 同上；检查 dump 对比里的 `CHANGED` | `refresh` 或重新导入：内容没变的区块**不会**被重新转换 |
| 只有贴图/模型变了 | dump、生成、构建、部署 | 无（客户端换上新 jar 即可） |
| 新增 mod | 与新方块相同。检查它的方块是否需要 TileEntity 渲染器 | 与新方块相同 |
| 移除 mod | 它的方块从 dump 里消失；删除 `mapping/overrides/` 里它的条目 | 与移除方块相同 |

## 分步说明

### 1. 用新版本做 dump

按 [dumper.md](dumper.md) 操作：一个真实可玩的 1.12.2 Forge 客户端，mod 版本与更新后的原服**相同**。NebulaeCraft 使用你要镜像的那个版本的**源码构建**（目前是 2.26），不一定是服务器上的 jar。不要放服务端专用的 mod。

- 做完整 dump（不带 `mods=` 过滤）。Railcraft 必须完整 dump，否则套件轨道的模型会缺失。
- 检查 `manifest.json` → `summary` 和 `errors.json`。对 Railcraft，`summary.trackKitVariants` 不能是 0（Railcraft 12.1.1 是 4214）。mod 更新后出现新错误，通常说明 dumper 的反射对不上了（0.2.0 → 0.2.1 的 `getName` / `func_176610_l` 问题就是这样）；修复 `legacy/dumper/` 并升它的版本号。
- 把 dump 拷到 `dumps/`，并把 `dumps/full` 软链接指向它。

### 2. 看看变了什么

```bash
python3 -I ops/scan/diff_dumps.py dumps/<旧 dump> dumps/<新 dump>
```

它会打印版本有变化的 mod、`added` 和 `removed` 的方块，以及属性名/取值或 meta → 状态表不同的 `CHANGED` 方块。`CHANGED` 是最危险的：同一个存储的（name, meta）现在代表别的东西，所以已转换的区块在重新转换之前都是错的。`look` 行是映射不变、烘焙模型不同的方块：它们只需要新 jar。原版方块会被跳过（由 DataFixer 处理）。

另外重新扫描一份最新的原世界副本，并检查覆盖率（[README](../README.md#常用命令)）：

```bash
python3 -I ops/scan/scan_world.py <原世界副本> --out work/world-stats.json --workers 8
python3 -I ops/scan/scan_world.py --stats work/world-stats.json --dump dumps/<新 dump>
```

`blocksMissingFromDump` 里应该只有确实已经不存在的方块（目前是 2 个旧的 NebulaeCraft 方块）。出现别的就说明 dump 客户端少了某个 mod 或版本不对。统计文件还会告诉你原服里某个 `ns:name:meta` 还有多少个方块（`states`），这决定了下文「移除方块」的处理方式。它只有计数，没有坐标。

### 3. 生成并阅读报告

```bash
python3 -I mirror/generator/generate.py --dump dumps/full
```

错误必须为 0（自检还会证明每个 meta 正向再反向之后回到自身）。在 `work/gen/report.md` 里关注：

- `override for a block that is not in the dump`：方块被移除或改名了；删除或改名 `mapping/overrides/` 里的条目。
- 按类型分组的警告里与**新**方块有关的：缺失贴图、没有 quad 的方块、`reverseAmbiguous`。
- 贴图列表：在 1.12 里就缺失的贴图是原 mod 的 bug，不是镜像的问题。

有原版对应物的方块（FutureMC、Quark）：

- FutureMC 向下移植了 1.13–1.16 的方块。一个新的 FutureMC 方块会被当作普通 mod 方块生成，除非有 override 把它映射到 1.21 的方块上。把规则加到 `mapping/overrides/futuremc.json`（格式见 [generator.md](generator.md#手写映射-mappingoverridesjson)）：`target`，属性改名写在 `props`，取值改名写在 `values`，个别 meta 的例外写在 `metas`。生成器会对照 1.21.1 方块报告检查每个目标属性和取值，所以写错的规则会报错，而不是悄悄造成破坏。
- **要在首次部署之前决定。** 如果 `futuremc:new_block` 被生成并发布了，镜像世界里就会有它；之后再改规则会把它变成另一个方块，需要 `refresh`。
- 多个 1.12 方块映射到同一个原版方块时，检查报告里的 `reverseAmbiguous`：它决定建筑者放的原版方块写回成哪一个 1.12 方块。
- Quark 同理，写在 `quark.json`。Quark 独有的方块直接生成即可。
- 必须保持为占位方块的方块（已经不存在或坏了，但还在 dump 里）：`{"unknown": true}`。`device_apple_iphone` 就是这样处理的。已经不在 dump 里的方块什么都不用做：它的（name, meta）没有映射，导入器会自动把它变成 unknown 占位方块。

### 4. 检查依赖 mod 内部数据的渲染器

镜像里有一些代码依赖某个 mod 的内部数据。这些 mod 更新版本后，要检查一下：

| Mod | 镜像依赖的东西 | 检查 |
|---|---|---|
| Railcraft | 套件轨道：TE id `railcraft:track.outfitted` / `.ticking`，NBT 名 `rail`、`kit` 以及每种套件的状态字段，[legacy-renderers.md](legacy-renderers.md#railcraft-套件轨道) 里的轨道套件表 | `trackKitVariants`、`errors.json`、`report.md` 的 `tracks.variants`；新套件或 NBT 字段改名需要修改 `OutfittedTrackKey`/`OutfittedTrack` |
| Chisels & Bits | 紧凑体素格式（`ChiselsBlob`） | 新的 blob 格式版本读不了；`ChiselsBlobTest` 里有一个真实的 blob |
| ArchitectureCraft | 形状表和 jar 里附带的 108 个 `.objson` 模型，屋顶和窗户的逻辑 | 新形状或新模型需要移植；未知形状无法正确绘制 |
| Chisel | CTM `.mcmeta` 规则 → Continuity | `report.md` 里的 `ctm.*` 计数；新的 CTM 类型不会被转换 |
| MoarSigns | 告示牌贴图（来自 `--mods`，即原服的 mods zip）和 TE 字段 | 把新的 mods zip 传给生成器 |
| NebulaeCraft | `roadmark_text` 渲染器和字体（`--nebulaecraft`，2.26 源码的资源目录） | 把 `--nebulaecraft` 指向新的源码 |
| Quark | 花盆内容是静态的 | 无 |

任何 mod 的新 TileEntity 驱动方块，在有渲染器之前只显示它的静态模型（[legacy-renderers.md](legacy-renderers.md)）。无论如何，数据都原样保存在旧版方块实体里，所以不会丢失任何东西，写回也照常工作。

### 5. 构建、测试、部署

```bash
python3 -I -m unittest discover -s mirror/generator/tests
cd mirror/mod && ./gradlew build && ./gradlew runSelftestClient
```

自检会列出没有 mesh 模型的方块状态和物品（`nebulaemirror-selftest.json`）。把 `mirror/mod/gradle.properties` 里的 `mod_version` 加一（每次发布小版本号加一，见 README），并在 README 的「版本」一节里记下来。

生成的注册表打包在 jar 里，所以**新 jar 要同时交给镜像服务器和所有客户端**；用旧 jar 的玩家进不了服务器（方块注册表不同）。玩家照旧需要 Continuity。重启镜像服务器；方块的变化不会热加载。只有 bridge/dumper 自己的代码变了才需要重新构建它们。

### 6. 更新世界

同步比较的是原服区块的**内容**，所以映射变化之后，它只会重新转换在原服发生了变化的区块。没有变化的区块保持镜像里现有的样子。有三种手段：

| 手段 | 适用于 | 代价和副作用 |
|---|---|---|
| 什么都不做 | 还没有被放置的新方块；原世界里已经一个不剩的被移除方块 | |
| `/nebulaemirror sync refresh <cx1> <cz1> <cx2> <cz2>` | 用新映射把已知的区块范围从原服重新转换一遍 | 约 275 区块/秒（9,216 个区块用了 33 秒）；整个世界大约需要 90 分钟。**会撤销**该范围内镜像上的编辑，签出的区域除外。需要先做一次新的拉取（`sync now`），让 `source` 是最新的 |
| 全量重新导入（[importer.md](importer.md)） | 影响很多区域的映射变化 | 整个世界离线约 31 分钟。会替换整个镜像世界，**包括尚未提交的编辑和区域**：先把所有区域提交或放弃。它会写入新的同步基线 |

如何选择区块范围：没有工具能列出某个方块在哪里。根据你知道的情况（建筑者放置新方块的区域、变化的方块用在哪里）来定，或者 refresh 整个有人活动的范围。在原世界副本里统计某个方块的数量（统计文件里的 `states`）可以告诉你它是否存在。

**被移除的方块。** jar 里没有的方块也就不在注册表里了，所以已保存的镜像区块如果还引用它，那个位置很可能会被加载成空气，就像移除任何 mod 一样（这里没有测试过）。如果最新的扫描在原世界里一个也没找到，那么镜像区块里也不会有它（仍处于签出状态的区域和未同步的编辑除外），可以直接部署。如果原服的 region 文件里还有残留（iPhone 方块就是这样留下来的），重启后立刻对它们所在的区域执行 `refresh`，让它们变成 unknown 占位方块，占位方块会保留原始数据用于写回。

涉及移除方块或映射变化的更新顺序：`sync now` → 部署并重启 → `refresh` 受影响的区域 → `/nebulaemirror sync status`（`last-run.json` 里的 unknown 方块计数和 `nebulaemirror-import.json` 的 unknown 列表里应该只有你预期的方块）。

`resolve-unknown`（自动重新转换旧的占位方块）已在计划中，但**尚未实现**；在那之前用 `refresh`。

### 7. 核对

- 对同一区域，把 `/nebulaemirror stats <区块范围>` 与 `ops/scan` 的结果对比：每个 mod 命名空间都应该一致，并且只有预期的方块是 unknown。
- 从创造模式标签页放置新方块，看看外观（自检生成的图标图在 `run/selftest/nebulaemirror-icons/`，可与 1.12 游戏对比），并用一个小区域写回一个（`zone lint`、`zone submit`）来验证反向映射：提交时的往返校验会报告任何差异。
- 对被移除的方块：占位方块破坏后再放回去，仍然是原来的 name/meta；同步摘要里没有意外的错误。

## mod 更新同时改变世界格式时

如果原服的更新会迁移世界里的数据（meta 重新编号、TileEntity 转换），这是在原服发生的；镜像把它看作普通的内容变化，并重新转换那些被保存过的区块。先完成 dump 和生成器的步骤，这样第一批变化的区块到来时，映射已经是新的了。

## 检查清单

- [ ] 已知道原服的 mod 版本，新的 `data/mods.zip` 已就位；dump 客户端用的是相同的版本（NebulaeCraft 用源码构建）
- [ ] 完整 dump，已检查 `errors.json`，Railcraft `trackKitVariants` ≠ 0，`dumps/full` 已指向新 dump
- [ ] 已阅读 `diff_dumps.py` 的结果：added / removed / `CHANGED`
- [ ] 重新扫描了原世界：覆盖率、被移除方块的数量
- [ ] override 已更新（新的 FutureMC/Quark 方块，删除过期条目）
- [ ] 生成器：0 个错误，已阅读 `report.md`
- [ ] 已检查 Railcraft / C&B / ArchitectureCraft / Chisel / MoarSigns / NebulaeCraft 的渲染器
- [ ] 测试和自检通过，版本号已升，README 的「版本」已更新
- [ ] jar 已交给服务器和所有客户端，服务器已重启
- [ ] 对变化的区域执行了 `refresh`（或重新导入）；重新导入之前已提交或放弃所有区域
- [ ] 已核对统计数据，并做了一次写回往返
