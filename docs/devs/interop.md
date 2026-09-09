# Interop & API

For authors of **Theatrical**, **Theatrical Extra Lights**, **SEF**, and anything else that shares a stage with NDI Displays.

The ask we keep getting: *“send me NDI Displays — or an API — so we work together, not against each other.”*

**Source:** [github.com/nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays) (MIT). Clone it. Do not re-implement our receivers, senders, or LED processor in your lighting mod.

**Talk first:** open a GitHub issue tagged `interop` before you add a second LED wall, NDI projector, or hoist. We would rather add a hook than ship two competing cabinets.

There is **no Maven API artifact yet**. The contract below is what other mods should use today. If you need a stable Java API jar, say so on that issue — we will design it *with* you.

## Who owns what

| Layer | NDI Displays | Lighting / furniture mods |
|-------|----------------|---------------------------|
| Live NDI in/out, LED cabinets, projector, cameras, router, switcher | Yes | Do not duplicate |
| Moving heads, gobos, beams, strobes | Soft-compat only | Your fixtures |
| Speakers / line arrays | Soft-compat on hoist | Official SEF jar |
| Flying real blocks (chain hoist) | Yes | Tag your blocks; do not write a second hoist |

We already soft-depend on Theatrical, Extra Lights (via Theatrical fixtures), Shimmer, Xaero, and MCEF. None of them are required to load. See [Integrations](/reference/integrations).

## Do

- Depend on us as **`optional`** in `mods.toml` (`ndidisplays`). Call `ModList.get().isLoaded("ndidisplays")` before touching our classes.
- Keep your own renderer for **lights**. Hang Extra Lights / Theatrical heads on our [kinetic winch](/blocks/kinetic-winch) hook or fly them on a [chain hoist](/blocks/chain-hoist) — we already keep DMX consumers alive in flight (`compat/theatrical`).
- Add your immovable machinery to `#ndidisplays:hoist_immovable` (datapack tag, `replace: false`) so a hoist never picks up a grid motor or a world anchor.
- Use our **NDI source names** as strings. Cameras and desks publish on the LAN; your GUI can list them the same way OBS does. Defaults: `MC Cam|PTZ|Jib|Dolly <pos>`, `MC Handheld <player>`, `MC Shoulder <player>`, `MC Drone <id>`, `MC Computer <name>`, `MC Switcher <name>`, `MC Rack Router <pos> U<n>`.
- Patch screens and winches with Theatrical’s card using the maps in [DMX](/kinetics/dmx). Do not invent a second Art-Net stack for the same block.

## Do not

- Bundle Devolay / `libndi` / a second NDI Runtime loader.
- Copy LED panel / projector / camera block entities into Extra Lights or Theatrical “so the pack works without NDI Displays”.
- Fork SEF or Theatrical into this repo or yours to make hoist work. Official jars only.
- Break NBT or registry ids of `ndidisplays:*` without a changelog entry and a migration.

## Hooks that exist today

| Surface | Where | What you get |
|---------|--------|----------------|
| Mod id | `ndidisplays` | `NdiDisplays.MODID` |
| Blocks / items | `NdiDisplays.java` | Registry objects — see [block catalog](/blocks/) |
| Hoist cargo | `#ndidisplays:hoist_immovable`, `#ndidisplays:hoist_world` | Datapack tags. World = terrain the scanner will not enter. Immovable = never cargo (include your motors). |
| Theatrical on hoist | `compat/theatrical/HoistFixtureCompat` | Proxy DMX consumers while the load is an entity; beams keep running. |
| SEF on hoist | `compat/sef/SefHoistCompat` | Pose / mixer remap after landing. |
| Winch + Extra Lights | Kinetic hook payload | Fixture hung on the winch; optional volumetric beam path. |
| Screen lights | `-Dndidisplays.screenLights=true` | Optional Shimmer wash. Off by default. |

There is no Inter-Mod Comms channel yet. If you need `enqueueTo` / `enqueueFrom`, propose the message shape on the `interop` issue instead of guessing.

## Suggested `mods.toml` (your mod)

```toml
[[dependencies.yourmod]]
modId = "ndidisplays"
mandatory = false
versionRange = "[1.1.0-beta.1,)"
ordering = "NONE"
side = "BOTH"
```

Load order should stay `NONE` unless you register into our registries (you should not).

## Working together

1. Open [an issue](https://github.com/nanocodium/ndi-displays/issues) with **your mod id**, **what you need** (hang, DMX, hoist tag, shared NDI name list, …), and **what you will not build**.
2. We add a narrow hook or a tag. You stay optional.
3. Both changelogs mention the hook so pack makers know the pair is supported.

If you only need the jar to test: CurseForge **`-all`** artifact, or `gradlew build` from this repo. Wiki for players stays at [wiki.nailec.fr](https://wiki.nailec.fr).
