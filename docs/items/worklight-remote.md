# Work Light Remote

The key a stage hand carries to bring the [work lights](/blocks/worklight) up between rehearsal and show.

## Registry ID

`ndidisplays:worklight_remote`

## Crafting

See [Recipes](/reference/recipes) (`worklight_remote.json`).

Redstone, stone button, iron nuggets, yellow dye.

## Use

The remote's **name is its group**. Rename it in an anvil ("Stage left", "FOH", "Load-in"), then walk the rig and tap each lamp to patch it in.

| Action | Result |
|--------|--------|
| Right-click the air | Toggle the group. If any lamp of the group is on, all go off; otherwise all come on. |
| Right-click a work light | Patch that lamp into the remote's group. An unnamed remote cannot patch: name it first. |

An **unnamed** remote switches every work light in the loaded chunks around you (6-chunk sweep). Named groups use the world's work light index, so they work across the venue as long as those chunks are loaded.

The action bar reports how many lamps answered. Reach and permissions follow the same build / claim rules as the hoist remote.

## Persistence

Group membership lives on the lamps and in the world's saved index. The remote itself holds nothing but its name, so several remotes with the same name drive the same group.
