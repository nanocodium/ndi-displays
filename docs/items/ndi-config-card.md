# NDI Configuration Card

Sneak + right-click in the air to pick a source. Right-click a screen to apply it. Sneak + click two winches to bound a park, then apply source, winch mode (linked / twin), and stitch on/off to the whole selection.

## Registry ID

`ndidisplays:ndi_config_card`

## Crafting

Shapeless: paper + redstone + iron nugget. [Recipes](/reference/recipes).

## Configuration

- Sneak + right-click empty air → source picker (also card GUI).
- Right-click a wall / floor / round / curve / winch tile / [projector](/blocks/projector) / [pro monitor](/blocks/pro-monitor) → apply stored source.
- Sneak + click two [kinetic winches](/blocks/kinetic-winch) → park region; then stitch or full-frame per motor.
- Bind a [Winch Park Monitor](/blocks/winch-park-monitor) the same way.
- Stored source string on a [chain hoist](/blocks/chain-hoist) is treated as a **group name** (right-click the motor).

## NDI behavior

Does not send or receive. Writes source names onto blocks.

## Multiplayer

Card actions sync block data on the server. Every client then pulls the new name.

## Limits

Source string length is clamped (same cap as processor fields). Park selection is axis-aligned between the two clicked motors.

## Integrations

Works with Theatrical-patched screens (you still change the NDI name; DMX source-select is a separate 2ch map).
