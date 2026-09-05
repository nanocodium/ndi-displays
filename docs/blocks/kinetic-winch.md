# Kinetic LED Winch

A ceiling motor that flies a payload on rendered cables — Freedom Stage “floating sky”, not a teleport. Motion is trapezoidal: accelerate, cruise, decelerate.

## Registry ID

`ndidisplays:kinetic_winch`

## Crafting

See [Recipes](/reference/recipes) (`kinetic_winch.json`).

## Configuration

Right-click → **Winch** GUI: payload type, height, speed, dimmer, orientation (vertical tile or flat / sky-facing), mesh vs solid tile, canvas `W×H` + `My Col/Row` for park stitch, twin vs linked cables, Theatrical fixture id when payload is a flown head.

[NDI Configuration Card](/items/ndi-config-card): sneak-click two winches to bound a park, then apply source, linked/twin, and stitch.

Payloads: [Payloads](/kinetics/payloads). Channel maps: [DMX](/kinetics/dmx).

## NDI behavior

**Receive** on LED tile / slat payloads (same processor crop / `UvRegion` as walls). Sphere and mirror ball are not video surfaces. Flown Theatrical fixtures use Extra Lights beams, not NDI.

## Multiplayer

Video receive is per client. Motor pose is server-authoritative. DMX is server-side when Theatrical is present.

## Limits

- Park stitch uses canvas cells so a flown grid stays one picture as motors move.
- Trapezoidal motion only — no instant jumps.
- Twin mode: second height clamped within the configured tilt limit around A.

## Integrations

- **Theatrical** (optional): patchable fixture, 4 / 6 / 7 / 10 ch depending on payload and mode.
- **Theatrical Extra Lights**: hang a real fixture on the hook (`PAYLOAD_FIXTURE`).
- **Shimmer**: bloom on LED tile video.
