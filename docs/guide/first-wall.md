# First wall

1. Place a rectangle of [LED Wall Panels](/blocks/led-panel), all facing the same way. New cabinets show colour bars so you can see the wall is alive.
2. Right-click any cabinet. The **LED Wall Processor** opens.
3. Pick a discovered NDI source (or type a fragment of the name). Set pitch, brightness, gamma. Pattern → **NDI Video**. **Apply to Wall**.
4. The whole rectangle plays the feed. Settings are per-wall and saved with the world.

Prefer a short distinctive fragment (`Arena - Composition`) over the full machine-prefixed name. Matching is exact first, then case-insensitive substring, so the short form survives a hostname change.

Need the Runtime first? See [Install](/guide/install). Walls stay on colour bars if NDI is missing or the source name does not match — [Troubleshooting](/guide/troubleshooting).

## Angled walls

Panels have eight orientations. Facing a cardinal gives a square cabinet; facing between them gives a 45° **chamfer** (a flat cut). Stand square to the angle you want and place on the ground. Clicking a block face still snaps flush to that face.

Build a 45° wall as a staircase — one block diagonally each time — and the cabinets meet corner to corner. A straight run and a 45° wing are **two** walls with their own sources unless a diagonal cabinet bridges them.

For a **smooth 90°** wrap, place an [LED Corner Cabinet](/blocks/led-panel#90-turns) (`ndidisplays:led_corner`) between the two cardinal runs. Craft it from one LED panel. Sneak-place for the inner corner. Two flats in an L with **no** corner stay two screens.
