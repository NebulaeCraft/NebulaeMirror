# Creative tabs (mirror client)

The mirror mod adds one creative tab per source mod ("Mirror: Nebulaecraft", "Mirror: Quark", …). Each player
can hide tabs and choose their icons in the **client's** `config/nebulaemirror-client.json` (mod 1.3+). The
server is not involved, so players can set it up differently.

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

- The key is the source mod's namespace, i.e. the tab title in lower case.
- `show: false` hides the tab. Its items **stay in the creative search**, and the remaining tabs close up
  (Fabric's tab pages are recomputed, so there are no gaps).
- `icon` is any item id, optionally with block state properties in `[...]`, which picks a 1.12 subtype (e.g.
  `nebulaecraft:asphalt[subtype=subtype2]`, `chisel:marble[variation=3]`). Empty: the tab's first item.
  An unknown item, property or value is ignored (the tab keeps its default icon), with a warning in the client log
  that lists the valid properties or values.
- The file is written on first start with every tab, all shown with default icons. Tabs added by a newer mod
  version are appended; entries the player wrote are never changed.

**Applying changes**: edit the file while the game runs; it is re-read within a second, and the next time the
creative inventory opens it shows the new tabs and icons. A file that is not valid JSON is reported in the log
(`Creative tabs: cannot read ...`) and the previous settings stay in effect.

## How it works

The tabs are still registered on both sides, unchanged, so client and server registries match. Only what a tab
builds is client-side: a hidden tab puts its items into the search tab only (`SEARCH_TAB_ONLY`), and vanilla
does not display a tab without contents. The icon generator reads the configured stack. On a file change
`client/CreativeTabsClient` clears each tab's cached icon and the cached display parameters of
`CreativeModeTabs` (accessor mixins), so the next creative screen rebuilds every tab and Fabric re-paginates.

## Verification (2026-11-08, mod 1.3)

- Unit tests `CreativeTabConfigTest`: first start lists every tab, user entries kept and new tabs appended
  without rewriting a complete file, broken JSON is an error and the file is left alone, icon syntax.
- Dev client tour (`run/tour/tour-tabs.txt`, new tour lines `creative <name> <page>` and
  `file <name> <src> <dst>`), full generator output, 13 mirror tabs:
  - Config A (quark, chisel, railcraft, extrautils2 hidden; nebulaecraft `asphalt[subtype=subtype2]`; sakura
    `minecraft:cherry_sapling`; two invalid icons): 9 mirror tabs on one page, both custom icons shown, the two
    invalid icons logged and left at their defaults.
  - Config B copied over the file during the tour: reloaded within a second; all 13 tabs back on two pages,
    chisel shows `marble{variation=3}`, sakura back to its default.
  - The search tab held 5602 items under both configs: hidden tabs' items stay searchable.
