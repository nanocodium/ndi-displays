# Client config

File: `config/ndidisplays-client.toml` (client only). The server never talks NDI.

Values below are the **defaults** from `ClientConfig.java`.

## `[broadcast]`

| Key | Default | Meaning |
|-----|---------|---------|
| `mode` | `AUTO` | `AUTO` — broadcast in singleplayer / LAN host only. `ALWAYS` — operator machine on a dedicated server. `NEVER` — receive only. |
| `handheld` | `true` | Publish `MC Handheld <player>` while the item is held. Several players can each carry one. |

See [Multiplayer](/guide/multiplayer).

## `[drone_pad]`

Values are `button:N`, `axis:N`, `unbound`, or stick tokens `left` / `right`. Sticks can also be raw axis pairs from the calibration wizard.

| Key | Default |
|-----|---------|
| `climb` | `button:0+axis:5` |
| `descend` | `axis:4` |
| `exit` | `button:1+button:6` |
| `waypoint` | `button:3` |
| `menu` | `button:7` |
| `pathPlay` | `unbound` |
| `pathStop` | `unbound` |
| `moveStick` | `left` |
| `lookStick` | `right` |
| `joystickGuid` | `""` (empty) |
| `invertLookY` | `false` |

Calibrate in the drone pad options / stick wizard. [Drone](/items/drone).

## Screen lights

Not a toml key. Content-coloured wash in front of LED walls (Shimmer) is a JVM flag, **off** by default — it reads as ugly ambient light on nearby architecture more often than as a video wall lighting a stage. Bloom on the cabinet itself is unaffected.

```
-Dndidisplays.screenLights=true
```

Add that to the client JVM arguments (launcher / `user_jvm_args.txt`). [LED Wall](/blocks/led-panel).

## JVM flags (client)

Dev / operator overrides. Gradle `runClient` also accepts `-PdebugCapture` and `-PperfLog`.

| Property | Default | Meaning |
|----------|---------|---------|
| `ndidisplays.screenLights` | `false` | Shimmer colour wash from LED walls |
| `ndidisplays.projectorShadowBias` | `0.06` | Projector shadow-map depth bias, metres |
| `ndidisplays.captureLoad` | `0.5` | Max fraction of wall-clock time spent capturing rigs |
| `ndidisplays.targetGameFps` | `50` | FPS the adaptive capture budget tries to protect |
| `ndidisplays.maxCapturesPerFrame` | `4` | Ceiling for adaptive captures per game frame |
| `ndidisplays.capturesPerFrame` | unset | Hard override; disables adaptive budgeting |
| `ndidisplays.noCapture` | `false` | Disable every rig capture (screens still receive) |
| `ndidisplays.debugCapture` | `false` | Log each rig's capture geometry once |
| `ndidisplays.perfLog` | `false` | Log capture cost and delivered feed rates |
| `ndidisplays.volumetricBeam` | `true` | Extra Lights volumetric beams on flown fixtures |

# Server / common config

File: `config/ndidisplays-common.toml` (common — the server is authoritative).

## `[hoist]`

Chain hoist size and speed caps. Hitting a cap is a fault, never a partial lift. Defaults from `HoistConfig.java`.

| Key | Default | Meaning |
|-----|---------|---------|
| `maxBlocks` | `256` | Maximum blocks in one flown structure (1–4096). |
| `maxSizeX` | `32` | Bounding box on X, blocks (1–128). |
| `maxSizeY` | `48` | Bounding box on Y, blocks (1–128). |
| `maxSizeZ` | `32` | Bounding box on Z, blocks (1–128). |
| `maxChainLength` | `32.0` | Longest chain one motor can pay out, metres. |
| `defaultSpeed` | `0.12` | Working speed of a newly placed hoist, m/s. |
| `maxSpeed` | `1.0` | Fastest speed the pendant will accept, m/s. |
| `maxTiltDegrees` | `35.0` | Steepest rake a multi-motor hang may be held at. A motor that would exceed it stops (`OBSTRUCTED`). A raked load stays in the air until it is levelled. |
| `maxMotorsPerRig` | `8` | Motors allowed on one structure. |

[Chain Hoist](/blocks/chain-hoist).
