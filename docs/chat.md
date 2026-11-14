# 聊天同步（原服 ⇄ 镜像）

玩家的聊天、加入和离开会显示在另一台服务器上。所有设置都在**镜像**上，位于 `config/nebulaemirror-sync.json` 的 `chat` 段。原服的 bridge mod（1.3 或更高，即原 0.3.0，见 [bridge.md](bridge.md)）只负责转发，自身没有任何设置。

## 配置步骤

1. 在原服安装 bridge 1.3 或更高版本（`legacy/bridge/build/libs/MirrorBridge-1.12.2-1.4.jar`）并重启。
2. 在镜像的同步配置里填好 `bridge.url`/`bridge.token`，和写回一样。聊天同步不需要 `source`，所以关闭同步时也能用。
3. 把 `chat.enabled` 设为 `true`，然后执行 `/nebulaemirror sync reload`（不需要重启）。新版镜像 mod 首次启动时会添加 `chat` 段，内容是下面的默认值，处于关闭状态。

```json
"chat": {
  "enabled": true,
  "shownOnOrigin": {
    "enabled": true,
    "chat":  "&7[来自镜像] &f{player} &7: &f{message}",
    "join":  "&7[来自镜像] &e{player} 加入了镜像服",
    "leave": "&7[来自镜像] &e{player} 离开了镜像服"
  },
  "shownOnMirror": {
    "enabled": true,
    "chat":  "&7[来自源] &f{player} &7: &f{message}",
    "join":  "&7[来自源] &e{player} 加入了源服务器",
    "leave": "&7[来自源] &e{player} 离开了源服务器"
  }
}
```

| 字段 | 含义 |
|---|---|
| `enabled` | 总开关 |
| `shownOnOrigin` | 镜像玩家的动作在原服上的显示方式。`enabled: false` 关闭这个方向 |
| `shownOnMirror` | 原服玩家的动作在镜像上的显示方式。`enabled: false` 关闭这个方向 |
| `chat` / `join` / `leave` | 模板。空字符串表示不同步这一类消息 |

模板：
- 占位符：`{player}`（玩家名）和 `{message}`（聊天内容；加入/离开时为空）。
- 格式代码：`&0`–`&9`、`&a`–`&f` 颜色，`&k` 乱码，`&l` 粗体，`&m` 删除线，`&n` 下划线，`&o` 斜体，`&r` 重置。和原版一样，颜色代码会同时结束粗体/斜体等样式。`&#RRGGBB` 在镜像上可用；在 1.12 原服上它会变成 `&r`，因为 1.12 没有十六进制颜色。
- 格式代码只对模板生效。玩家输入 `&c` 时，对方看到的是 `&c`，而不是红色文字。
- 如果你自己写了某个方向但漏掉了某个模板，它就是空的，也就是不同步那一类消息。镜像在下次加载时会把缺少的键写回文件，方便你看到。

`/nebulaemirror chat status`（或 `/nm chat status`；权限 `nebulaemirror.sync.status`，等级 2）显示每个方向是否开启、消息计数和最近一次错误。

## 工作原理

始终是镜像去连接 bridge，不会反过来；使用与写回相同的 token 和 HTTP API。

- **镜像 → 原服**：镜像上的每条聊天、加入或离开按 `shownOnOrigin` 格式化后，发到 `POST /v1/chat/say`。单个发送线程保证顺序。bridge 把文字显示给所有玩家，并去掉格式代码后记到控制台。
- **原服 → 镜像**：bridge 在内存里保留最近 200 条聊天、加入和离开。镜像长轮询 `GET /v1/chat`（每次请求最长 25 秒），把收到的内容按 `shownOnMirror` 格式化后作为系统消息广播。
- 转发的消息在两边都是系统消息。聊天事件看不到它们，所以不会产生回声。
- 原服的聊天以最低优先级捕获，被取消的聊天（例如被禁言）会被跳过。

## 故障情况

- **连不上 bridge**：镜像记录一条警告并持续重试（退避间隔从 5 秒到 60 秒）。bridge 恢复时会再记一条日志。这期间镜像发出的消息被丢弃，不会排队。
- **bridge 重启**：只要还在 200 条的缓冲区内，镜像会显示重启之后原服发生的事情。重启之前的内容不会重复显示。
- **镜像重启或重新加载**：从原服当前的位置开始。镜像停机期间原服的聊天不会补发。
- **bridge 版本低于 1.3（0.3.0）**：镜像记录一条日志说明 bridge 需要更新，并停止轮询，直到下一次 `sync reload`。

## 验证（开发服务器，2026-11-08）

环境：镜像开发服务器（`run/p2`）和装了 bridge 0.3.0 的 Forge 1.12.2 开发服务器，在同一台机器上。测试玩家是 mineflayer 机器人（离线模式）：原服上一个 1.12.2 机器人，镜像上一个 1.21.1 机器人。

- 两个方向的加入和离开，使用默认格式。
- 两个方向的聊天，包括中文。输入的 `&c` 在另一边保持原样。
- 修改 `shownOnMirror.chat` 并执行 `sync reload` 后，新格式立即生效。`chat.enabled: false` + reload 后两个方向都停止。
- 停掉 bridge：镜像上出现一条警告。重启 bridge：出现「receiving from the origin again」。重启之后发生的加入和聊天都送达了，更早的内容没有重复。
- 用 `curl` 访问 bridge：错误的 token 返回 401，空文本返回 400，`since=-1` 返回当前位置且不带事件。

**此处未测试**（请先在真实原服上测试）：带有原服聊天插件和禁言插件的 SpongeForge。需要确认被禁言玩家的聊天不会被转发，以及聊天插件加的前缀不会造成影响：转发使用的是原始消息和它自己的模板。镜像一侧的颜色是用单元测试（`ChatFormatTest`）检查的，没有在真实的 1.21 客户端上看过。
