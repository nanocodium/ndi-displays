# NDI Stage Displays (Forge 1.20.1)

Realistic LED video walls for Minecraft, driven by **real NDI network video** — feed
your in-game stage screens from OBS, Resolume, vMix, a media server, or any other
NDI source on your LAN, exactly like a real touring LED wall.

## What it does

- **LED Wall Panel** block — a slim 1×1 m rental-style cabinet. Place panels in any
  rectangle (walls hang free, no support needed — they're "rigged") and they
  automatically merge into a single video wall.
- **Real NDI input** — the mod embeds [Devolay](https://github.com/WalkerKnapp/devolay)
  (Java bindings for NewTek's NDI SDK). Every client receives the stream itself over
  the network, just like every processor on a real stage network.
- **LED processor GUI** — right-click any panel to open the wall config: pick a
  discovered NDI source (or type a name), pixel pitch, brightness, gamma, and test
  patterns.

## Realism simulation (custom core shader)

The wall is not just a texture on a quad. A dedicated fragment shader models how a
real LED wall behaves:

| Real-world behaviour | Simulation |
|---|---|
| Each LED shows one sample of the scaled feed | Per-LED point sampling; mipmap LOD chosen like a hardware scaler |
| Pixel pitch (P2.0 – P31) | Selectable px/m; a 10×5 m wall at P3.9 is a true 2560×1280 px canvas |
| RGB subpixel structure | Vertical R\|G\|B emitter stripes inside each LED cell |
| Black bezel between pixels | Configurable gap with tiny intense emitters + energy compensation |
| Uncalibrated module variance | Subtle per-LED random brightness offset |
| Panel gamma / drive brightness | Gamma 1.8–2.8, brightness in % (≈nits) applied in linear light |
| Wall resolves smooth at distance, no moiré | Pixel structure anti-aliases and fades out below ~1 LED per screen pixel |
| Emissive surface | Screen ignores world lighting and emits block light |
| Test patterns | Colour bars + grey ramp, per-cabinet alignment grid with diagonals, R/G/B/white, pixel checker |
| Light spill / camera bloom | Optional [Shimmer](https://www.curseforge.com/minecraft/mc-mods/shimmer) integration: live video is fed into Shimmer's bloom pipeline so bright content glows |

## NDI cameras (world → network)

The mod also works in the other direction: camera rigs film the world and
broadcast it as **real NDI sources** — pick them up in OBS/vMix, or feed them
straight back onto an in-game LED wall (DJ cam on the big screen).

- **Broadcast Camera** — tripod ENG camera. Fixed shot with pan/tilt trim and zoom.
- **PTZ Camera** — compact dome. Pan/tilt changes ease in at a configurable slew
  rate, like a real motorized head.
- **Jib Camera** — boom arm on a pedestal that auto-sweeps over the stage;
  configurable arm length, sweep range and period.
- **Track Dolly Camera** — lay a straight run of **Camera Track**, place the
  dolly camera on top of a rail; it ping-pongs along the run at a set speed.

Right-click any rig for its config: NDI source name, live on/off, 540p/720p/1080p,
frame rate, zoom, pan/tilt and rig-specific motion settings. A red tally lamp
shows when a rig is live. Feeds render on the client (one capture per frame,
round-robin), so keep the number of simultaneously live rigs sensible.

Camera rigs must be within ~96 blocks of the viewer, and they can only see
terrain the viewer's client has built (fine for stage/venue shots).

## Requirements

1. **Minecraft 1.20.1 + Forge 47.x**
2. **NDI runtime** installed on each client machine that should see video:
   - Windows/macOS: install [NDI Tools](https://ndi.video/tools/)
   - Linux: install the NDI SDK / runtime (`libndi`)
   - Without it the mod still works — walls show test patterns, and the GUI reports
     that the runtime is missing.
3. An NDI source on your network. Easiest: OBS Studio with the
   [DistroAV / obs-ndi](https://distroav.org/) plugin → *Tools → NDI Output Settings*.

## Usage

1. Build a rectangle of **LED Wall Panels** (all facing the same way). New panels
   show colour bars so you can see the wall is alive.
2. Right-click the wall → the **LED Wall Processor** opens.
3. Pick your NDI source from the discovered list (or type part of its name),
   choose pitch/brightness/gamma, set pattern to **NDI Video**, hit **Apply to Wall**.
4. The whole wall now plays your live feed.

Settings are per-wall and saved with the world. Video reception is client-side;
each viewer needs the NDI runtime and network access to the source.

Tip: set the source to a short distinctive fragment (`Arena - Composition`)
rather than the full machine-prefixed name. Matching is exact first, then
case-insensitive substring, so the short form keeps working on machines where
the source carries a different prefix.

### Angled walls

Panels have eight orientations. Facing roughly north/east/south/west gives a
square panel; facing between them gives a 45° one, so stand square to the
angle you want and place on the ground. Clicking a block's side still snaps
flush to that face. Build a 45° wall as a staircase — one block diagonally
each time — and the cabinets meet corner to corner into one continuous
angled surface.

A wall is one flat rectangle, so a straight section and a 45° wing are two
separate walls with their own sources, meeting at a corner.

### Multiplayer

Install the mod on the server too — it registers the blocks, and Forge
requires it on both sides. The server never touches NDI: no runtime, no
config, no ports.

Receiving works for everyone. Broadcasting must come from **one** machine, or
every client publishes a duplicate copy of every camera and renders it again.
In `config/ndidisplays-client.toml`:

    [broadcast]
        mode = "ALWAYS"   # on the operator's machine only

`AUTO` (the default) broadcasts only in singleplayer or when hosting a LAN
world, so on a server nothing is published until one machine opts in. The
handheld camera is named per player and has its own switch, so several
players can each carry one.

## Building from source

Linux / macOS:

```bash
./gradlew build
```

Windows (cmd or PowerShell):

```
gradlew.bat build
```

The compile JDK is provisioned automatically (Java 17 toolchain), so `JAVA_HOME` only
needs to point at a JDK that Gradle 8.1.1 itself can run on — anything from 8 to 19.

Two jars land in `build/libs/`. The one to ship is
`ndidisplays-1.20.1-1.0.0-all.jar` — it has Devolay bundled via jarJar. The plain
`ndidisplays-1.20.1-1.0.0.jar` does **not** contain the NDI bindings and will fail to load
Devolay at runtime.

### Running the dev client

```
gradlew.bat runClient
```

The run configs locate your NDI runtime automatically: they honour an existing
`NDI_RUNTIME_DIR_V6`/`NDI_RUNTIME_DIR_V5`, otherwise they probe the standard install
paths for the platform. Override it explicitly with
`-PndiRuntimeDir="C:/Program Files/NDI/NDI 6 Runtime/v6"`. Gradle prints the directory it
picked at configuration time, and says so when it does not exist.
