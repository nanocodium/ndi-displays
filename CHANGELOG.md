# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.3] - 2026-09-29

Fourth CurseForge release. Ship **`ndidisplays-1.20.1-1.1.3-all.jar`**. Fixes the white camera feeds seen with Theatrical Extra Lights 1.4.x volumetric beams.

### Added

- **Camera operator view**: a **Take control** button in the camera block's settings puts your view at the lens. The mouse pans and tilts, the scroll wheel zooms, Esc hands the camera back. The pan, tilt and zoom sliders now move the shot live, without pressing Apply.
- **Shoulder rig settings** (`ndidisplays:shoulder_camera`): sneak + right-click with the rig worn or held opens the block cameras' settings screen — NDI source name, Live switch, resolution (540p / 720p / 1080p), frame rate (24 / 30 / 60 fps) and zoom. No pan or tilt: the rig follows where you look. Settings live on the item, so they travel with it. **V** toggles operator mode (viewfinder overlay; keybind under *NDI Stage Displays*), and the scroll wheel zooms the lens while operating.
- Curved screen: a **distance** slider puts the arc's centre up to 512 m in front of the mount, and a **height offset** raises or lowers the arc up to 256 m without moving the mount.
- `-Dndidisplays.debugFlownFixtures` logs how fixtures flown on hoists and winches are tracked and drawn; a ghost renderer that throws is reported once instead of silently dropping the fixture.

### Changed

- Curved screen radius and height reach 256 m and the sphere screen diameter 512 m, on logarithmic sliders so the small end stays precise. Both get a denser mesh and a wider view distance, so a stadium-sized screen stays drawn from across the map.
- The broadcast camera's feed is captured from the front of its articulated blue lens, so the shot matches where the model points.

### Fixed

