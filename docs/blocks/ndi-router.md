# NDI Router

![NDI router](/img/blocks/ndi_router.png)

Publishes a **stable output name** and forwards whichever source is patched to it. Uses NDI routing — no decode, no re-encode, no extra GPU cost.

Rename the output once in OBS; re-patch the router when the show changes.

## Registry ID

`ndidisplays:ndi_router`

## Crafting

See [Recipes](/reference/recipes) (`ndi_router.json`).

## Configuration

Right-click → output name, patched source, optional resolution metadata (`1280` / `1920` / `2560` width presets in code).

## NDI behavior

**Send (route).** Output name is what OBS should lock to. Input is any discovered source. Forwarding is an NDI route, not a Minecraft texture.

## Multiplayer

Only the [broadcast](/guide/multiplayer) client should publish router outputs, same as cameras.

## Limits

Routing still needs the Runtime. No LED simulation on the block itself.

## Integrations

None required.
