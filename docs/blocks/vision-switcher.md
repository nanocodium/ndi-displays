# Vision Switcher

ATEM-style desk: **eight NDI inputs**, a **program** bus and a **preview** bus, and real transitions. Right-click sits at the panel. The program output broadcasts whether or not anyone is at the desk.

CUT swaps the buses instantly. AUTO runs the selected transition (mix, dip to black, or wipe) over the set rate, then program and preview have traded places — flip-flop, like the hardware.

The server owns every bus and syncs it, so two operators see the same cut.

## Registry ID

`ndidisplays:vision_switcher`

## Crafting

See [Recipes](/reference/recipes) (`vision_switcher.json`).

## Configuration

| Field | Meaning |
|-------|---------|
| Name | Becomes the NDI output label |
| Inputs 1–8 | Source names (picker or substring) |
| Program / Preview | Live and next. Black is a bus below slot 0 |
| Style | Mix, dip, wipe |
| Rate | AUTO length: 0.5 s / 1 s / 2 s (10 / 20 / 40 ticks) |
| Resolution / FPS | 480p / 720p / 1080p, default 30 |
| Broadcast | Publish the program bus |

Panel monitors are **16:9**, matching the feeds.

## NDI behavior

**Send.** `MC Switcher <name>`. The broadcast host composites the transition frame by frame on the GPU, so every wall, [Pro Monitor](/blocks/pro-monitor), [Multiview](/blocks/multiview), and OBS sees the cut mid-wipe.

## Multiplayer

One [broadcast](/guide/multiplayer) client publishes. Bus state is server-authoritative for everyone sitting at the desk.

## Limits

- Eight inputs. No downstream keyer / DSK.
- Needs the Runtime on the operator machine to composite live sources.

## Integrations

None required. Point walls at the switcher name the same way you would point them at OBS.
