# Video processor

Every screen (wall, floor, round, curve, kinetic tile) can crop an **input window** out of the incoming frame — full frame or a sub-rectangle — so one NDI source can feed several walls with different cuts. The [projector](/blocks/projector) uses a separate lens GUI (throw / keystone), not this processor.

Right-click a cabinet or mount → processor GUI. Putting OBS or Resolume on the LAN: [OBS, Resolume, and the wall](/guide/ndi-software).

## Common fields

| Field | Meaning |
|-------|---------|
| Source | Exact name or substring of an NDI source on the LAN |
| Pattern | Colour bars, alignment grid, RGB/white, pixel checker, or **NDI Video** |
| Pitch | Pixels per block: 512, 384, 256, 208, 170, 128, 96, 64, 48, 32 (P2-class through P31) |
| Brightness | Percent (≈ nits), linear light |
| Gamma | 1.8–2.8 |
| Input window | Full frame or crop (see native size in the GUI) |

**Apply to Wall / Floor** copies the settings to the whole connected group (rectangle or shaped plan).

## What the shader simulates

The wall is not a quad with a video texture. A dedicated core shader models a real LED processor:

- Per-LED point sampling, LOD like a hardware scaler
- Vertical R | G | B subpixel stripes
- Black bezel + energy compensation
- Per-LED brightness variance (uncalibrated modules)
- Structure fades out below ~1 LED per screen pixel (no moiré)
- Emissive surface: ignores world lighting. Cabinets do **not** emit vanilla block light
- Optional [Shimmer](/reference/integrations) bloom from the live feed
- Optional Shimmer **screen lights** (colour wash on the floor) stay off unless `-Dndidisplays.screenLights=true`

Example: a 10×5 m wall at P3.9 (`256` px/block) is **2560×1280**.

Native size and “fits one feed” advice: [Native resolution](/reference/native-resolution).
