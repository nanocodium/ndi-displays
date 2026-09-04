# LED Wall Panel

Slim 1×1 m rental cabinet. Place a rectangle of same-facing panels and they merge into one wall — no support blocks; they hang as if they were rigged.

## Registry ID

`ndidisplays:led_panel`

## Crafting

Shaped recipe, **8** cabinets. See [Recipes](/reference/recipes).

```
III
GRG
III
```

`I` iron ingot, `G` glowstone dust, `R` redstone block.

## Configuration

Right-click any cabinet → **LED Wall Processor**: source, pitch, brightness, gamma, test patterns, input crop. **Apply to Wall** writes the merged rectangle. Full field list: [Video processor](/guide/processor).

Eight facings (cardinals and 45°). [First wall](/guide/first-wall) for placement.

## 90° turns

A true 90° wrap is the **LED Corner Cabinet** (`ndidisplays:led_corner`): one block, a quarter-cylinder of radius 1, tessellated like the [curved screen](/blocks/curved-screen) (~5°). Shapeless craft: **1** LED panel → **1** corner. Place it between two cardinal runs; sneak-place for the inner (concave) form. The corner auto-orients from its neighbours.

The 45° **chamfer** is still the diagonal panel (`DIAGONAL`) — a flat cut, not a curve. Two cardinal flats in an L **without** a corner cabinet stay **two** screens; they do not merge across the gap.

## NDI behavior

**Receive only.** Pulls the named NDI source on each viewing client. No sender.

## Multiplayer

Every client with the Runtime pulls the feed itself. Server stores processor settings only. See [Multiplayer](/guide/multiplayer).

## Limits

- Merge span **256** blocks (`WallScanner.MAX_SPAN`).
- Native feed capped at **3840×2160** per source ([Native resolution](/reference/native-resolution)).
- Adjacent same-facing panels only; a 45° wing is a **second** wall unless a diagonal chamfer joins them.
- A 90° L without a [corner cabinet](#90-turns) is two walls.
- Emits light level 10. Emissive shader ignores world lighting.

## Integrations

- **Shimmer** (optional): bloom from the live feed.
- **Theatrical** (optional): 2-channel screen DMX — dimmer + source select. [DMX maps](/kinetics/dmx).
