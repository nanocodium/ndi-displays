# Active Cam (2D Spidercam)

Four [Camera Winches](/blocks/camera-winch) around a stage, one gondola on visual cables, one [Controller](/blocks/active-cam-controller). Motion-control broadcast: server kinematics, client Hermite interpolation, drone-style waypoints.

## Registry ID

Gondola entity `ndidisplays:active_cam` (spawned by the controller, not placed by hand).

## Crafting

Craft the winches and controller; bind with the [NDI Configuration Card](/items/ndi-config-card): sneak-click four Camera Winches, then right-click the controller.

## Configuration

V1 is **2D**: sticks move X/Z, **working height** is the Y setpoint (eased with the same accel profile). 3D mode is stubbed for later.

Head is independent: Free Look (pan/tilt/roll/FOV) or Track Target (lookAt a player UUID while the nacelle still flies).

Paths reuse the drone spline (`DronePath`: Catmull-Rom, Once / Loop / Ping-pong). **Add point** / **B** stores the current gondola pose (pitch can look straight down). **Play** eases along the points. Take control is FPV on the gondola — no extra NDI render unless Live is on.

## NDI behavior

**Send** from the gondola pose. Source `MC ActiveCam x,y,z`. Tally on the head when live.

## Multiplayer

Server owns pose. Snapshots ~10 Hz. Clients interpolate every frame for render and NDI. One operator via Take control.

## Limits

Bounding box of the four winches (inset), min hang under drums, max cable length, max speed, server validation. Emergency stop freezes velocity. Destroying a winch stops the rig.

## Integrations

Xbox pad (`active_cam_pad`, separate from `drone_pad`). Defaults in [Client config](/reference/config).
