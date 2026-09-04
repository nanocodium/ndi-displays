# DMX maps

Requires [Theatrical](https://github.com/theatricalmod/Theatrical). Patch with Theatrical’s configuration card. Winches and screens register as consumers when the mod is loaded.

## Screens — 2 ch

LED wall, round, and curved screens (`ScreenDmxConsumer`).

| Ch | Function |
|----|----------|
| 1 | Dimmer — scales video brightness, 0 = blackout |
| 2 | Source select — eight NDI slots, one per 32 DMX values (0–31 = slot 1, …) |

Patch a whole merged wall as **one** 2-channel fixture.

## Winch linked — 4 ch (default)

LED tile / slat / mirror ball, cables locked together.

| Ch | Function |
|----|----------|
| 1–2 | Height coarse / fine (16-bit). 0 = all the way up, 65535 = all the way down |
| 3 | Speed — 0 = configured working speed, else scales toward max |
| 4 | Dimmer |

## Winch twin — 6 ch

Independent cables; the tile tilts with the height difference.

| Ch | Function |
|----|----------|
| 1–2 | Winch A 16-bit |
| 3–4 | Winch B 16-bit (clamped within tilt limit around A) |
| 5 | Speed |
| 6 | Dimmer |

## Kinetic sphere — 7 ch

Height 16-bit, speed, dimmer, RGB.

## Flown fixture — 4 / 7 / 10 ch

Nested modes (channel N never reshuffles):

| Ch | BASIC | COLOUR | FULL |
|----|-------|--------|------|
| 1–2 | Height 16-bit | same | same |
| 3 | Speed | same | same |
| 4 | Intensity | same | same |
| 5–7 | — | R G B | R G B |
| 8 | — | — | Focus |
| 9 | — | — | Pan (128 = centre, ±270°) |
| 10 | — | — | Tilt (128 = centre, ±135°) |

Omitted channels **hold** rather than zero. If the hung fixture publishes Theatrical personalities, those descriptions and slot lists win.

Beams can go through Extra Lights’ volumetric pipeline when that mod is present.
