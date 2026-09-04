# Hoist Remote

Radio belly-box for [chain hoists](/blocks/chain-hoist). Right-click the air to open a yellow pendant: emergency stop, group selector, up / stop / down.

The remote is a keypad. The server looks the motors up itself and applies the same safety checks as the block GUI.

## Registry ID

`ndidisplays:hoist_remote`

## Crafting

See [Recipes](/reference/recipes) (`hoist_remote.json`).

Redstone, stone button, iron nuggets.

## Use

| Action | Result |
|--------|--------|
| Right-click air | Open the pendant. The group list is sent by the server. |
| Right-click a hoist | Patch that motor into the remote's selected group. If the remote is on **ALL**, it instead **tunes** to the motor's existing group. |
| Mushroom (red) | **E-stop**: stop every hoist in range, latch the remote. Click again to twist-release. While latched, UP / DOWN / pick-up do nothing. |
| UP / DOWN / STOP | Run the selected group (or every nearby motor on **ALL**). |
| PICK UP / SET DOWN | Attach or land. |

The selector always has **ALL** first (motors in loaded chunks around you, 8-chunk sweep), then every named group. Named groups use the world's hoist index, so they work across the venue as long as those chunks are loaded.

The readout shows motors loaded / known, average chain, and load (`N/A` when nothing is attached).

Reach is 192 blocks (a roof run from the floor). Same build / claim rules as the pendant.

## Persistence

The selected group and the e-stop latch live on the item. Closing the GUI does not release a latched mushroom.

## Multiplayer

Commands are server-authoritative. Spectators and adventure-mode players cannot run motors. `mayInteract` is honoured (spawn protection, claims).
