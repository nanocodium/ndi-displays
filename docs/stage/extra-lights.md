---
title: Extra Lights
outline: deep
---

# Theatrical Extra Lights

<span class="stage-badge">Companion</span>

Extra Lights owns the **fixture catalog** — moving heads, wash, strobes, lasers, gobos. This page is only how those machines sit on an NDI Displays stage.

<div class="stage-callout">
<p><strong>Not a dump.</strong> Fixture recipes, DMX personalities, and gobo plates live with Extra Lights. If a page here starts listing every head, it has left its job.</p>
</div>

## What you get when both are loaded

| You do this | What happens |
|-------------|--------------|
| Hang an Extra Lights block on a [kinetic winch](/blocks/kinetic-winch) | Payload `PAYLOAD_FIXTURE`. The winch flies the body; the beam can go through Extra Lights’ volumetric renderer. |
| Fly a truss of fixtures on a [chain hoist](/blocks/chain-hoist) | Patch stays on the address it took off from. Heads keep the cue in the air. Beams re-trace so they do not punch the floor on the way up. |
| Leave Extra Lights at home | Classic Theatrical cone (or no beam). Winches and hoists still move. |

Theatrical is the DMX bus. Extra Lights is the look. NDI Displays never re-implements either.

## Hang one fixture

On the winch GUI: payload **Theatrical fixture**, then the Extra Lights block id. Nested modes add channels at the tail only — [payloads](/kinetics/payloads), [DMX](/kinetics/dmx).

If the hung fixture publishes Theatrical personalities, those names and slot maps win over the generic 4 / 7 / 10 ch footprints.

LED tile and slat stay **video**. A flown head is **light**, not an NDI surface.

## Fly a whole hang

A hoist load is real blocks. Extra Lights fixtures on that island stay live because the hoist installs a stand-in DMX consumer for the flight (`HoistFixtureCompat`). You do not Apply each machine again after landing.

The volumetric beam uses the same Extra Lights pipeline as a fixture still in the world. Rake tilts the **bodies**; pan / tilt still come from the head.

## Volumetric beams

When Extra Lights is present and its volumetric path is on, a flown fixture submits `BeamRenderData` into `VolumetricBeamRenderer` — the same raymarched shaft Extra Lights uses on its own blocks. Reflection only: Extra Lights is not a compile dependency. If the class is missing, renamed, or refuses the beam, the classic cone draws instead.

| Gate | Default | Meaning |
|------|---------|---------|
| Extra Lights installed | — | Resolves `com.github.dumann089.theatricalextralights.client.render.beam.*` |
| Extra Lights `isVolumetricBeamEnabled()` | on | Extra Lights’ own switch; we honour it |
| `-Dndidisplays.volumetricBeam` | `true` | NDI Displays escape hatch — `false` forces the classic cone |

Submit builds the beam in **fixture-local** space (lens in the head matrix, travel along −Z), then Extra Lights’ lazy pass re-anchors it camera-relative. A world-space origin double-counts the position and throws the shaft away from the light — that is why the hook does not pass absolute coordinates.

Open gobo plate `theatricalextralights:textures/gobos/generic_1/open.png` is required: Extra Lights feeds `goboTexture` into the render type. A flown winch fixture has no inserted gobo, so it uses the same empty plate Extra Lights uses on its own open heads.

Half-angle is 5°–25° from focus (zoom), not derived from beam width. A near-zero tangent reads as a laser too thin to see.

Three consecutive submit failures latch off for the session and fall back. One transient miss (shader reload) does not.

Code: `compat/theatrical/ExtraLightsBeamHooks` via `TheatricalCompat.submitFixtureBeam`.

## What this wiki will not list

- Extra Lights registry ids, recipes, or a fixture index
- Gobo libraries, prism / frost / shutter tables
- How to patch Extra Lights from a console

Those belong on Extra Lights. Here: [winch](/blocks/kinetic-winch), [hoist](/blocks/chain-hoist), [DMX maps](/kinetics/dmx), [config flag](/reference/config#jvm-flags-client).

Authors adding another lighting mod: [Interop](/devs/interop) — hang on our hook, keep your renderer, do not copy our cabinets.
