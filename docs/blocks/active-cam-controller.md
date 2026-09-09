# Active Cam Controller

Desk / processor for a 2D Active Cam (Spidercam / OperTec-style) rig. Owns the authoritative [`ActiveCamPose`](/blocks/active-cam): position, velocity, pan, tilt, roll, FOV.

## Registry ID

`ndidisplays:active_cam_controller`

## Crafting

See [Recipes](/reference/recipes) (`active_cam_controller.json`).

## Configuration

Right-click to open the controller. Bind four [Camera Winches](/blocks/camera-winch) first (NDI card). Fields: working height, max speed, acceleration, Free Look / Track Target, Add point / Play / Pause / Stop / Loop / Clear, emergency STOP.

**Take control** is FPV on the gondola (same idea as the drone): WASD, Space / Ctrl for height, mouse look, scroll zoom, **B** adds a waypoint. Xbox pad (`active_cam_pad`): left stick XZ, right stick look, triggers zoom, A add point. Shift leaves FPV. Leave **Live** off unless you need the NDI source.

Working height is a **kinematic setpoint**. Changing it eases the gondola up/down; it never teleports.

## NDI behavior

**Send** `MC ActiveCam x,y,z` (override in the GUI) from the interpolated gondola pose, using the same capture budget as other rigs (`framePartialTick`).

## Multiplayer

Only the [broadcast](/guide/multiplayer) machine publishes. Pose snapshots are ~10 Hz; every client Hermite-interpolates.

## Limits

Gondola stays inside the four-winch box, below the drums, and within max cable length. Speed 0.5–25 m/s (default 10). Accel 0.5–15 (default 3). FOV 15–100°. Missing winch → e-stop.

## Integrations

Gamepad via GLFW. Track Target looks at a player name entered in the GUI.
