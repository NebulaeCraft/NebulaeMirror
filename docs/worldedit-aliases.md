# WorldEdit short commands (mirror)

The mirror runs upstream WorldEdit 7.3, not FAWE. To keep the commands players already know from the origin,
the mirror mod adds short aliases that run the full WorldEdit command (`mirror/mod/.../worldedit/`).

| Typed | Runs | Note |
|---|---|---|
| `//s` | `//set` | |
| `//r` | `//replace` | WorldEdit already has `//re`, `//rep` |
| `//c`, `//cp` | `//copy` | `//cp` is FAWE's alias |
| `//p`, `//pa` | `//paste` | FAWE |
| `//mirror` | `//flip` | FAWE |
| `//mv` | `//move` | FAWE |
| `//1`, `//2` | `//pos1`, `//pos2` | FAWE |
| `//0` | `//set air` | stands in for FAWE's `//air` |

Arguments and flags go through unchanged (`//r stone 50%glass,50%air`, `//p -a`, `//0 -m`). Tab completion is
WorldEdit's own. Permissions, zone limits (`EditGuard`), `//undo` history and messages are the target
command's: an alias only rewrites the command line.

## Configuration

`config/nebulaemirror-we-aliases.json` is written with the table above on first start. Write entries the way
players type them; the target may carry fixed arguments:

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

Changes take effect on `/reload` or restart; the server log lists them (`WorldEdit aliases: [...]`). An entry is
skipped with a warning when its name is already a command (WorldEdit's own aliases come first) or its target
command does not exist. Nothing is registered when WorldEdit is not installed.

## Why not a Brigadier redirect

WorldEdit's Fabric command nodes ignore the parse tree: they send `"/" + context.getInput()` to WorldEdit's own
dispatcher. A redirect would therefore reach WorldEdit as the unknown command `//s`. Each alias instead executes
the rewritten line (`//set …`) through the server's dispatcher, and asks the target for completions with the
positions moved back onto the typed text.

## Verification (2026-11-08, mod 1.2)

- Unit tests `CommandAliasesTest`: rewriting, fixed arguments, skipped names and targets, inherited permission
  requirement, suggestion positions, config defaults and parse errors.
- Dev server `run/p2` with `-PwithWorldEdit`: all 11 defaults registered; from the console `//s stone` and `//0`
  reach `//set` (WorldEdit asks for a world, as for `//set` itself) and `//r` prints the `//replace` usage.
- Not checked yet: in game with a player (an actual edit, and tab completion on the client).
