# Cameras

Block rigs film the world and publish **real NDI sources**. Pick them up in OBS or vMix, or route them back onto an in-game wall (DJ cam on the IMAG). Bodies are articulated OBJ meshes (fluid head, PTZ arm, boom, dolly column), not JSON cubes.

Right-click any rig: source name, live on/off, 540p / 720p / 1080p, frame rate, FOV, pan / tilt, and motion extras. A red tally means the rig is live.

Capture is client-side and budgeted (round-robin). Keep the number of **simultaneously live** rigs sensible. A rig only sees terrain the viewer's client has built — fine for a stage, not for filming unloaded chunks.

Default name if the field is blank: `MC Cam|PTZ|Jib|Dolly <x>,<y>,<z>`.

Handheld and shoulder are **items**: [Handheld](/items/handheld-camera), [Shoulder](/items/shoulder-camera). FPV: [Drone](/items/drone).

---

## Broadcast Camera

Tripod ENG body: fluid head, pan bar, matte box, viewfinder. Fixed shot with pan / tilt trim and zoom.

### Registry ID

`ndidisplays:broadcast_camera`

### Crafting

See [Recipes](/reference/recipes) (`broadcast_camera.json`).

### Configuration

Right-click → camera GUI. Pan/tilt trim, zoom, resolution (960×540 / 1280×720 / 1920×1080), fps (default 30), FOV (default 60°), live toggle, source name.

### NDI behavior

**Send.** Default `MC Cam x,y,z`. Logged as `NDI sender '<name>' online`.

### Multiplayer

Only the [broadcast](/guide/multiplayer) machine publishes. Receivers see the source on the LAN like any other NDI box.

### Limits

Round-robin capture budget. 1080p is the max preset. FOV and trim only — no motorized slew.

### Integrations

None required. Route through an [NDI Router](/blocks/ndi-router) for a stable OBS name.

---

## PTZ Camera

Single-arm broadcast PTZ. Pan and tilt ease to target at a configurable slew rate (default 45°/s). Piano-black stepped base, live tally on the head.

### Registry ID

`ndidisplays:ptz_camera`

### Crafting

See [Recipes](/reference/recipes) (`ptz_camera.json`).

### Configuration

Same camera GUI plus **slew rate**. Targets ease; they do not snap.

### NDI behavior

**Send.** Default `MC PTZ x,y,z`.

### Multiplayer

One broadcaster. Same as Broadcast Camera.

### Limits

Slew is simulated client-side on the operator machine. Capture budget shared with other live rigs.

### Integrations

None required.

---

## Jib Camera

Boom on a pedestal. The arm auto-sweeps; length, sweep range and period are configurable. The head hangs on the tip.

### Registry ID

`ndidisplays:jib_camera`

### Crafting

See [Recipes](/reference/recipes) (`jib_camera.json`).

### Configuration

Camera GUI plus **arm length** (default 5 m, max **24 m**), sweep range, period. Invisible `jib_seat` entity for the operator pose.

### NDI behavior

**Send.** Default `MC Jib x,y,z`. Eye is at the boom tip.

### Multiplayer

One broadcaster.

### Limits

Arm length is cheap to render (trig at the tip). Capture still costs GPU on the broadcast client.

### Integrations

None required.

---

## Track Dolly

Lay **Camera Track** (straight or curved, including closed rings). The dolly follows a Catmull-Rom rail, leans into bends, and the column telescopes with the model. The motion clock survives server lag. Open runs ping-pong; rings loop.

### Registry ID

`ndidisplays:track_camera`

### Crafting

See [Recipes](/reference/recipes) (`track_camera.json`).

### Configuration

Camera GUI plus path speed and **column** height (0–3 m above the stock pedestal; 0 is the low MILO stance). Needs a connected run of [Camera Track](#camera-track).

### NDI behavior

**Send.** Default `MC Dolly x,y,z`.

### Multiplayer

One broadcaster.

### Limits

No track → no motion. Head offset matches the dolly model (above and ahead of the deck).

### Integrations

None required.

---

## Camera Track

Rails for the dolly. Straight or curved, including closed rings.

### Registry ID

`ndidisplays:camera_track`

### Crafting

See [Recipes](/reference/recipes) (`camera_track.json`).

### Configuration

No processor GUI. Facing and connections define the path.

### NDI behavior

n/a — geometry only. The [Track Dolly](#track-dolly) is the sender.

### Multiplayer

n/a (block on the server like any rail).

### Limits

Dolly needs a contiguous run. Open paths ping-pong; loops loop.

### Integrations

None.
