# Round LED Screen

![Round LED screen showing colour bars](/img/blocks/round_screen.png)

A single mount that draws a video disc of configurable radius. Same processor workflow as the walls, without building a circle out of cabinets.

## Registry ID

`ndidisplays:round_screen`

## Crafting

See [Recipes](/reference/recipes) (`round_screen.json`).

## Configuration

Right-click → round-screen processor: NDI source, pitch, brightness, gamma, **radius**, patterns, crop. Native size is derived from radius × pitch.

## NDI behavior

**Receive only.**

## Multiplayer

Client receive. Config on the server.

## Limits

- One block, not a merged grid.
- Native feed 3840×2160 cap — coarsen pitch if the disc is huge.
- Renderer internal atlas size 256.

## Integrations

Shimmer (optional). Theatrical 2ch screen DMX (optional).
