# NDI Drone

![NDI drone](/img/items/drone.png)

Place the drone, link a remote, then fly it in FPV — you are in the gimbal. WASD strafes, space / sneak climb, mouse aims. The NDI source `MC Drone <id>` is the same view.

Sneak-click the remote for waypoints (once / loop / ping-pong). Optional Xaero import.

## Registry IDs

| Item | ID |
|------|-----|
| Drone | `ndidisplays:drone` |
| Remote | `ndidisplays:drone_remote` |

![Drone remote](/img/items/drone_remote.png)
| Entity | `ndidisplays:drone` |

## Crafting

See [Recipes](/reference/recipes) (`drone.json`, `drone_remote.json`).

## Configuration

1. Place the drone item in the world.
2. Right-click with the remote to **link**.
3. Use the remote to enter FPV / config (`DroneConfigScreen`).
4. Gamepad: Options → drone pad bindings, stick calibration wizard. Defaults in [Client config](/reference/config).
5. Waypoints: sneak-click remote — once, loop, ping-pong. Import from Xaero when that mod is loaded.

## NDI behavior

**Send** `MC Drone <id>` (short id) from the broadcast client. Same picture as FPV.

## Multiplayer

Published from the same machine that broadcasts other rigs. The entity is persistent on the server. Link is per remote.

## Limits

- FPV is the gimbal, not a third-person chase cam.
- Capture budget shared with cameras.
- Unloaded terrain is empty.
- Pad GUIDs are stored in client config (`drone_pad.joystickGuid`).

## Integrations

**Xaero Minimap / World Map** (optional): import the current world’s waypoints. The button only appears when Xaero is loaded.
