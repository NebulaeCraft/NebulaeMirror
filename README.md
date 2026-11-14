# NebulaeMirror

为 1.12.2 Forge 建筑服务器（原服）搭建的 1.21.1 Fabric 镜像服务器，让玩家可以使用 WorldEdit、Axiom 等现代编辑工具。镜像只需要还原原服的**方块外观**。原服 → 镜像是定期增量同步；镜像 → 原服按签出的区域写回。

完整设计见已批准的技术方案（整体架构、同步模型、mod 更新方案）。各阶段进度：

- [x] 覆盖率扫描器 `ops/scan/`（1.12 世界 →（name, meta）/ TE / 实体统计，以及 dump 覆盖率检查）
- [x] 1.12 dumper `legacy/dumper/`（导出方块属性、meta 表、烘焙后的 quad、形状、贴图、语言文件）
- [x] 生成器 `mirror/generator/`（dump → 1.21 方块定义、模型、正向/反向映射），见 [docs/generator.md](docs/generator.md)
- [x] 镜像 mod `mirror/mod/`（Fabric 1.21.1）：方块注册、mesh 模型、物品/创造模式标签页、旧版 TE 存储、unknown 占位方块，见 [docs/mirror-mod.md](docs/mirror-mod.md)
- [x] FutureMC/Quark → 1.21 原版的 override（`mapping/overrides/`）
- [x] 导入器：离线全量导入（DFU + mod 方块替换），见 [docs/importer.md](docs/importer.md)
- [x] P1 由 TileEntity 决定的外观：Chisels&Bits 体素、ArchitectureCraft 形状/屋顶/窗户、NebulaeCraft roadmark_text、MoarSigns、Chisel CTM → Continuity，见 [docs/legacy-renderers.md](docs/legacy-renderers.md)（客户端需要 Continuity）；Railcraft 套件轨道（需要 MirrorDumper 1.2 / 0.2.1 的 dump，见 [docs/dumper.md](docs/dumper.md)）
- [x] P2 在线增量同步：拉取（RCON `save-off`/`save-all flush` + rsync + `save-on`）、时间戳 + 内容哈希的变更检测、未加载区块走 IO 替换 / 已加载区块走方块差异、mod 内定时器 + launchd 保活，见 [docs/sync.md](docs/sync.md)（待办：对真实原服的首次测试）
- [x] P3 写回，见 [docs/writeback.md](docs/writeback.md)：
  - [x] 第 1 步：反向映射（原版走 DataFixer，mod 走 reverse.json）、TileEntity/物品降级、区域（`/nebulaemirror zone checkout|lint|diff|abandon`）、patch 文件
  - [x] 第 2 步：bridge mod `legacy/bridge/`（静默应用 patch、undo 文件、回滚、冲突检查），见 [docs/bridge.md](docs/bridge.md)
  - [x] 第 3 步：原服上的区域锁（Forge 事件 + FAWE mask）、带 token 的本地 HTTP API、带往返校验的 `zone submit`、LuckPerms 权限节点
