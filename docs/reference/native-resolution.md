# Native resolution

`NativeResolution` turns panel count, disc radius, or curve wrap plus **pitch** into a pixel size an operator can type into Resolume or OBS.

Advice is snapped to GUI pitches: **512, 384, 256, 208, 170, 128, 96, 64, 48, 32** px/block.

## One-feed cap

Largest frame worth asking a single NDI source to produce:

- `MAX_FEED_WIDTH` = **3840**
- `MAX_FEED_HEIGHT` = **2160**

Past that, NDI / encoders / GPU surfaces start refusing. If native size exceeds this, the GUI suggests a **coarser** pitch that still fits (never finer).

Recommended source size is rounded **even** (encoders and NDI want even dimensions).

## Formulas (conceptually)

- **Flat wall / floor:** pixels per cabinet × cabinet count.
- **Round disc:** diameter in blocks × pitch.
- **Curve:** arc length × height × pitch (slab thickness 0.12 does not add pixels).

The processor GUI prints native size and whether it fits one feed. Details: [Video processor](/guide/processor).
