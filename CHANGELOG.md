# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Upstream [nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays) `main` through `150b536` (merged here). Their post-1.1.0-beta.1 work was not in their changelog; it is recorded below.

### Added

- Wiki guide [OBS, Resolume, and the wall](docs/guide/ndi-software.md): DistroAV output, in-game source patch clip, Resolume Arena NDI.
- Sculpted **shoulder rig** mesh (worn + item): pad on the shoulder, screen at the eye, live NDI on the monitor in third and first person.
- Sculpted **blow-through cabinet** mesh: rail profiles, strip bars behind the video face, rear brace. Dedicated Shimmer MRT bloom shader for the transparent wall.
- Dedicated **broadcast camera** item mesh so the inventory slot is no longer empty.

### Changed

- First person shows the whole shoulder rig, not only the finder. The monitor copy is opaque (sky horizon had alpha 0).
- Blow-through picture is brighter and taller against the cabinet bars; coverage ~60%; bloom goes through the transparent MRT, not a solid-wall pass.
- Palette atlas strips are 16 px tall so the block atlas keeps its full mip chain.
- Creative tab icon is the NDI configuration card (the LED panel read as empty on the dark tab).

### Fixed

- Chain hoist: Theatrical fixtures (and screens / winches on the same load) stay patched after detach. Place was loading NBT after `setLevel`, so the DMX consumer never re-joined the network.
- Broadcast camera item inherited a particles-only block model and vanished in the inventory / JEI.
- Shoulder first-person finder no longer hides the rest of the worn rig.

## [1.1.0-beta.1] - 2026-09-04

First public CurseForge beta. Ship **`ndidisplays-1.20.1-1.1.0-beta.1-all.jar`**.

### Added

- **Chain hoist** (`ndidisplays:chain_hoist`) — a stage motor that flies an isolated island of real blocks (truss, scenery, Theatrical fixtures, LED cabinets, SEF). Each motor runs its own chain, so raising one corner rakes the hang. A group command or remote keeps the attitude. Theatrical fixtures stay patched and keep their beams in flight.
- **Hoist remote** (`ndidisplays:hoist_remote`) — yellow belly-box: latched e-stop, group selector, UP / STOP / DOWN, pick-up / set down. Reach 192 blocks, same build and claim rules as the pendant.
- **`/hoist at`** and **`/hoist group`** (permission 2) for cue-style operation from the console or a function file.
- Server caps in `config/ndidisplays-common.toml` under `[hoist]` (`maxBlocks`, bounding box, `maxChainLength`, `maxTiltDegrees`, `maxMotorsPerRig`). Hitting a cap is a fault, never a partial lift.
- Block tags `#ndidisplays:hoist_world` and `#ndidisplays:hoist_immovable` so terrain and immovable furniture are never cargo.
- LED corner cabinet (`ndidisplays:led_corner`) registered in the creative tab, with a shapeless recipe from a flat panel.
- VitePress wiki under `docs/` (block catalog, recipes, config, troubleshooting) plus this file at the repo root.
- **LED wall / blow-through / floor** cabinets that merge into one processor canvas (pitch, gamma, bezels, subpixels, crop window). Merge span 256. Same-kind neighbours stitch even when the plan is not a clean rectangle (crosses, L-runs, stairs). Shaped flood-fill caps at 8192 tiles.
- **Round** and **curved** LED mounts (radius, opening angle, 360° column, concave / convex).
- **Video projector** (`ndidisplays:projector`) — not a cabinet: a lens that drapes NDI onto world geometry (FOV, throw 2–64 m, keystone, lens shift, feather, additive overlap, frustum wireframe, 2048-map shadows). Fresh units come up on the alignment grid.
- **Computer** (`ndidisplays:computer`) — placeable desk OS drawn natively (Notes, Files, Paint, Images, Music, NDI Monitor, Terminal, Settings). Publishes `MC Computer <name>` at 480p / 720p / 1080p. Owner lock is server-side. Optional **Browser** app needs MCEF.
- **Vision switcher** (`ndidisplays:vision_switcher`) — eight inputs, program / preview, CUT and AUTO (mix / dip / wipe, 0.5 / 1 / 2 s). Output `MC Switcher <name>` is composited on the broadcast GPU. Server owns the buses.
- **Pro monitor** (`ndidisplays:pro_monitor`) — single-feed desk panel (source + brightness). Sibling of the multiview.
- **Equipment rack** (`ndidisplays:equipment_rack`) — six 1U slots. Units are items (web, PDU, switch, patch, recorder, sync, blank, rack router). The rack runs only while a PDU is seated and on. Default router name `MC Rack Router <pos> U<n>`.
- **NDI router** and **rack router** — stable output name, NDI route (no decode).
- **Multiview** (2×2 / 3×3) and **Winch Park Monitor**.
- **Web Terminal** — full-page browser as `MC Web <label>` (MCEF on the broadcast client).
- **NDI Configuration Card** — pick a source, apply to screens / projector / pro monitor, bound a winch park, stitch / linked / twin.
- **Broadcast, PTZ, jib, track dolly**, handheld, shoulder, and **NDI drone** (FPV, waypoints, optional Xaero). Default names: `MC Cam|PTZ|Jib|Dolly <pos>`, `MC Handheld <player>`, `MC Shoulder <player>`, `MC Drone <id>`. Dolly column 0–3 m.
- **Kinetic LED winch** — trapezoidal fly, LED tile / slat / sphere / mirror / Theatrical fixture, park stitch, optional DMX.
- Articulated OBJ meshes (cameras, drone, winch, projector beam, desk PC, router, terminal, switcher 16:9 panels, rack units) with palette atlases.
- Client `broadcast.mode` (`AUTO` / `ALWAYS` / `NEVER`) so one machine publishes on a dedicated server.
- Optional Shimmer bloom; optional Theatrical DMX on winches and screens.

