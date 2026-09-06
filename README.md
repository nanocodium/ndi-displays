<div align="center">

# NDI Stage Displays

**Forge 1.20.1 · real NDI video in Minecraft**

Touring LED walls, kinetic parks, and broadcast cameras that speak the same
protocol as OBS, Resolume, vMix, and a hardware LED processor.

<br/>

<a href="https://www.minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.20.1-2d6a4f?style=for-the-badge" alt="Minecraft 1.20.1"></a>
<a href="https://files.minecraftforge.net/"><img src="https://img.shields.io/badge/Forge-47.x-c2410c?style=for-the-badge" alt="Forge 47"></a>
<a href="https://ndi.video/"><img src="https://img.shields.io/badge/NDI-SDK%20v5%20%2F%20v6-0ea5e9?style=for-the-badge" alt="NDI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-64748b?style=for-the-badge" alt="MIT"></a>

<br/>

[Screens](#screens) · [Cameras](#cameras) · [Kinetics](#kinetics) · [Control room](#control-room) · [Requirements](#requirements) · [Usage](#usage) · [Build](#build) · [Changelog](CHANGELOG.md)

</div>

---

Every client pulls NDI itself — no server transcode, no shared texture upload.
Walls look like rental cabinets (pitch, subpixels, bezels, gamma). Cameras
publish the world back onto the same network, so a jib can land on the IMAG
wall in the same world.

<table>
<tr>
<td width="33%" valign="top">

**In**
Live NDI from OBS, a media server, or another machine on the LAN.

</td>
<td width="33%" valign="top">

**Through**
Walls, floors, curves, projectors, kinetic tiles, switchers, racks,
and a processor GUI.

</td>
<td width="33%" valign="top">

**Out**
Broadcast, PTZ, jib, dolly, handheld, drone, computer, and switcher
program as real NDI sources.

</td>
</tr>
</table>

---

<h2 id="screens">Screens</h2>

<table>
<tr>
<td width="50%" valign="top">

### LED Wall Panel
Slim 1×1 m rental cabinet. Place a rectangle of same-facing panels and they
merge into one wall — no support blocks, they hang as if they were rigged.

Right-click any cabinet for the **LED Wall Processor**: source, pitch,
brightness, gamma, test patterns.

</td>
<td width="50%" valign="top">

### Blow-Through Panel
Mesh / see-through cabinet for flying in front of a lighting rig. Dimmer
than a solid module (less emitter area) and it does not block light or sight.

Same processor, same NDI feed, same merge rules as the solid wall.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### LED Floor Tile
Walkable module on the XZ plane. Adjacent same-facing tiles stitch into one
floor canvas — colour bars, grids, or live video underfoot.

</td>
<td width="50%" valign="top">

### Round LED Screen
A single mount that draws a video disc of configurable radius. Same processor
workflow as the walls, without building a circle out of cabinets.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Curved LED Screen
Cylindrical arc: radius, opening angle, height. 360° closes it into a full
video column. Concave (audience inside) or convex (audience outside), with
optional video repeat around the barrel.

</td>
<td width="50%" valign="top">

### Video Processor
Every screen can crop an **input window** out of the incoming frame — full
frame or a sub-rectangle — so one NDI source can feed several walls with
different cuts.

Same-kind cabinets that share an edge are **one screen**, even when the
plan is not a rectangle (cross, L, stairs).

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Video Projector
A lens, not a cabinet. It drapes NDI onto world geometry — throw, keystone,
lens shift, feather, additive overlap. Fresh units come up on the
alignment grid. Shadows use a 2048 map.

</td>
<td width="50%" valign="top">

### LED Corner
Quarter-cylinder wrap between two cardinal runs. Craft from one panel.
Sneak-place for the inner corner. Without it, a 90° L is two walls.

</td>
</tr>
</table>

<h3>What the shader actually simulates</h3>

The wall is not a quad with a video texture. A dedicated core shader models a
real LED processor:

<table>
<tr>
<td width="50%" valign="top">

- Per-LED point sampling, LOD like a hardware scaler
- Pixel pitch P2.0–P31 (a 10×5 m wall at P3.9 is 2560×1280)
- Vertical R&nbsp;|&nbsp;G&nbsp;|&nbsp;B subpixel stripes
- Black bezel + energy compensation
- Per-LED brightness variance (uncalibrated modules)

</td>
<td width="50%" valign="top">

- Gamma 1.8–2.8, brightness in % (≈ nits), linear light
- Structure fades out below ~1 LED per screen pixel (no moiré)
- Emissive surface, ignores world lighting (no vanilla block light)
- Colour bars, alignment grid, RGB/white, pixel checker
- Optional <a href="https://www.curseforge.com/minecraft/mc-mods/shimmer">Shimmer</a> bloom from the live feed

</td>
</tr>
</table>

<details>
<summary><strong>Angled walls</strong></summary>

<br/>

Panels have eight orientations. Facing a cardinal gives a square cabinet;
facing between them gives a 45° one. Stand square to the angle you want and
place on the ground. Clicking a block face still snaps flush to that face.

Build a 45° wall as a staircase — one block diagonally each time — and the
cabinets meet corner to corner. A straight run and a 45° wing are **two**
walls with their own sources, meeting at a corner.

</details>

---

<h2 id="cameras">Cameras</h2>

Rigs film the world and publish **real NDI sources**. Pick them up in OBS or
vMix, or route them back onto an in-game wall (DJ cam on the IMAG).

<table>
<tr>
<td width="50%" valign="top">

### Broadcast Camera
Tripod ENG body: fluid head, pan bar, matte box, viewfinder. Fixed shot with
pan / tilt trim and zoom.

</td>
<td width="50%" valign="top">

### PTZ Camera
Single-arm broadcast PTZ. Pan and tilt ease to target at a configurable slew
rate. Piano-black stepped base, live tally on the head.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Jib Camera
Boom on a pedestal. The arm auto-sweeps; length, sweep range and period are
configurable. The head hangs on the tip.

</td>
<td width="50%" valign="top">

### Track Dolly
Lay **Camera Track** (straight or curved, including closed rings). The dolly
follows the rail and leans into bends. Open runs ping-pong; rings loop.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Handheld Camera
Item, not a block. While held, the client publishes the player's view as
<code>MC Handheld &lt;player&gt;</code>. Several players can each carry one.

</td>
<td width="50%" valign="top">

### NDI Drone
Place the drone, link a remote, then fly it in FPV — you are in the gimbal.
WASD strafes, space / sneak climb, mouse aims. The NDI source
<code>MC Drone &lt;id&gt;</code> is the same view. Sneak-click the remote for
waypoints (once / loop / ping-pong). Optional Xaero import.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Rig config
Right-click any block rig: source name, live on/off, 540p / 720p / 1080p,
frame rate, FOV, pan / tilt, and motion extras. A red tally means the rig
is live.

</td>
</tr>
</table>

Capture is client-side and budgeted (round-robin). Keep the number of
**simultaneously live** rigs sensible. A rig only sees terrain the viewer's
client has built — fine for a stage, not for filming unloaded chunks.

---

<h2 id="kinetics">Kinetics</h2>

<table>
<tr>
<td width="50%" valign="top">

### Kinetic LED Winch
A ceiling motor that flies a payload on rendered cables — Freedom Stage
“floating sky”, not a teleport. Motion is trapezoidal: accelerate, cruise,
decelerate.

**Payloads:** LED tile · LED slat · kinetic RGB sphere · mirror ball · a
Theatrical / Extra Lights fixture hung on the hook.

**Orientation:** vertical tile or flat (sky-facing). Mesh tiles are
blow-through.

</td>
<td width="50%" valign="top">

### Parks and stitch
Each winch knows its cell in a canvas (`Canvas W×H`, `My Col/Row`). The
shader's `UvRegion` samples only that slice, so a flown grid stays one
picture as motors move.

The **NDI Configuration Card** can select a park (sneak + click two
corners) and either **stitch** it into one image or give every motor the
full source.

**Winch Park Monitor** binds to that selection and plots the motors.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Chain Hoist
A stage motor that flies an **isolated island of real blocks** — truss,
fixtures, speakers, scenery. Each motor pays out its own chain, so
raising one corner rakes the hang. A group command or the hoist remote
keeps the attitude. Theatrical fixtures stay patched in flight.

STOP holds the load in the air. It lands only when the hang is level.
Server caps live in `config/ndidisplays-common.toml` under `[hoist]`.

</td>
<td width="50%" valign="top">

### Hoist Remote
Yellow belly-box: latched e-stop, group selector, UP / STOP / DOWN.
Reach 192 blocks. `/hoist at` and `/hoist group` do the same from the
console (permission 2).

</td>
</tr>
</table>

<h3>DMX (optional Theatrical)</h3>

When <a href="https://github.com/theatricalmod/Theatrical">Theatrical</a> is installed,
winches register as patchable fixtures.

<table>
<tr>
<td width="50%" valign="top">

**Linked — 4 ch**
Height coarse / fine (16-bit), speed, dimmer.

</td>
<td width="50%" valign="top">

**Twin — 6 ch**
Two independent 16-bit heights + shared speed and dimmer.

</td>
</tr>
<tr>
<td width="50%" valign="top">

**Flown fixture — 4 / 7 / 10 ch**
Position only, plus colour, or full (focus, pan, tilt). Beams can go
through Extra Lights' volumetric pipeline.

</td>
<td width="50%" valign="top">

**Screens — 2 ch**
Dimmer + source select (slot every 32 DMX values). Patch with Theatrical's
configuration card.

</td>
</tr>
</table>

---

<h2 id="control-room">Control room</h2>

<table>
<tr>
<td width="50%" valign="top">

### NDI Router
Publishes a **stable output name** and forwards whichever source is patched
to it. Uses NDI routing — no decode, no re-encode, no extra GPU cost.

Rename the output once in OBS; re-patch the router when the show changes.

</td>
<td width="50%" valign="top">

### Multiview Monitor
2×2 or 3×3 mosaic of NDI sources for the video engineer. Direct video, no
LED simulation. Click a cell, then pick its source.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### NDI Configuration Card
Sneak + right-click in the air to pick a source. Right-click a screen to
apply it. Sneak + click two winches to bound a park, then apply source,
winch mode (linked / twin), and stitch on/off to the whole selection.

</td>
<td width="50%" valign="top">

### Winch Park Monitor
Control-room plot of a bound park: motor grid, selection size, live
layout. Bind it with the card the same way you select a region.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Vision Switcher
Eight inputs, program / preview, CUT and AUTO (mix / dip / wipe). Output
<code>MC Switcher &lt;name&gt;</code> is composited on the broadcast GPU.

</td>
<td width="50%" valign="top">

### Pro Monitor
Single-feed desk panel. Sibling of the multiview. Card applies a source.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Computer
Placeable desk OS (Notes, Files, Paint, Music, NDI Monitor, Terminal).
Publishes <code>MC Computer &lt;name&gt;</code>. Owner lock is server-side.
Browser app needs MCEF.

</td>
<td width="50%" valign="top">

### Equipment Rack
Six 1U slots. Units are items (web, PDU, switch, patch, recorder, sync,
blank, rack router). The frame runs only while a PDU is seated and on.

</td>
</tr>
</table>

---

<h2 id="requirements">Requirements</h2>

<table>
<tr>
<td width="50%" valign="top">

### Required
1. **Minecraft 1.20.1** + **Forge 47.x**
2. The mod on **every client** that should see video, and on the **server**
   (blocks must be registered; the server never talks NDI)

</td>
<td width="50%" valign="top">

### For live video
3. **NDI runtime** on each viewing machine
   - Windows / macOS: <a href="https://ndi.video/tools/">NDI Tools</a>
   - Linux: NDI SDK / <code>libndi</code>
4. An NDI source on the LAN — easiest is OBS +
   <a href="https://distroav.org/">DistroAV / obs-ndi</a>
   → *Tools → NDI Output Settings*

</td>
</tr>
</table>

Without the runtime the mod still loads. Walls show test patterns and the
GUI reports that NDI is missing.

### Optional integrations

<table>
<tr>
<td width="50%" valign="top">

**Shimmer** — live wall content is fed into the bloom pipeline so bright
video spills into the room.

</td>
<td width="50%" valign="top">

**Theatrical** — winches and screens become DMX consumers on a Theatrical
network. Extra Lights fixtures can be hung on a winch hook.

</td>
</tr>
<tr>
<td width="50%" valign="top">

**Xaero Minimap / World Map** — the drone path GUI can import the current
world's waypoints. Soft dependency; the button only appears when Xaero is
loaded.

</td>
<td width="50%" valign="top">

</td>
</tr>
</table>

---

<h2 id="usage">Usage</h2>

<table>
<tr>
<td valign="top" width="8%"><strong>1</strong></td>
<td>

Build a rectangle of **LED Wall Panels**, all facing the same way. New
cabinets show colour bars so you can see the wall is alive.

</td>
</tr>
<tr>
<td valign="top"><strong>2</strong></td>
<td>

Right-click the wall. The **LED Wall Processor** opens.

</td>
</tr>
<tr>
<td valign="top"><strong>3</strong></td>
<td>

Pick a discovered NDI source (or type a fragment of the name). Set pitch,
brightness, gamma. Pattern → **NDI Video**. **Apply to Wall**.

</td>
</tr>
<tr>
<td valign="top"><strong>4</strong></td>
<td>

The whole rectangle plays the feed. Settings are per-wall and saved with
the world.

</td>
</tr>
</table>

Prefer a short distinctive fragment (`Arena - Composition`) over the full
machine-prefixed name. Matching is exact first, then case-insensitive
substring, so the short form survives a hostname change.

---

<h2 id="multiplayer">Multiplayer</h2>

Install the mod on the server. The server never opens NDI: no runtime, no
config, no ports.

**Receiving** works for every client with the runtime and LAN access.

**Broadcasting** must come from **one** machine. Otherwise every client
publishes a duplicate of every camera and renders it again.

```toml
# config/ndidisplays-client.toml
[broadcast]
    mode = "ALWAYS"   # operator machine only
    handheld = true
```

`AUTO` (default) broadcasts only in singleplayer or when hosting a LAN
world. On a dedicated server nothing is published until one machine sets
`ALWAYS`. `NEVER` is receive-only.

The handheld camera is named per player and has its own switch, so several
operators can each carry one. Drones publish as <code>MC Drone &lt;id&gt;</code>
from the same client that broadcasts the other rigs.

---

<h2 id="build">Build</h2>

<table>
<tr>
<td width="50%" valign="top">

### Linux / macOS

```bash
./gradlew build
```

</td>
<td width="50%" valign="top">

### Windows

```bat
gradlew.bat build
```

</td>
</tr>
</table>

Java 17 is the compile target. Gradle 8.1.1 needs a JDK it can *run* on
(8–19). The toolchain provisions 17 for `compileJava`.

Two jars land in `build/libs/`:

<table>
<tr>
<td width="50%" valign="top">

**Ship this**
`ndidisplays-1.20.1-1.1.1-beta.1-all.jar`

Devolay is bundled via jarJar.

</td>
<td width="50%" valign="top">

**Do not ship**
`ndidisplays-1.20.1-1.1.1-beta.1.jar`

No NDI bindings. Devolay will fail at runtime.

</td>
</tr>
</table>

### Dev client

```bat
gradlew.bat runClient
```

Optional:

```bat
gradlew.bat runClient -PquickPlay="NDI TEST"
gradlew.bat runClient -PdebugCapture
gradlew.bat runClient -PperfLog
```

Run configs honour `NDI_RUNTIME_DIR_V6` / `V5`, then probe the usual
install paths for a directory that actually contains an NDI library.
Override with
`-PndiRuntimeDir="C:/Program Files/NDI/NDI 6 Runtime/v6"`.
Gradle prints the directory it picked, and says so when it holds no NDI.

The dev client needs **JDK 17**. Gradle 8.1.1 cannot run on Java 21 and
fails during configuration with `Unsupported class file major version 65`
— the toolchain in `build.gradle` governs compilation, not Gradle's own
JVM. If your default JDK is newer, either launch with `JAVA_HOME` set to a
17 install, or set `org.gradle.java.home` in `~/.gradle/gradle.properties`
(machine-local; deliberately not committed, since the path differs per OS).

#### Linux notes

Two things bite on Linux only, and both fail *silently* — screens stay
black and nothing is logged:

1. **Devolay wants `libndi.so.5`.** Its natives `dlopen` that exact
   soname, so an NDI 6 install (`libndi.so.6`) never resolves. The build
   now links `build/ndi-shim/libndi.so.5` to whatever major version it
   finds and points the run config there; NDI 6 still exports
   `NDIlib_v3_load`, the entry point Devolay calls, so the runtime is
   ABI-compatible and only the filename was wrong. Windows and macOS
   need no bridge — their runtime filenames carry no major version.

2. **NDI discovery needs a working mDNS responder.** NDI's mDNS has to
   bind UDP 5353; if another process holds it exclusively (browsers are
   common offenders) and `avahi-daemon` is not running, the source finder
   returns an empty list forever, so a screen set to an NDI source just
   renders black. Either run `avahi-daemon`, or bypass mDNS with
   `~/.ndi/ndi-config.v1.json`:

   ```json
   { "ndi": { "networks": { "ips": "127.0.0.1" } } }
   ```

   That covers in-game cameras feeding in-game screens. Add the IP of any
   other machine whose sources you want (`"192.168.0.50,127.0.0.1"`), or
   run `ndi-discovery-server` from the SDK and use
   `"discovery": "<ip>"` instead. NDI reads this file only when the
   library loads, so restart the client after editing it.

A healthy client logs `NDI runtime initialised`, one
`NDI sender '<name>' online` per camera, and an `NDI-Receive-<name>`
thread per screen that is pulling a feed. `Unknown frame type id: 101` is
an NDI 6 control frame that Devolay 2.1.0 cannot name; it is ignored and
harmless.

---

<div align="center">

NDI® is a registered trademark of Vizrt NDI AB. This project is not
affiliated with Vizrt.

[MIT License](LICENSE) · [Issues](https://github.com/nanocodium/ndi-displays/issues) · [Source](https://github.com/nanocodium/ndi-displays)

</div>
