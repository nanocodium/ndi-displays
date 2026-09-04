# Shoulder Camera

Chest-slot rig. Publishes `MC Shoulder <player>` while worn, leaving both hands free — ENG shoulder cam versus the handheld.

## Registry ID

`ndidisplays:shoulder_camera`

## Crafting

See [Recipes](/reference/recipes) (`shoulder_camera.json`).

## Configuration

Wear in the chest slot. Shoulder rig GUI for live/options when implemented in the client (`ShoulderRigScreen`). Same capture pipeline as handheld.

## NDI behavior

**Send** while worn: `MC Shoulder <player>`. If both handheld and shoulder apply, the client uses the shoulder prefix when the rig is worn.

## Multiplayer

Per-player name. Broadcast role still required.

## Limits

Same capture budget and chunk visibility as other first-person sends.

## Integrations

None required.
