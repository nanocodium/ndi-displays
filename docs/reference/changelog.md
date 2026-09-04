# Changelog

This page follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The same notes live in [`CHANGELOG.md`](https://github.com/nanocodium/ndi-displays/blob/main/CHANGELOG.md) at the repo root.

Current artifact: **`ndidisplays-1.20.1-1.0.0-all.jar`** — Minecraft **1.20.1**, Forge **47.x**, Java 17.

## [Unreleased]

### Added

- [Chain Hoist](/blocks/chain-hoist) — flies an isolated island of real blocks. Each motor has its own chain (rake); group / remote moves keep the attitude. Theatrical fixtures stay patched in flight.
- [Hoist Remote](/items/hoist-remote) — e-stop, groups, UP / STOP / DOWN.
- `/hoist at` and `/hoist group` (permission 2).
- Server caps in [`ndidisplays-common.toml`](/reference/config#hoist).
- Tags `#ndidisplays:hoist_world` and `#ndidisplays:hoist_immovable`.
- LED corner cabinet in the creative tab ([LED Wall](/blocks/led-panel#90-turns)).
- This VitePress wiki (`docs/`) and a root [`CHANGELOG.md`](https://github.com/nanocodium/ndi-displays/blob/main/CHANGELOG.md).

### Changed

- Corner facing snaps only when both L wings already join.
- Path walls bake the same UV strips the world mesh uses (Iris / Oculus).

### Fixed

- Corner collision is an L, not a full cube.
- Quarter-arc meets adjacent flats without a seam.
- Path-wall Shimmer bloom is one mesh, not a stack of chord quads.

## [1.0.0] - 2026-09-03

Upstream `main` at the hoist branch point: LED walls, kinetic winches, cameras, drone, projector, computer, rack.

[Unreleased]: https://github.com/nanocodium/ndi-displays/compare/32d691b...HEAD
[1.0.0]: https://github.com/nanocodium/ndi-displays/commit/32d691b
