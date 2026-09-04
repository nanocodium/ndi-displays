# Equipment Rack

A 19-inch frame with **six 1U slots**. Units are **items**: carry a web module across the stage, seat it, pull it back out. Each seated unit keeps its own config in the slot (a web module its URL, a PDU its breaker).

**Power is the game.** The rack runs only while it holds a PDU that is switched **on**. No PDU, or PDU off, and every screen in the rack goes dark.

Units rotate with the frame's facing.

## Registry ID

`ndidisplays:equipment_rack`

## Crafting

Frame: [Recipes](/reference/recipes) (`equipment_rack.json`). Units are separate creative-tab items (no extra crafts — take them from the tab).

| Item | Role |
|------|------|
| `web_module` | Browser page on the face — needs [MCEF](/reference/integrations). Click the seated unit to open the terminal |
| `rack_router` | Same idea as the [NDI Router](/blocks/ndi-router) block: stable output name, NDI route |
| `rack_pdu` | Power. Click the seated unit to flip the breaker |
| `rack_switch` | Faceplate (network switch) |
| `rack_patch` | Faceplate (patch panel) |
| `rack_recorder` | Faceplate (recorder) |
| `rack_sync` | Faceplate (sync generator) |
| `rack_blank` | Blank 1U |

## Configuration

- Right-click a slot with a unit item → seats it there, or in the lowest free slot if that one is taken.
- Sneak + empty hand on a seated unit → pulls it back out as an item.
- Bare click on a PDU → breaker. Bare click on a web module → terminal GUI.
- Breaking the rack **drops every seated unit**.

## NDI behavior

The **rack router** publishes a stable output name (broadcast client only). Default if you leave the field blank: `MC Rack Router <pos> U<n>`. The **web module** can send a page the same way the [Web Terminal](/blocks/web-terminal) does, once MCEF is present and the PDU is on.

## Multiplayer

Seating, power, and unit NBT are server-side. NDI send still follows [broadcast mode](/guide/multiplayer).

## Limits

- Six slots. One PDU can power the whole frame.
- Decorative units (switch, patch, recorder, sync, blank) have no NDI I/O.
- Without MCEF the web module is a dark faceplate.

## Integrations

**MCEF** — web module only. The floor [NDI Router](/blocks/ndi-router) does not need a rack.
