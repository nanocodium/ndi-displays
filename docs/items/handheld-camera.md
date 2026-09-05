# Handheld Camera

Item, not a block. While held, the client publishes the player's view as `MC Handheld <player>`. Several players can each carry one.

## Registry ID

`ndidisplays:handheld_camera`

## Crafting

See [Recipes](/reference/recipes) (`handheld_camera.json`).

## Configuration

No block GUI. Client config `broadcast.handheld` (default `true`) enables the sender. Resolution follows the camera capture path (round-robin with rigs).

## NDI behavior

**Send** while the item is held: `MC Handheld <player>`.

## Multiplayer

Named per player, so several operators can each carry one without clashing. Still only machines that are allowed to broadcast will publish ([Multiplayer](/guide/multiplayer)).

## Limits

First-person view of the holding player. Unloaded chunks are black. Capture budget shared with other live rigs.

## Integrations

None required.
