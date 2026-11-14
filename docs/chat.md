# Chat sync (origin ⇄ mirror)

Player chat, joins and leaves are shown on the other server. Everything is configured on the **mirror**, in the `chat` section of `config/nebulaemirror-sync.json`. The origin's bridge mod (1.3 or later, formerly 0.3.0, see [bridge.md](bridge.md)) only relays and has no settings of its own.

## Setup

1. Install bridge 1.3 or later on the origin (`legacy/bridge/build/libs/MirrorBridge-1.12.2-1.4.jar`) and restart it.
2. Fill in `bridge.url`/`bridge.token` in the mirror's sync config, as for write-back. Chat sync does not need `source`, so it also works with sync switched off.
3. Set `chat.enabled` to `true`, then run `/nebulaemirror sync reload` (no restart needed). The first start of the new mirror mod adds the `chat` section with the defaults below, switched off.

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

| Field | Meaning |
|---|---|
| `enabled` | Master switch |
| `shownOnOrigin` | What mirror players do, as shown on the origin. `enabled: false` turns off this direction |
| `shownOnMirror` | What origin players do, as shown on the mirror. `enabled: false` turns off this direction |
| `chat` / `join` / `leave` | Templates. An empty string means that kind of message is not synced |

Templates:
- Placeholders: `{player}` (player name) and `{message}` (chat text; empty for join/leave).
- Codes: `&0`–`&9`, `&a`–`&f` colours, `&k` obfuscated, `&l` bold, `&m` strikethrough, `&n` underline, `&o` italic, `&r` reset. As in vanilla, a colour code also ends bold/italic/etc. `&#RRGGBB` works on the mirror. On the 1.12 origin it becomes `&r`, since 1.12 has no hex colours.
- Codes only apply to the template. A player who types `&c` sees `&c`, not red text.
- If you write a direction yourself and leave out a template, it is empty, which means that kind is not synced. The mirror writes the missing keys back into the file on the next load, so you can see them.

`/nebulaemirror chat status` (or `/nm chat status`; permission `nebulaemirror.sync.status`, level 2) shows whether each direction is on, the message counts, and the last error.

## How it works

The mirror always connects to the bridge, never the other way round, using the same token and HTTP API as write-back.

- **Mirror → origin**: each chat message, join or leave on the mirror is formatted with `shownOnOrigin` and posted to `POST /v1/chat/say`. One sender thread keeps the order. The bridge shows the text to every player and logs it to the console without codes.
- **Origin → mirror**: the bridge keeps the last 200 chat messages, joins and leaves in memory. The mirror long-polls `GET /v1/chat` (up to 25 s per request) and broadcasts what arrives, formatted with `shownOnMirror`, as system messages.
- Relayed messages are system messages on both sides. Chat events don't see them, so nothing echoes back.
- Origin chat is captured at the lowest priority, and cancelled chat (for example from a mute) is skipped.

## Failures

- **Bridge unreachable**: the mirror logs one warning and keeps retrying (backoff 5 s up to 60 s). It logs again when the bridge is back. Mirror messages sent meanwhile are dropped, not queued.
- **Bridge restarted**: the mirror shows what happened at the origin since the restart, as long as it fits in the 200-entry buffer. Nothing from before the restart is shown twice.
- **Mirror restarted or reloaded**: it starts from the origin's current position. Origin chat from while the mirror was down is not replayed.
- **Bridge older than 1.3 (0.3.0)**: the mirror logs that the bridge needs updating and stops polling until the next `sync reload`.

## Verification (dev servers, 2026-11-08)

Setup: the mirror dev server (`run/p2`) and the Forge 1.12.2 dev server with bridge 0.3.0, on one machine. Test players were mineflayer bots (offline mode): a 1.12.2 bot on the origin and a 1.21.1 bot on the mirror.

- Join and leave in both directions, with the default formats.
- Chat in both directions, including Chinese text. A typed `&c` stayed literal on the other side.
- After changing `shownOnMirror.chat` and running `sync reload`, the new format applied at once. `chat.enabled: false` + reload stopped both directions.
- Bridge stopped: one warning on the mirror. Bridge restarted: "receiving from the origin again". The join and chat that happened after the restart arrived, and nothing earlier was repeated.
- `curl` against the bridge: a wrong token gave 401, empty text gave 400, and `since=-1` returned the current position with no events.

**Not tested here** (test on the real origin first): SpongeForge with the origin's chat and mute plugins. Check that a muted player's chat is not relayed, and that chat prefixes from a chat plugin don't matter: the relay uses the raw message and its own template. The mirror-side colours were checked by unit tests (`ChatFormatTest`), not on a real 1.21 client.
