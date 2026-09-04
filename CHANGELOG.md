# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Chain hoist** (`ndidisplays:chain_hoist`) — a stage motor that flies an isolated island of real blocks (truss, scenery, Theatrical fixtures, LED cabinets, SEF). Each motor runs its own chain, so raising one corner rakes the hang. A group command or remote keeps the attitude. Theatrical fixtures stay patched and keep their beams in flight.
- **Hoist remote** (`ndidisplays:hoist_remote`) — yellow belly-box: latched e-stop, group selector, UP / STOP / DOWN, pick-up / set down. Reach 192 blocks, same build and claim rules as the pendant.
- **`/hoist at`** and **`/hoist group`** (permission 2) for cue-style operation from the console or a function file.
- Server caps in `config/ndidisplays-common.toml` under `[hoist]` (`maxBlocks`, bounding box, `maxChainLength`, `maxTiltDegrees`, `maxMotorsPerRig`). Hitting a cap is a fault, never a partial lift.
- Block tags `#ndidisplays:hoist_world` and `#ndidisplays:hoist_immovable` so terrain and immovable furniture are never cargo.
- LED corner cabinet (`ndidisplays:led_corner`) registered in the creative tab, with a shapeless recipe from a flat panel.
- VitePress wiki under `docs/` (block catalog, recipes, config, troubleshooting) plus this file at the repo root.

### Changed

- LED corner placement only snaps facing when both wings of the L already join (score ≥ 2), so a single neighbour no longer rotates the wrap onto the wrong cell.
- Path walls (including 90° corners) bake the same UV strips the world mesh uses, so Iris / Oculus no longer flatten the turn into an AABB.

### Fixed

- Corner collision is an L-shaped voxel, not a full cube.
- Quarter-arc tessellation pins first and last vertices to the scanner endpoints, so the curve meets adjacent flats without a seam.
- Shimmer bloom for path walls is one mesh submit instead of dozens of chord quads (that read as a second screen and froze the client).
- Wall scanner no longer treats a cell as a neighbour of itself when scoring endpoints.

## [1.0.0] - 2026-09-03

Baseline of [nanocodium/ndi-displays](https://github.com/nanocodium/ndi-displays) `main` at the hoist branch point (`32d691b`): LED walls and floors, kinetic winches, cameras, drone, projector, computer, rack, and NDI I/O.

[Unreleased]: https://github.com/nanocodium/ndi-displays/compare/32d691b...HEAD
[1.0.0]: https://github.com/nanocodium/ndi-displays/commit/32d691b
