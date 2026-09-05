# Blow-Through LED Panel

![Blow-through LED panel](/img/blocks/blow_through_panel.png)

Mesh / see-through cabinet for flying in front of a lighting rig. Dimmer than a solid module (less emitter area) and it does not block light or sight.

Same processor, same NDI feed, same merge rules as the [solid wall](/blocks/led-panel).

## Registry ID

`ndidisplays:blow_through_panel`

Shares the `led_panel` block entity type with the solid cabinet.

## Crafting

See [Recipes](/reference/recipes) (`blow_through_panel.json`).

## Configuration

Same **LED Wall Processor** as the solid panel: source, pitch, brightness, gamma, patterns, crop. Solid and mesh cabinets **do not** merge with each other.

## NDI behavior

**Receive only.** Same matching rules as LED Wall Panel.

## Multiplayer

Client-side receive. Settings saved on the server with the world.

## Limits

- Merge span **256**.
- Light level **6** (solid panel is 10).
- `noOcclusion`, not view-blocking, not suffocating.
- Native feed 3840×2160 cap.

## Integrations

Shimmer bloom and Theatrical 2ch screen DMX, same as the solid wall.
