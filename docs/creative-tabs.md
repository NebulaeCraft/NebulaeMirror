# 创造模式标签页（镜像客户端）

镜像 mod 为每个来源 mod 添加一个创造模式标签页（「Mirror: Nebulaecraft」「Mirror: Quark」等）。每位玩家可以在
**客户端**的 `config/nebulaemirror-client.json` 里隐藏标签页、选择图标（mod 1.3+）。这与服务器无关，所以各人
可以有不同的设置。

```json
{
  "creativeTabs": {
    "nebulaecraft": { "show": true,  "icon": "nebulaecraft:asphalt" },
    "chisel":       { "show": true,  "icon": "chisel:marble[variation=3]" },
    "quark":        { "show": false, "icon": "" },
    "sakura":       { "show": true,  "icon": "minecraft:cherry_sapling" }
  }
}
```

- 键是来源 mod 的命名空间，也就是标签页标题的小写形式。
- `show: false` 隐藏该标签页。它的物品**仍然能在创造模式搜索里找到**，其余标签页会自动补位
  （Fabric 会重新计算标签页分页，所以不会留空位）。
- `icon` 可以是任意物品 id，后面可以用 `[...]` 带上方块状态属性来指定某个 1.12 子类型（例如
  `nebulaecraft:asphalt[subtype=subtype2]`、`chisel:marble[variation=3]`）。留空则使用标签页的第一个物品。
  未知的物品、属性或取值会被忽略（标签页保持默认图标），客户端日志里会有一条警告，列出可用的属性或取值。
- 首次启动时会写出这个文件，包含所有标签页，全部显示、使用默认图标。新版 mod 增加的标签页会追加进去；
  玩家自己写的条目永远不会被改动。

**应用修改**：游戏运行时直接编辑文件即可；一秒内会重新读取，下次打开创造模式物品栏就会显示新的标签页和图标。
文件不是合法 JSON 时会在日志里报告（`Creative tabs: cannot read ...`），并继续使用之前的设置。

## 工作原理

标签页仍然在两端原样注册，所以客户端和服务端的注册表一致。只有标签页的内容是客户端决定的：隐藏的标签页只把
物品放进搜索页（`SEARCH_TAB_ONLY`），而原版不会显示没有内容的标签页。图标生成器读取配置里指定的物品堆。文件
变化时，`client/CreativeTabsClient` 清除每个标签页缓存的图标，以及 `CreativeModeTabs` 缓存的显示参数
（accessor mixin），于是下次打开创造模式界面时会重建所有标签页，Fabric 也会重新分页。

## 验证（2026-11-08，mod 1.3）

- 单元测试 `CreativeTabConfigTest`：首次启动列出所有标签页；保留用户条目并追加新标签页，完整的文件不会被重写；
  损坏的 JSON 报错且文件不被改动；图标语法。
- 开发客户端巡览（`run/tour/tour-tabs.txt`，新增巡览指令 `creative <name> <page>` 和
  `file <name> <src> <dst>`），完整的生成器输出，13 个镜像标签页：
  - 配置 A（隐藏 quark、chisel、railcraft、extrautils2；nebulaecraft 用 `asphalt[subtype=subtype2]`；sakura 用
    `minecraft:cherry_sapling`；两个无效图标）：9 个镜像标签页排在一页里，两个自定义图标都显示出来，两个无效图标
    记入日志并保持默认。
  - 巡览过程中用配置 B 覆盖文件：一秒内重新加载；13 个标签页全部回来、分成两页，chisel 显示
    `marble{variation=3}`，sakura 恢复默认。
  - 两种配置下搜索页都有 5602 个物品：被隐藏标签页的物品仍然可以搜索到。
