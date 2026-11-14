# WorldEdit 短命令（镜像）

镜像运行的是上游 WorldEdit 7.3，不是 FAWE。为了保留玩家在原服已经习惯的命令，镜像 mod 添加了一些短别名，
它们实际执行完整的 WorldEdit 命令（`mirror/mod/.../worldedit/`）。

| 输入 | 实际执行 | 备注 |
|---|---|---|
| `//s` | `//set` | |
| `//r` | `//replace` | WorldEdit 自带 `//re`、`//rep` |
| `//c`、`//cp` | `//copy` | `//cp` 是 FAWE 的别名 |
| `//p`、`//pa` | `//paste` | FAWE |
| `//mirror` | `//flip` | FAWE |
| `//mv` | `//move` | FAWE |
| `//1`、`//2` | `//pos1`、`//pos2` | FAWE |
| `//0` | `//set air` | 代替 FAWE 的 `//air` |

参数和标志原样传递（`//r stone 50%glass,50%air`、`//p -a`、`//0 -m`）。Tab 补全是 WorldEdit 自己的。
权限、区域限制（`EditGuard`）、`//undo` 历史和提示消息都是目标命令的：别名只改写命令行。

## 配置

`config/nebulaemirror-we-aliases.json` 在首次启动时按上表写出。条目按玩家输入的样子书写；目标命令可以带固定
参数：

```json
{
  "enabled": true,
  "aliases": {
    "//s": "//set",
    "//h": "//hollow",
    "//w": "//walls stone_bricks"
  }
}
```

修改在 `/reload` 或重启后生效；服务器日志会列出它们（`WorldEdit aliases: [...]`）。如果别名的名字已经是一个
命令（WorldEdit 自己的别名优先），或者目标命令不存在，该条目会被跳过并给出警告。没有安装 WorldEdit 时什么都
不会注册。

## 为什么不用 Brigadier 重定向

WorldEdit 的 Fabric 命令节点不看解析树：它们把 `"/" + context.getInput()` 交给 WorldEdit 自己的分发器。所以
重定向到达 WorldEdit 时会是未知命令 `//s`。每个别名改为通过服务器的分发器执行改写后的命令行（`//set …`），
并向目标命令请求补全，再把补全位置移回到玩家实际输入的文本上。

## 验证（2026-11-08，mod 1.2）

- 单元测试 `CommandAliasesTest`：改写、固定参数、被跳过的名字和目标、继承的权限要求、补全位置、配置默认值和
  解析错误。
- 开发服务器 `run/p2` 加 `-PwithWorldEdit`：11 个默认别名全部注册；在控制台执行 `//s stone` 和 `//0` 都到达
  `//set`（WorldEdit 要求指定世界，和直接执行 `//set` 一样），`//r` 打印 `//replace` 的用法。
- 尚未检查：玩家在游戏里实际使用（一次真实的编辑，以及客户端上的 Tab 补全）。
