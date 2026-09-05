# Video Projector

![Video projector](/img/blocks/projector.png)

Not a cabinet: a **light source with an image in it**. The block is the chassis; the picture is draped onto whatever world geometry the frustum hits — occlusion included — the way a real projector hits a cyc or a building.

Placement aims it the way the player is looking (yaw and pitch). Fresh units come up on the **alignment grid**. Right-click opens the lens GUI.

## Registry ID

`ndidisplays:projector`

## Crafting

See [Recipes](/reference/recipes) (`projector.json`). Iron, glass, LED panel.

## Configuration

| Field | Meaning |
|-------|---------|
| Source | NDI name or substring (same matching as walls) |
| Pattern | Alignment grid by default; switch to **NDI Video** when the throw is framed |
| Yaw / pitch | World-absolute aim. Pitch ±85° |
| FOV | Vertical lens angle, 10–120° (default 40°) — this is the zoom |
| Aspect | Default 16:9 |
| Near / far | Throw. Far is also the geometry-scan budget, **2–64** m (default 24) |
| Keystone H / V | Image-plane tilt, ±0.6 |
| Lens shift H / V | Fraction of frame, ±1.0 |
| Brightness | 0–1 (default 0.9) |
| Feather | Soft-edge width, up to 0.4 of the frame |
| Additive | Overlap blend: add light (`true`, default) or replace |
| Show frustum | Calibration wireframe |

The [NDI Configuration Card](/items/ndi-config-card) can write the stored source onto the projector.

## NDI behavior

**Receive.** The drape is a world-space mesh, not an LED processor canvas. Shadows use a 2048 map (depth bias `-Dndidisplays.projectorShadowBias`, default `0.06` m). The beam starts at the chassis lens and opens at the frustum angles.

## Multiplayer

Every viewing client rebuilds the drape locally. Source name lives on the server.

## Limits

- Far throw is a performance budget: long throws scan more geometry.
- Does not merge with LED walls. One projector, one frustum.
- Needs the NDI Runtime on the viewer for live video (patterns still draw without it).

## Integrations

None required. [Shimmer](/reference/integrations) bloom does not apply (this is not an LED mesh).
