# First wall

![LED wall panel showing colour bars](/img/blocks/led_panel.png)

1. Place a rectangle of [LED Wall Panels](/blocks/led-panel), all facing the same way. New cabinets show colour bars so you can see the wall is alive.
2. Right-click any cabinet. The **LED Wall Processor** opens.
3. Pick a discovered NDI source (or type a fragment of the name). Set pitch, brightness, gamma. Pattern → **NDI Video**. **Apply to Wall**.
4. The whole connected plan plays the feed. Settings are per-wall and saved with the world.

## Shaped walls

The plan does not have to be a rectangle. Same-kind, same-facing cabinets that share an edge are **one screen** — a cross floor, an L, a staircase of chamfers. Right-click any cabinet and **Apply to Wall** writes the whole group (bounded by the 256-cabinet span). Gaps and a 90° L **without** a [corner cabinet](/blocks/led-panel#90-turns) stay separate walls.

Prefer a short distinctive fragment (`Arena - Composition`) over the full machine-prefixed name. Matching is exact first, then case-insensitive substring, so the short form survives a hostname change.

Need the Runtime first? See [Install](/guide/install). Need OBS or Resolume on the LAN first? See [OBS, Resolume, and the wall](/guide/ndi-software). Walls stay on colour bars if NDI is missing or the source name does not match — [Troubleshooting](/guide/troubleshooting).

## Angled walls

Panels have eight orientations. Facing a cardinal gives a square cabinet; facing between them gives a 45° **chamfer** (a flat cut). Stand square to the angle you want and place on the ground. Clicking a block face still snaps flush to that face.

Build a 45° wall as a staircase — one block diagonally each time — and the cabinets meet corner to corner. A straight run and a 45° wing are **two** walls with their own sources unless a diagonal cabinet bridges them.

For a **smooth 90°** wrap, place an [LED Corner Cabinet](/blocks/led-panel#90-turns) (`ndidisplays:led_corner`) between the two cardinal runs. Craft it from one LED panel. Sneak-place for the inner corner. Two flats in an L with **no** corner stay two screens.
