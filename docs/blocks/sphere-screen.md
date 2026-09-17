# Spherical LED Screen

A single mount that draws a video globe of configurable **diameter** (0.5–32 m), centred on the block. The source frame is wrapped around it as an equirectangular map: the frame runs once around the equator and from pole to pole, with the centre of the frame on the block's facing side and the seam round the back. Feed it a **2:1 panorama** and it reads correctly from every side; any other frame simply stretches around the globe.

## Registry ID

`ndidisplays:sphere_screen`

## Crafting

See [Recipes](/reference/recipes) (`sphere_screen.json`).

## Configuration

Right-click → sphere processor: NDI source, pitch, brightness, **diameter**, patterns, crop. Native size is the unrolled surface — circumference × half-circumference at the chosen pitch (a 2:1 frame).

## NDI behavior

**Receive only.**

## Multiplayer

Client receive. Config on the server.

## Limits

- One block, not a merged grid. The globe is drawn around the mount and has no collision.
- Native feed 3840×2160 cap — coarsen pitch for a large globe.
- The picture is visible from inside the globe too (mirrored).
- No Shimmer room light from the globe yet.

## Integrations

Theatrical 2ch screen DMX (optional). NDI configuration card.
