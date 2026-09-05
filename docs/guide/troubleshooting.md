# Troubleshooting

## Walls stuck on colour bars

The mod loaded, NDI did not. Install the [Windows NDI Runtime](http://ndi.link/NDIRedistV6), restart, look for `NDI runtime initialised` in the log. Pattern is still **Colour bars** until you set **NDI Video** and Apply.

## Empty source picker / “source not found”

- Runtime missing (above).
- No NDI output on the LAN (OBS DistroAV not enabled). See [OBS, Resolume, and the wall](/guide/ndi-software).
- **Firewall** blocking NDI / mDNS (UDP 5353 and NDI data ports). Allow the game and NDI on Private networks.
- **VLAN / Wi‑Fi isolation** — phones and PCs must share multicast/mDNS, or use a discovery server.
- Linux: another process owns UDP 5353 and `avahi-daemon` is down. See [Install](/guide/install#linux).
- Name mismatch: walls match **exact** then **substring**. Prefer a short unique fragment.

A healthy receive thread is logged as `NDI-Receive-<name>`. `Unknown frame type id: 101` is an NDI 6 control frame Devolay 2.1.0 cannot name; it is ignored.

## Duplicate cameras on a server

Several clients have `broadcast.mode = ALWAYS`. Leave everyone on `AUTO` except the operator machine. See [Multiplayer](/guide/multiplayer).

## Web Terminal / Computer Browser / rack web module is a black panel

[MCEF](/reference/integrations) is not installed on the broadcasting client. The Computer OS (Notes, Paint, NDI Monitor, …) still runs without it.

## Equipment rack is dark

No [PDU](/blocks/equipment-rack) seated, or the breaker is off. Click the seated PDU to flip it. Decorative units do not power the frame.

## Vision switcher / computer / cameras missing on the LAN

Only the [broadcast](/guide/multiplayer) machine publishes (`AUTO` in singleplayer, `ALWAYS` on a dedicated server). Other clients receive.

## Projector shows the grid but not the feed

Pattern is still the alignment grid (factory default). Set **NDI Video** after the throw is framed. Far throw > 64 m is clamped; long throws cost more geometry.

## Linux screens stay black, nothing logged

1. `libndi.so.5` soname — NDI 6 as `libndi.so.6` never `dlopen`s. Use the Gradle shim or a symlink.
2. mDNS / `~/.ndi/ndi-config.v1.json` (restart after edit).

## Gradle `Unsupported class file major version 65`

Gradle 8.1.1 cannot **run** on JDK 21. Point `JAVA_HOME` / `org.gradle.java.home` at JDK 17. The toolchain only governs `compileJava`.

## Wrong jar

Ship `ndidisplays-*-all.jar`. The jar without `-all` has no Devolay.

## Chain hoist will not attach

- The load must be an **isolated island** from walls and neighbouring buildings. Sitting on the grass or the stage floor is fine and it will still fly. A bolt into a wall is a fault, not a smaller lift.
- Terrain is tagged `#ndidisplays:hoist_world`. Building a stage out of stone keeps it on the ground — use truss, wood, SEF, Theatrical, or other non-terrain blocks.
- Caps: `config/ndidisplays-common.toml` `[hoist]` (`maxBlocks`, bounding box, `maxMotorsPerRig`). Over the cap → fault, never a partial capture.
- Sneak + a block in hand **places** against the motor. Empty-hand right-click opens the pendant.
- After STOP the load is world blocks again — **if it is level**. A raked hang stays in the air; the pendant says so and shows the rake in degrees. Level it (low motors UP, or group keys) and it lands on its own.
- Only one chain on a four-point hang: name the motors as one group, or put them over / next to the load. Attach from any of them picks the rest up.
- SEF speakers that were wrenched vanish only on an **old** jar. Current builds draw them in flight; no modified SEF is required.
- Fixtures go dark in the air only on an old jar, or if Theatrical's DMX API does not match. Current builds keep the cue running.

## Hoist remote does nothing

- E-stop mushroom latched (red blink, status **E-STOP**). Click the mushroom again to release.
- No motors in loaded chunks / group empty. Walk closer or patch motors into the selected group (right-click each hoist with the remote).
- Adventure / spectator, or a claim the player cannot interact with.
