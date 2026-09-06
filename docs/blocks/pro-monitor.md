# Pro Monitor

![Pro monitor](/img/blocks/pro_monitor.png)

Production monitor: a desk display that shows **one** NDI source on its panel — the [multiview](/blocks/multiview)'s single-feed sibling, for director's desks, green rooms, and gallery walls.

Pure receiver: source name and brightness. No LED pitch, no bezels.

## Registry ID

`ndidisplays:pro_monitor`

## Crafting

See [Recipes](/reference/recipes) (`pro_monitor.json`).

## Configuration

Right-click: pick the source, set brightness (0.1–1). The [NDI Configuration Card](/items/ndi-config-card) writes its stored name onto the panel.

Park it next to a [Vision Switcher](/blocks/vision-switcher) and point it at `MC Switcher <name>` for a program monitor.

## NDI behavior

**Receive.** Direct video, same family as the multiview.

## Multiplayer

Every viewing client pulls the named source. Settings live on the server.

## Limits

One source per block. Use a [Multiview](/blocks/multiview) when you need 2×2 / 3×3.

## Integrations

None required.
