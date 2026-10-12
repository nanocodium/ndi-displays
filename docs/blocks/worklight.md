# Halogen Work Light

A 2 kW halogen flood in a yoke, the lamp a crew switches on to see the stage between shows. It hangs from the block above, tilts in its yoke and lights the room the moment it is on.

## Registry ID

`ndidisplays:worklight`

## Crafting

See [Recipes](/reference/recipes) (`worklight.json`).

Iron ingots, glass panes, a blaze rod for the tube.

## Placing and aiming

Place it against the underside of a truss or ceiling block. It aims **away from you**: stand in the house, point it at the stage.

| Action | Result |
|--------|--------|
| Right-click | Switch on / off. |
| Sneak + right-click | Tilt the head one notch (15°) further down; wraps back to level after 90°. |
| Right-click with a [Work Light Remote](/items/worklight-remote) | Patch this lamp into the remote's group. |

## Light

Three layers, each present when its mod is:

- **Always**: block light level 15 while on, a warm emissive tube and an additive halo on the glass. The halo is drawn by the mod itself, no shader pack needed.
- **With Theatrical Extra Lights**: a wide warm volumetric shaft through the same raymarch pipeline as Extra Lights' own fixtures, so the flood shows in stage haze like a real halogen.
- **With Shimmer**: a coloured point light on the floor in front of the lamp.

## Groups

A lamp remembers one group name, set by tapping it with a named remote. Groups are saved with the world; see the remote page.

## Multiplayer

Switching and tilting are server actions synced to every client. The usual build / claim rules apply.

## Limits

One tilt axis, no pan: turn the block to aim horizontally. Cone width is fixed at the widest angle the beam bridge offers.
