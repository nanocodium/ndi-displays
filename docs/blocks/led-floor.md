# LED Floor Tile

![LED floor tiles](/img/blocks/led_floor.png)

Walkable module on the XZ plane. Adjacent same-facing tiles stitch into one floor canvas — colour bars, grids, or live video underfoot.

## Registry ID

`ndidisplays:led_floor`

## Crafting

See [Recipes](/reference/recipes) (`led_floor.json`).

## Configuration

Right-click → **LED Floor Processor** (same idea as the wall): source, pitch, brightness, gamma, patterns, crop. **Apply to Floor** for the merged rectangle.

## NDI behavior

**Receive only.** Per-client pull of the named source.

## Multiplayer

Runtime on each viewer. Server stores config.

## Limits

- Merge span **256** (`FloorScanner.MAX_SPAN`).
- Walkable collision on XZ.
- Native feed 3840×2160 cap.
- Light level 10.

## Integrations

Shimmer (optional). Theatrical 2ch screen DMX (optional).