### Changed

- LED corner placement only snaps facing when both wings of the L already join (score ≥ 2), so a single neighbour no longer rotates the wrap onto the wrong cell.
- Path walls (including 90° corners) bake the same UV strips the world mesh uses, so Iris / Oculus no longer flatten the turn into an AABB.
- LED walls no longer emit vanilla block light. The shader stays emissive (ignores world lighting).
- Content-coloured **screen lights** (Shimmer wash on the floor in front of a wall) exist but stay **off** unless `-Dndidisplays.screenLights=true`.
- Dolly rides a Catmull-Rom rail (leans into bends). Open runs ping-pong; closed rings loop. Motion clock survives server lag; the column telescopes with the model.
- Capture path is Fabulous-safe and budgeted (round-robin live rigs).

### Fixed

- Corner collision is an L-shaped voxel, not a full cube.
- Quarter-arc tessellation pins first and last vertices to the scanner endpoints, so the curve meets adjacent flats without a seam.
- Shimmer bloom for path walls is one mesh submit instead of dozens of chord quads (that read as a second screen and froze the client).
- Wall scanner no longer treats a cell as a neighbour of itself when scoring endpoints.
- Video faces depth-biased so they stop z-fighting their cabinets at range.
- Projector beam starts at the chassis lens and opens at the frustum angles; image samples at full resolution.
- Computer browser surface follows its window; music stops when the machine sleeps. Double-click maximises.
- Rack unit fronts follow the frame facing (geometry-checked, not guessed).
- Shaped / bending walls light and UV from each column's own face instead of a single billboard.

## [1.0.0] - 2026-09-03

Upstream [nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays) `main` at `32d691b` before this beta. Notes for that tree now live under [1.1.0-beta.1].

[Unreleased]: https://github.com/nanocodium/ndi-displays/compare/1.1.0-beta.1...HEAD
[1.1.0-beta.1]: https://github.com/nanocodium/ndi-displays/compare/32d691b...HEAD
[1.0.0]: https://github.com/nanocodium/ndi-displays/commit/32d691b
