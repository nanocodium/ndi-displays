# Web Terminal

![Web terminal](/img/blocks/web_terminal.png)

A dedicated browser box that renders a page and puts it on the network as an NDI source. Publishing as NDI rather than a bespoke texture means every LED wall, floor, and kinetic tile can take it.

For a full desk OS (notes, paint, NDI monitor) plus an optional Browser app, use the [Computer](/blocks/computer). For a 1U browser in a bay, use the [equipment rack](/blocks/equipment-rack) web module.

## Registry ID

`ndidisplays:web_terminal`

## Crafting

See [Recipes](/reference/recipes) (`web_terminal.json`).

## Configuration

Right-click → URL, source label, resolution. The browser runs on the **broadcast** client.

## NDI behavior

**Send.** Default `MC Web x,y,z`, or `MC Web <label>` when a label is set.

## Multiplayer

Needs `broadcast.mode` that actually publishes ([Multiplayer](/guide/multiplayer)). Other clients only need to receive that NDI name on walls.

## Limits

**MCEF is required** for the browser. Without it the block places but the page does not render. Capture cost is on the operator GPU, like a camera.

## Integrations

**MCEF** (optional at load, required for function). Walls then receive it like any other source.