- [x] 聊天同步：聊天、加入和离开经 bridge 双向同步，格式和开关在镜像的同步配置里，见 [docs/chat.md](docs/chat.md)
- [x] WorldEdit 短命令（`//s`、`//r`、`//c` 等，可配置），见 [docs/worldedit-aliases.md](docs/worldedit-aliases.md)
- [x] 所有 `/nebulaemirror` 命令的短写 `/nm`（`/nm zone checkout`、`/nm sync status` 等），见 [docs/mirror-mod.md](docs/mirror-mod.md)
- [x] 创造模式标签页的客户端配置（按 mod 隐藏、选择图标），见 [docs/creative-tabs.md](docs/creative-tabs.md)
- [x] 原服 mod 变化（方块新增、移除或改变）时的更新指南，见 [docs/mod-updates.md](docs/mod-updates.md)
- [x] 更新辅助程序 `ops/update/update.py`：列出 dump 客户端要更换的 jar，然后导入并检查新 dump、做对比、起草 override、生成、测试、升版本号并构建 jar，见 [docs/mod-updates.md](docs/mod-updates.md#使用辅助程序)

## 版本

三个 mod 都使用两段式版本号 `1.x`，每次发布把小版本号加一。2026-11-08 重新编号时保留了各 mod 原来的小版本号：镜像 mod 1.2（原 0.1.0，加上 WorldEdit 短命令）、bridge 1.3（代码同 0.3.0）、dumper 1.2（代码同 0.2.1）。此后：镜像 mod 1.3（创造模式标签页配置）；镜像 mod 1.4、bridge 1.4、dumper 1.3（游戏内消息全部改为中文）；镜像 mod 1.5（WorldEdit `//pos2` 可选在签出区域之外）；镜像 mod 1.6（`/nebulaemirror` 的短写 `/nm`）；镜像 mod 1.7（签出区域的边界高亮：自己的为青色，他人的为黄色）。版本号在 `mirror/mod/gradle.properties`（`mod_version`）中设置；两个 Forge mod 的版本号在 `build.gradle` 中设置，并要同时修改 `MirrorBridge` / `MirrorDumper` 里的 `VERSION` 常量。

## 目录结构

| 目录 | 内容 |
|---|---|
| `data/` | 原服快照：世界 zip、配置、mods（不入 git） |
| `legacy/dumper/` | Forge 1.12.2 客户端 mod：导出方块外观数据 |
| `legacy/bridge/` | Forge 1.12.2 服务端 mod：应用写回 patch（undo、回滚）、区域锁、聊天转发 |
| `mirror/mod/` | Fabric 1.21.1 mod（Gradle 9.8 + Loom，Mojang 映射） |
| `mirror/generator/` | dump → 镜像定义的生成器（Python，仅标准库） |
| `mapping/overrides/` | 手写映射（格式见 docs/generator.md） |
| `mapping/snapshots/` | 注册表/映射快照，用于对比新方块 |
| `ops/scan/` | 世界扫描、覆盖率检查和 dump 对比（`diff_dumps.py`） |
| `ops/sync/` | 原服拉取脚本（RCON + rsync）及其测试 |
| `ops/update/` | 原服 mod 变化后更新镜像的辅助程序（`update.py`）及其测试 |
| `ops/server/` `ops/launchd/` | 镜像服务器配置、启动脚本和 launchd 保活 |
| `ops/mc/` | 下载 1.21.1 官方 jar 并生成参考数据 |
| `dumps/` `work/` | 本地 dump 和中间文件（不入 git） |
| `docs/` | 使用文档 |

## 常用命令

扫描世界（只用 Python 标准库；8 核扫完整个世界约 5 分钟）：

```bash
python3 -I ops/scan/scan_world.py data/2026.11.06.zip --out work/world-stats.json --workers 8
```

检查某个 dump 是否覆盖了世界里实际用到的所有方块：

```bash
python3 -I ops/scan/scan_world.py --stats work/world-stats.json --dump dumps/<dump 目录>
```

构建 dumper（需要 JDK 8）：

```bash
cd legacy/dumper && JAVA_HOME=$(/usr/libexec/java_home -v 1.8) ./gradlew build
```

dumper 的运行方法见 [docs/dumper.md](docs/dumper.md)。

生成镜像定义（先下载一次 1.21.1 参考数据）：

```bash
JAVA=$(/usr/libexec/java_home -v 21+)/bin/java ops/mc/fetch_121.sh
```

```bash
python3 -I mirror/generator/generate.py --dump dumps/<dump 目录>
```

```bash
python3 -I -m unittest discover -s mirror/generator/tests
```

原服 mod 变化之后（见 [docs/mod-updates.md](docs/mod-updates.md#使用辅助程序)）：先看 dump 客户端要改什么，dump 之后再跑完其余步骤：

```bash
python3 -I ops/update/update.py prepare
```

```bash
python3 -I ops/update/update.py run <dump zip>
```

同步测试（拉取脚本）：

```bash
python3 -I -m unittest discover -s ops/sync/tests
```

更新辅助程序的测试：

```bash
python3 -I -m unittest discover -s ops/update/tests
```
