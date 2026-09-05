# Chain Hoist

A stage motor that flies a **real block structure** — truss, scenery, SEF speakers, Theatrical fixtures, LED cabinets, or a pile of oak. Not a kinetic tile on a cable: the load leaves the world, travels as one rigid structure, and is placed back when the motor stops.

Each motor runs its own chain, so a truss on several hoists can be **raked** by running one corner up. Theatrical fixtures on the load stay patched and keep running their cue, beams and all, for the whole flight. NDI video is the exception: a screen goes blank in the air and comes back when the load lands.

## Registry IDs

| Piece | ID |
|-------|-----|
| Block | `ndidisplays:chain_hoist` |
| Block entity | `ndidisplays:chain_hoist` |
| Flying load | `ndidisplays:moving_rig` |
| Remote | `ndidisplays:hoist_remote` — [Hoist Remote](/items/hoist-remote) |

## Crafting

See [Recipes](/reference/recipes) (`chain_hoist.json`).

Iron ingots, vanilla chain, piston.

## First hang

1. Clamp the motor under a roof, grid, or truss (it faces you on place).
2. Build the load as its own island. It may sit on the floor or grass. It must not be **bolted into a wall** or fused into a neighbouring building.
3. Sneak + right-click with a block in hand to place against the motor without opening the pendant.
4. Right-click the motor → **Attach**, or press **UP** / **DOWN** and it will pick up a valid island on its own.
5. **STOP** (or the target height) puts the blocks back so you can add pieces, then fly again.

If attach fails, the motor faults and says why (empty hook, welded to the world, over the size cap, …). It never lifts a sliced-off half of a building.

## Pendant GUI

Right-click the motor (empty hand, no sneak-place).

| Control | What it does |
|---------|----------------|
| **UP / DOWN** | Run **this motor** to its upper or lower limit. On a multi-point hang this rakes the load. |
| **STOP** | Halts every motor. The load stays in the air at that height. |
| **Target + GOTO** | Pay out an exact chain length in metres, on this motor. |
| **Attach / Detach** | Attach captures the island. **Detach on the owner** sets the hang down. **Detach on a follower** unhooks that motor; the others keep the truss. The last motor left always lands. |
| **Limits / speed** | Shortest chain, longest chain, working speed. |
| **Group** | Name this motor so a remote or `/hoist group` can run several at once. |
| **Group UP / DOWN** | Moves every motor of the group by the **same** amount of chain, so the hang keeps its attitude. |

While a load is flying the pendant also reads out **Rake** in degrees, whenever there is any.

An [NDI Configuration Card](/items/ndi-config-card) whose stored name is a group will patch the motor into that group on right-click.

Shift + a block in hand **places** against the hoist instead of opening the GUI.

## How the load is chosen

The scanner is a flood fill from the first solid under the hook (or a neighbour if the motor sits in the truss itself). Everything connected and liftable becomes one island.

It **refuses** rather than trimming when:

- the island is welded **sideways** to terrain (`#ndidisplays:hoist_world`) or anything tagged `#ndidisplays:hoist_immovable`. Sitting on the deck or parking under a ceiling is allowed — that is how a set piece rests between cues.
- it is bigger than [server caps](/reference/config#hoist)
- a chunk on the edge is unloaded
- too many motors share it

Motors, kinetic winches, and SEF decorative chain are rigging hardware: they are not cargo and they do not weld the load to the world.

## Motion

- Quantity is **metres of chain out**. Shorter chain = higher load.
- Trapezoidal profile (soft start / stop). The server owns position; clients interpolate.
- While travelling the blocks are gone and a `moving_rig` entity is the only copy — no break/place every tick, no duplication on chunk unload.
- On STOP, at the target, on a fault, or after a world reload, the owner **lands** the load so you can build on it again — as long as it is level.
- Several motors on one island elect one owner. The owner advances every chain on the rig in one place each tick, so four motion profiles cannot drift apart.

## Multi-motor and rake

A long truss can hang on two or four hoists. Attach from any of them: every motor over the load is picked up in the same breath, so all the chains draw to the truss instead of one motor doing the work and the others hanging short.

The scanner finds a motor when it is above a column of the load (dead centre or one block off), when it shares a face with the load, or when it is **in the same group** as the motor you asked and hanging over the load. That last one is the escape hatch for rigging the geometry cannot see — a motor on a beam off the truss line, or one bolted through the roof it is hanging from.

Each motor then runs its own chain:

- **One motor** up or down → the load rakes about the plane of the other hooks.
- **A group** up or down → everybody moves the same distance, so the attitude is preserved. The motor with the least room left governs the move.
- **`goto` on a group** is deliberately absolute: asking for a trim height by number levels the hang at that height.

Rake is capped by `maxTiltDegrees` (35° by default). A motor that would push the load past the cap stops in **OBSTRUCTED**; a motor bringing it back towards level is always allowed, even if it is already over the cap.

Blocks cannot be stored on a slope, so **Detach on the owner levels the hang then lands it.** STOP only holds position. A leftover degree or two from four motors drifting is ignored. If the last motor is mined, the load lands square.

```
/hoist group "Main Truss" up
/hoist group "Main Truss" goto 6.5
/hoist at 10 80 10 up
/hoist at 10 80 10 info
```

`/hoist` is operator level (2). Same actions as the pendant: `at` runs one motor, `group` runs the hang.

## Persistence

Chain length, target, limits, speed, group, and the flying snapshot survive save, restart, and chunk unload. A motor that was moving comes back **stopped** at that height and lands the load on the next tick. Breaking a motor promotes another owner or lands the load — it never deletes the cargo.

## Integrations

No SEF fork is required. Official SEF is enough.

| Mod | What happens |
|-----|----------------|
| **SEF** (optional) | Speakers, line arrays, and wrench poses fly with full NBT. In flight the client asks SEF's own renderer to draw wrench-hidden cabinets. After landing, nearby mixers / scene processors are re-pointed at the new speaker positions. |
| **Theatrical** (optional) | Fixtures on the load **stay live**: still patched, still receiving DMX, body and beam drawn at the flying position, head still moving with the cue. See below. |
| **NDI Displays** screens / winches | LED cabinets can be part of the load, but their video does not run in the air. Kinetic winches are never lifted. |

### Flown lighting fixtures

A fixture that has left the world is no longer a DMX consumer, which would mean a rig going dark the moment it moves. So each fixture on the load gets a stand-in on the server, patched at **the address it took off from** — flying a truss does not re-patch it. It receives the same DMX frames it did on the deck, and its head position, colour, focus and intensity are sent to clients every tick, where the fixture's own renderer draws it at the flying position.

- Works with Theatrical Extra Lights' volumetric beams too, since the beam goes through Theatrical's own pipeline.
- Beam length is re-traced as the load moves, so a beam does not punch through the stage floor on the way up.
- No Theatrical, or a build whose API has moved: flying still works, the fixtures simply hold the look they took off with.
- Rake is applied to the fixture bodies. The beam itself follows the head's own pan and tilt rather than the truss angle.

## Limits

Defaults in `config/ndidisplays-common.toml` — see [Config](/reference/config#hoist).

- 256 blocks, 32×48×32 bounding box, 8 motors, 32 m chain, 0.12 m/s default / 1.0 m/s max, 35° rake.
- Hitting a cap is a **fault**, never a partial capture.
- Obstacle in the travel path → **OBSTRUCTED**, load held, then landed. Nothing is ever broken to make room.
- A raked load is tested cell by cell as it turns, so it cannot pass through anything; it can graze a corner, which is the price of letting it rake at all.
- Remote e-stop cuts every motor in range and stays latched until the mushroom is clicked again.
