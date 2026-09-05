# Multiview Monitor

2×2 or 3×3 mosaic of NDI sources for the video engineer. Direct video, no LED simulation. Click a cell, then pick its source.

## Registry ID

`ndidisplays:multiview`

## Crafting

See [Recipes](/reference/recipes) (`multiview.json`).

## Configuration

Right-click → layout 2×2 or 3×3. Click a cell → source picker.

## NDI behavior

**Receive**, up to nine streams at once. Captions show the source name. Not a sender.

## Multiplayer

Each operator client pulls the mosaic itself. Heavy if every cell is a 1080p camera.

## Limits

No pixel-pitch / bezel shader. Internal atlas 256. Budget NDI receives like any other wall.

## Integrations

None required. Pair with [Router](/blocks/ndi-router) and [Park Monitor](/blocks/winch-park-monitor).
