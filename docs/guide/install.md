# Install

Minecraft **1.20.1**, **Forge 47.x**, Java 17. Put the **`-all`** jar on every **client that should see video** and on the **server** (blocks must be registered). The server never opens NDI.

Ship **`ndidisplays-1.20.1-1.1.0-beta.1-all.jar`**. The thin jar without `-all` has no Devolay bindings and will fail at runtime.

Without an NDI library the mod still loads. Walls show test patterns and the GUI reports that NDI is missing.

## Windows — NDI Runtime (required for live video)

Devolay loads **`Processing.NDI.Lib.x64.dll`**. That file comes from Vizrt’s **NDI Runtime** redistributable, not from the Minecraft jar.

1. Download and install the official Windows **[NDI Runtime](http://ndi.link/NDIRedistV6)** (`ndi.link/NDIRedistV6`).
2. Restart the game after installing.
3. A healthy client logs `NDI runtime initialised`.

Typical install path (used by the Gradle run config as well):

`C:\Program Files\NDI\NDI 6 Runtime\v6`

Override with `-PndiRuntimeDir="…"` or env `NDI_RUNTIME_DIR_V6` / `NDI_RUNTIME_DIR_V5`.

### NDI Tools (optional)

**[NDI Tools](https://ndi.video/tools/)** (Studio Monitor, Test Patterns, Access Manager) is useful to confirm a source is on the LAN. It is **not** required to play, as long as the Runtime is present. Many people install Tools anyway; it also drops a runtime on the machine.

### NDI SDK (not for players)

The **[NDI SDK](https://ndi.video/for-developers/ndi-sdk/)** is for native development and Linux `libndi`. Windows players should install the **Runtime**, not the SDK.

## macOS

Install [NDI Tools](https://ndi.video/tools/) (includes the runtime). Linux notes below do not apply.

## Linux

Devolay `dlopen`s the soname **`libndi.so.5`**. An NDI 6 install named `libndi.so.6` will not resolve unless a shim is in `LD_LIBRARY_PATH` (the Gradle run config links `build/ndi-shim/libndi.so.5`). NDI 6 still exports `NDIlib_v3_load`.

Discovery needs a working mDNS responder on UDP **5353**. If the picker is empty, run `avahi-daemon` or bypass mDNS with `~/.ndi/ndi-config.v1.json`:

```json
{ "ndi": { "networks": { "ips": "127.0.0.1" } } }
```

Restart the client after editing. Add other machine IPs as a comma-separated list, or run `ndi-discovery-server` from the SDK with `"discovery": "<ip>"`.

## An NDI source on the LAN

Easiest path: OBS + [DistroAV / obs-ndi](https://distroav.org/) → *Tools → DistroAV NDI output*. Resolume, vMix, and hardware processors work the same. Walkthroughs: [OBS, Resolume, and the wall](/guide/ndi-software).

## Optional mods

| Mod | Why |
|-----|-----|
| [Shimmer](https://www.curseforge.com/minecraft/mc-mods/shimmer) | Bloom from live wall content |
| [Theatrical](https://github.com/theatricalmod/Theatrical) | DMX on winches and screens; Extra Lights fixtures on a winch hook |
| Xaero Minimap / World Map | Import world waypoints into a drone path |
| MCEF | Required for the [Web Terminal](/blocks/web-terminal) browser |

See [Integrations](/reference/integrations).

## Build from source

Java 17 compile target. Gradle 8.1.1 must **run** on JDK 8–19 (not 21).

```bat
gradlew.bat build
```

```bash
./gradlew build
```

Dev client: `gradlew.bat runClient`. Optional: `-PquickPlay="NDI TEST"`, `-PdebugCapture`, `-PperfLog`.
