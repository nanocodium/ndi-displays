# Integrations

All optional. The mod loads without them.

| Mod | Side | What you get | Without it |
|-----|------|----------------|------------|
| [Shimmer](https://www.curseforge.com/minecraft/mc-mods/shimmer) `1.20.1-0.2.0+` | Client | Live wall/floor/curve content in the bloom pipeline | Still video, no extra bloom |
| [Theatrical](https://github.com/theatricalmod/Theatrical) | Both | Winches and screens as DMX consumers; Extra Lights fixtures on a winch hook; fixtures on a [chain hoist](/blocks/chain-hoist) stay patched and keep their beams in flight | Manual winch GUI only; hoist still flies generic blocks |
| SEF (Stage Entertainment Furnitures) | Both | Speakers and line arrays fly with wrench poses; mixer / processor patches remapped after landing. **Official jar — no fork** | Hoist still flies everything else; audio routing must be re-patched by hand |
| Xaero Minimap / World Map | Client | Import world waypoints into a drone path | Path GUI without the import button |
| MCEF | Client | [Web Terminal](/blocks/web-terminal) browser + NDI send | Block places; no page / no `MC Web` source |

`mods.toml` marks shimmer, theatrical, xaerominimap, and xaeroworldmap as **non-mandatory**. MCEF is a dev/runtime extra (not listed there); see the repo `libs/` note.

DMX channel maps: [DMX](/kinetics/dmx).
