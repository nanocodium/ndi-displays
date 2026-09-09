# Camera Winch

Ceiling drum that anchors one corner of an [Active Cam](/blocks/active-cam) rig. Four of these define the authorised XZ area. Cables are visual only — length is `sqrt(dx²+dy²+dz²)` to the gondola.

## Registry ID

`ndidisplays:camera_winch`

## Crafting

Shaped: iron, string, piston. See [Recipes](/reference/recipes) (`camera_winch.json`).

## Configuration

No processor GUI. Sneak + right-click with the [NDI Configuration Card](/items/ndi-config-card) to add this winch to the card (four slots). Right-click the [Active Cam Controller](/blocks/active-cam-controller) to bind the rig.

Right-click empty-handed to see bound / unbound status.

## NDI behavior

n/a — the gondola is the sender.

## Multiplayer

Block on the server. Cable mesh is client-rendered from interpolated gondola pose.

## Limits

Must hang above the working volume. If this block is broken or unloaded while bound, the controller **emergency-stops**. Max cable length is clamped server-side.

## Integrations

None. Not a Kinetic LED Winch (no LED tile, no Theatrical DMX on this block).
