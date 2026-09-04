# Winch Park Monitor

Control-room plot of a bound park: motor grid, selection size, live layout. Bind it with the [NDI Configuration Card](/items/ndi-config-card) the same way you select a region.

## Registry ID

`ndidisplays:winch_park_monitor`

## Crafting

See [Recipes](/reference/recipes) (`winch_park_monitor.json`).

## Configuration

Bind a park with the card (two-corner selection). Right-click the monitor for the park GUI.

## NDI behavior

n/a — display of motor layout, not a video sender or receiver.

## Multiplayer

Layout is world data. All players see the same park.

## Limits

Only shows winches in the bound selection.

## Integrations

None required. Useful next to [NDI Router](/blocks/ndi-router) and [Multiview](/blocks/multiview).
