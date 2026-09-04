# Winch payloads

Configured on the [Kinetic LED Winch](/blocks/kinetic-winch). Constants in `KineticWinchBlockEntity`.

| Payload | Const | Video | Notes |
|---------|-------|-------|--------|
| LED Tile | `PAYLOAD_LED_TILE` (0) | Yes | Vertical or flat (sky-facing). Mesh = blow-through. Canvas stitch via `UvRegion`. |
| Kinetic RGB sphere | `PAYLOAD_KINETIC_SPHERE` (1) | No | Decorative / DMX colour. |
| Mirror ball | `PAYLOAD_MIRROR_BALL` (2) | No | Décor, no extra DMX beyond winch 4ch. |
| Theatrical fixture | `PAYLOAD_FIXTURE` (3) | Beams | Registry id of a Theatrical / Extra Lights block on the hook. |
| LED Slat | `PAYLOAD_SLAT` (4) | Yes | Vertical kinetic blade. |

## LED tile / slat

Same processor ideas as walls: source, pitch presets, crop. Parks: each winch stores `Canvas W×H` and `My Col/Row` so a grid stays one picture as motors move. Card **stitch** vs full-frame per motor: [NDI Configuration Card](/items/ndi-config-card).

## Flown fixture

Set payload to fixture and pick a Theatrical / Extra Lights block id. Modes (nested, channels only added at the tail):

| Mode | Const | Channels |
|------|-------|----------|
| Basic | `FIXTURE_MODE_BASIC` | 4 — height 16-bit, speed, intensity |
| Colour | `FIXTURE_MODE_COLOUR` | 7 — plus RGB |
| Full | `FIXTURE_MODE_FULL` (default) | 10 — plus focus, pan, tilt |

When the fixture declares its own Theatrical personalities, the winch prefers those names and slot maps. Channel maps: [DMX](/kinetics/dmx).

To fly a **whole block structure** (truss + fixtures + speakers) rather than a single winch payload, use the [Chain Hoist](/blocks/chain-hoist). That system does not use these payload enums.
