# Changelog

This page follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The same notes live in [`CHANGELOG.md`](https://github.com/nanocodium/ndi-displays/blob/main/CHANGELOG.md) at the repo root.

Current artifact: **`ndidisplays-1.20.1-1.1.0-beta.1-all.jar`** — Minecraft **1.20.1**, Forge **47.x**, Java 17.

## [Unreleased]

### Added

- [OBS, Resolume, and the wall](/guide/ndi-software) — DistroAV output, in-game source patch clip, Resolume Arena NDI.

## [1.1.0-beta.1] - 2026-09-04

First public CurseForge beta. Everything in this jar.

### Added

- [Chain Hoist](/blocks/chain-hoist) — flies an isolated island of real blocks. Each motor has its own chain (rake); group / remote moves keep the attitude. Theatrical fixtures stay patched in flight.
- [Hoist Remote](/items/hoist-remote) — e-stop, groups, UP / STOP / DOWN.
- `/hoist at` and `/hoist group` (permission 2).
- Server caps in [`ndidisplays-common.toml`](/reference/config#hoist).
- Tags `#ndidisplays:hoist_world` and `#ndidisplays:hoist_immovable`.
- LED corner cabinet in the creative tab ([LED Wall](/blocks/led-panel#90-turns)).
- This VitePress wiki (`docs/`) and a root [`CHANGELOG.md`](https://github.com/nanocodium/ndi-displays/blob/main/CHANGELOG.md).
- [LED Wall](/blocks/led-panel), [blow-through](/blocks/blow-through-panel), [floor](/blocks/led-floor) — merge into one processor canvas, including non-rectangular plans ([First wall](/guide/first-wall#shaped-walls)).
- [Round](/blocks/round-screen) and [curved](/blocks/curved-screen) mounts.
- [Video Projector](/blocks/projector) — drapes NDI onto world geometry (throw, keystone, shift, shadows).
- [Computer](/blocks/computer) — native OS, `MC Computer <name>`. Browser app needs [MCEF](/reference/integrations).
- [Vision Switcher](/blocks/vision-switcher) — eight inputs, PGM / PVW, CUT / AUTO, `MC Switcher <name>`.
- [Pro Monitor](/blocks/pro-monitor) — single-feed desk panel.
- [Equipment Rack](/blocks/equipment-rack) — six 1U slots, PDU power, rack router / web module.
- [NDI Router](/blocks/ndi-router), [Multiview](/blocks/multiview), [Winch Park Monitor](/blocks/winch-park-monitor), [Web Terminal](/blocks/web-terminal).
- [NDI Configuration Card](/items/ndi-config-card).
- [Cameras](/blocks/cameras) (`MC Cam|PTZ|Jib|Dolly`), [handheld](/items/handheld-camera), [shoulder](/items/shoulder-camera), [drone](/items/drone). Dolly column 0–3 m.
- [Kinetic LED Winch](/blocks/kinetic-winch) and [payloads](/kinetics/payloads).
- Articulated meshes; [broadcast mode](/guide/multiplayer); optional Shimmer / Theatrical.

### Changed

- Corner facing snaps only when both L wings already join.
- Path walls bake the same UV strips the world mesh uses (Iris / Oculus).
- Screens no longer emit vanilla block light. Optional Shimmer **screen lights** stay off unless `-Dndidisplays.screenLights=true` ([Config](/reference/config#screen-lights)).
- Dolly: Catmull-Rom rail, telescoping column, motion clock that survives lag.

### Fixed

- Corner collision is an L, not a full cube.
- Quarter-arc meets adjacent flats without a seam.
- Path-wall Shimmer bloom is one mesh, not a stack of chord quads.
- Video faces stop z-fighting cabinets at range.
- Projector beam starts at the lens; computer music dies with the machine; rack fronts follow the frame.

## [1.0.0] - 2026-09-03

Upstream `main` at `32d691b` before this beta. Notes for that tree now live under [1.1.0-beta.1].

[Unreleased]: https://github.com/nanocodium/ndi-displays/compare/1.1.0-beta.1...HEAD
[1.1.0-beta.1]: https://github.com/nanocodium/ndi-displays/compare/32d691b...HEAD
[1.0.0]: https://github.com/nanocodium/ndi-displays/commit/32d691b