- **Camera feeds no longer blow out to white when Theatrical Extra Lights' volumetric beams are on.** Extra Lights draws its raymarched beams through one renderer shared by every fixture in view. A camera capture left that renderer's beam counter and scene-depth copy mid-flight, so beams piled up and were drawn against the other view's depth: a saturated white wash on the feed, a magenta ghost of the viewfinder image on the player's screen. Captures now reset the pass on both sides.
- Fixtures flown on chain hoists and kinetic winches (Theatrical and Extra Lights): beams glide with the truss instead of jumping at block boundaries; pan, tilt, gobo, zoom and colour wheel stay live in flight, because the proxy now syncs its full NBT; beam length is measured from the drawn body each tick rather than Theatrical's whole-block cell; landing keeps the fixture's live look.
- LED wall settings (processor config, NDI card, crop window) apply to the whole merged screen across 90° corners and chamfers instead of stopping at the bend.
- After a shoulder-rig capture, or when a projector first created its shadow target, the game kept rendering into the wrong framebuffer: no sky clear, old frames smearing into streaks, world pixels painted over the pause menu. Both now hand the screen back.
- A compat hook failing during capture setup (for example on a client without Shimmer) could leave the game rendering into the camera's buffer, at the camera's size, for good. Setup now runs inside the capture's own cleanup, and Shimmer is only touched when it is installed.
- Operator view on the camera block: head direction, eye height and mouse jitter.
- Shoulder rig: the **V** key was never registered (Forge did not dispatch the mode's own event subscribers), and with operator mode on the rig's zoom overrode other cameras' zoom in their feeds.

## [1.1.2-beta.1] - 2026-09-17

Third CurseForge beta. Ship **`ndidisplays-1.20.1-1.1.2-beta.1-all.jar`**.

### Added

- **Spherical LED screen** (`ndidisplays:sphere_screen`) — one mount drawing a video globe of configurable diameter (0.5–512 m). The source wraps round it as an equirectangular map (frame centre on the facing side, seam at the back); native size is the unrolled 2:1 surface at the chosen pitch. Same processor GUI as the round screen, plus crop, NDI card and Theatrical 2ch DMX.
- Curved screen: **Mount: hidden** option makes the centre hub invisible (small hitbox stays so it can be reopened), leaving only the video arc.
- **LED inner corner** (`ndidisplays:led_inner_corner`) — concave quarter-cylinder for in-corners. Same block as the outer cabinet (`convex=false`). Swap recipes; sneak + empty hand flips a placed one.
- Apple Silicon Macs get live NDI: the bundled Devolay binding now includes a macOS arm64 native, built by the `Build Devolay macOS arm64` workflow and repacked into `thirdparty/devolay-2.1.0.1.jar`.
- Camera range is configurable: `cameraRange` (blocks) and `cameraRangeUnlimited` in the client config, with a slider and switch on the mod's options page. Cameras past the range stop sending until the player returns.
- **Active Cam** (2D Spidercam): four Camera Winches, gondola, controller, FPV and drone-style waypoints.
- Wiki **For developers** (`docs/devs/`): contributing, Keep a Changelog rules, interop contract for Theatrical / Extra Lights / SEF. Root `CONTRIBUTING.md` points at it.

### Fixed

- Stale `led_corner_panel` loot table (and its orphaned blockstate / models) removed: it named an item that no longer exists and logged a loot-table parse error on every world load. The corner cabinet's real loot table (`led_corner`) already drops the outer or inner corner item.
- Two flat runs meeting at a convex 90° corner merge into one screen (hard fold) when their cabinets touch at the shared back corner; previously cardinal-to-cardinal turns were refused and such an L was always two walls. Back-to-back (180°) flats still never merge.
- Chamfers and corner cabinets join the flats they physically touch. The wall scanner idealised a flat cabinet's face at the *front* edge of its block, 0.875 m in front of the real 2/16 slab at the back, so a bend only chained when the next cabinet was placed a block away from the one it continued, and a closed ring drew once per cabinet (every panel took itself for the anchor) and z-fought. The face is now the cabinet's own back edge, and a closed ring is canonicalised so all its panels agree on one anchor. Corner cabinets' quarter-arcs start where the neighbouring flat's cabinet ends, one cell over from before.
- Bending walls draw each column on its cabinet's screen plane — flats and chamfers a slab in front of their back edge / diagonal (the chamfer slab now runs forward from the diagonal instead of straddling it), arcs on the quarter-round — with the joins mitred and the cabinet wedge behind each mitre filled in, so the picture is continuous around a bend instead of floating 0.875 m ahead of the flats and hiding inside the chamfers.

## [1.1.1-beta.1] - 2026-09-06

Second CurseForge beta. Ship **`ndidisplays-1.20.1-1.1.1-beta.1-all.jar`**. Includes [nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays) `main` through `150b536` (their post-1.1.0-beta.1 work was not in their changelog).

### Added

- Wiki guide [OBS, Resolume, and the wall](docs/guide/ndi-software.md): DistroAV output, in-game source patch clip, Resolume Arena NDI.
- Sculpted **shoulder rig** mesh (worn + item): pad on the shoulder, screen at the eye, live NDI on the monitor in third and first person.
- Sculpted **blow-through cabinet** mesh: rail profiles, strip bars behind the video face, rear brace. Dedicated Shimmer MRT bloom shader for the transparent wall.
- Dedicated **broadcast camera** item mesh so the inventory slot is no longer empty.

### Changed

- First person shows the whole shoulder rig, not only the finder. The monitor copy is opaque (sky horizon had alpha 0).
- Blow-through picture is brighter and taller against the cabinet bars; coverage ~60%; bloom goes through the transparent MRT, not a solid-wall pass.
- Palette atlas strips are 16 px tall so the block atlas keeps its full mip chain.
- Rack units are 12% wider and deeper and grow to the slot pitch, filling the frame instead of floating in it.
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

[Unreleased]: https://github.com/nanocodium/ndi-displays/compare/1.1.3...HEAD
[1.1.3]: https://github.com/nanocodium/ndi-displays/compare/1.1.2-beta.1...1.1.3
[1.1.2-beta.1]: https://github.com/nanocodium/ndi-displays/compare/1.1.1-beta.1...1.1.2-beta.1
[1.1.1-beta.1]: https://github.com/nanocodium/ndi-displays/compare/1.1.0-beta.1...HEAD
[1.1.0-beta.1]: https://github.com/nanocodium/ndi-displays/compare/32d691b...1.1.0-beta.1
[1.0.0]: https://github.com/nanocodium/ndi-displays/commit/32d691b
