# 导入器（1.12.2 世界 → 1.21.1 镜像世界）

它是 `mirror/mod` 的一部分：镜像服务器带上相应的 JVM 参数启动时，会在加载任何世界之前，把 1.12 主世界的 region 文件转换到服务器的世界目录里，然后服务器照常启动。这就是方案里的「离线全量导入」；在线增量同步（[sync.md](sync.md)）使用同一个区块转换器。

## 用法

1. 在镜像服务器的 `server.properties` 里准备一个虚空超平坦世界（示例见 [ops/server/server.properties](../ops/server/server.properties)）：

   ```properties
   level-type=minecraft\:flat
   generator-settings={"layers"\:[],"biome"\:"minecraft\:the_void","features"\:false,"lakes"\:false,"structure_overrides"\:[]}
   generate-structures=false
   ```

2. 准备一份 1.12 世界的副本（包含 `level.dat` 和 `region/` 的目录；用 rsync 就够了）。
3. 带上导入参数启动服务器：

   ```bash
   java -Dnebulaemirror.import=/path/to/world12 -jar fabric-server-launch.jar nogui
   ```

   可选参数：
   - `-Dnebulaemirror.import.regions=x1,z1,x2,z2`：只导入这个范围的 region（用于测试）；
   - `-Dnebulaemirror.import.threads=8`；
   - `-Dnebulaemirror.import.overwrite=true`：允许覆盖已有的 `region/`。默认拒绝覆盖。

4. 导入还会写入增量同步的基线（`world/nebulaemirror/sync/`，见 [sync.md](sync.md)）。所以要从同步配置里 `source` 指向的那份本地副本导入。
5. 结果写到 `world/nebulaemirror-import.json`：区块/方块计数、unknown 方块（按 name:meta）、错误，以及 1.12 的出生点。首次启动时出生点会自动应用到镜像世界。

开发时，`cd mirror/mod && ./gradlew runImportServer -PimportSource=/abs/path/world12 [-PimportRegions=...]` 使用 `run/import/`。

## 转换流程（`ChunkConverter`）

1. **取出 mod 方块**：通过源世界 `level.dat` 里的 FML 注册表解码 1.12 的 ID 和 meta；ID 从不硬编码。每个非 `minecraft` 的方块在数组里被替换成空气，并连同它的 TileEntity 一起记录在一张旁表里。
2. **原版升级**：剩下的纯原版区块交给 Minecraft 自己的 DataFixer（1343 → 3955）。它使用服务器对「超平坦主世界」会用的同一个上下文，所以不会安排 0 层以下的重新生成或地形混合。告示牌、旗帜、床、花盆、双层植物等都由官方逻辑处理。
3. **写回 mod 方块**：目标状态来自 `forward.json`，写入 1.21 的 section 调色板：
   - 在 1.12 里带 TE 的生成方块，以及未映射的方块（`nebulaemirror:unknown`），会得到一个 `nebulaemirror:legacy` 方块实体，它的 `Legacy` 标签 = `{Name, Meta, TileEntity?}`，原样保留用于写回。对 C&B/ArchitectureCraft 还会额外添加一个推导出的 `Render` 标签（见 [legacy-renderers.md](legacy-renderers.md)）。
   - 目标状态取决于邻居的方块（楼梯/墙/栅栏/玻璃板/栅栏门/门，包括映射到原版的 FutureMC/Quark）会加入 `UpgradeData` 队列，于是 1.21 在首次加载时重新计算它们的连接和形状。
4. **收尾**：移除 `below_zero_retrogen`/`blending_data`/`Heightmaps`，设置 `Status=full` 和 `isLightOn=false`。服务器在加载时会重新计算区块光照并重建高度图。

另外还处理：
- **生物群系**：mod 生物群系映射到气候相同的原版生物群系（生成器的 `biomes.json`；`sakura:bamboo_forest` → jungle，`maple_forest` → forest），然后由 DFU 升级。
- **计划刻**：属于 mod 方块的被丢弃。
- **大区块**：超过 1 MB 的区块写成 `.mcc` 外部文件。

## 验证（出生点周围 9 个 region，x 2..4 / z −3..−1）

- 9216 个区块，**0 个失败，18 秒**（10 线程）；输出 108 MB（输入 94 MB）。全世界估算：约 150 万个区块，约 50 分钟，约 12 GB。
- 把 `/nebulaemirror stats <区块范围>` 与同一区域的 `ops/scan` 结果做了对比：
  - 每个 mod 命名空间都**完全一致**（NebulaeCraft 837,595 = 837,569 + 26 个 unknown 的 iPhone；Chisel 8,178,652；C&B 280,686；Railcraft 2,289,266，等等）。
  - 683,864 个 FutureMC/Quark 方块全部落在了正确的原版方块上。
  - 非空气方块总数 166,542,391 → 166,542,388。少掉的 3 个原版方块是 1.21 在原版升级自身的形状更新中移除的（悬空的火把之类）；普通的 1.21 升级也会这样。写回是与镜像自己的签出快照做差异，所以这 3 个方块不会从原服删除。
  - 302,346 个旧版方块实体（C&B、ArchitectureCraft、Railcraft 储罐、NebulaeCraft 的 tdt/roadmark_text 等）。
- 截图巡览（`./gradlew runTourClient -PtourWorld=world -PtourFile=/abs/tour.txt`，先把世界拷到 `run/tour/saves/`）：道路/停车位标线、高架桥和接触网、斜拉桥、车站、樱花树和建筑都渲染正确。

## 全世界导入（2026-11-07，使用 `2026.11.06` 备份）

- 1553 个 region / **1,497,442 个区块，0 个失败，31 分钟**（10 核，`-Xmx8G`）；输出 7.9 GB。
- 与 `ops/scan` 的全世界扫描完全吻合：
  - 1,279,648,994 个 mod 方块；
  - 1,107 个 unknown 占位方块（`device_apple_iphone` ×1106、`countdown` ×1）。
- 报告里还记录了：
  - 3,723,724 个旧版方块实体；
  - 1,603,803 个形状更新位置；
  - 丢弃了 9,010 个 TileEntity（来自映射到原版方块、且没有旧版方块实体的方块，例如 FutureMC 木桶）；
  - 丢弃了 410,592 个实体（暂未导入）。
- 在地图上 6 个地点做了截图巡览（NebulaeCraft 方块最多的 6 个区块簇）：摩天楼的外立面和内部、道路、人行横道、高架桥、NebulaeCraft 色块像素画，以及 Sakura 的枫树和樱花树都渲染正确。

使用的开发命令：

```bash
cd mirror/mod && ./gradlew runImportServer -PimportSource=/abs/path/world12-full -PimportRunDir=run/full
```

## 已知限制

| 项目 | 状态 |
|---|---|
| ArchitectureCraft 形状方块 | 模型、屋顶和窗户可以渲染（P1）；覆层（世界里没有用到）显示为基础材质的完整方块 |
| Chisels&Bits | 可以渲染（P1） |
| 实体（盔甲架、物品展示框、画、生物、矿车） | 暂未导入（测试区域里丢弃了 139 个）。装饰性实体留待以后处理 |
| 原版容器里的 mod 物品 | 在 1.21 里无法存在，被丢弃（只影响镜像的副本；原服不受影响） |
| 在线增量同步 | 已在 P2 完成，见 [sync.md](sync.md)；跳过被锁定的区域使用 `SyncGuard` 钩子，P3 的区域会接入它 |
