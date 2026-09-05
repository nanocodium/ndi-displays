# Computer

A placeable desk machine with its own **native OS** — wallpaper, icons, windows, taskbar, cursor — drawn with Minecraft GUI calls into a private framebuffer. That one texture feeds three consumers: the in-world monitor, the sit-down screen, and the NDI output.

The OS is **not** a browser. Placement records the **owner**. Right-click sits down at the machine. A lock is decided on the **server**, so a locked computer stays locked no matter what a client claims.

## Registry ID

`ndidisplays:computer`

## Crafting

See [Recipes](/reference/recipes) (`computer.json`).

## Configuration

Identity (name, 480p / 720p / 1080p, fps, broadcast on/off, lock) lives on the block. The desktop is client runtime. Files persist **per computer, on disk**.

Idle machines sleep after ~10 s with no viewer; music stops with them.

### Apps

| App | What it does |
|-----|----------------|
| Notes | Real notepad; saves onto the machine's drive |
| Files | Lists saved notes / paint canvases |
| Browser | Full web page — **needs [MCEF](/reference/integrations)** |
| Paint | Pixel canvas |
| Images | Fetches a picture over HTTP |
| Music | Plays vanilla records at the block |
| NDI Monitor | Any live LAN source, in a window (that desktop is itself an NDI source) |
| Terminal | `help`, `echo`, `date`, `name`, `sources`, `files`, `open <app>`, `sysinfo`, `clear` |
| Settings | Name, resolution, FPS, NDI toggle, wallpaper |

Double-click maximises a window. The desktop is laid out in logical ~640×360 and scaled to the output, so 480p and 1080p keep the same layout.

## NDI behavior

**Send.** `MC Computer <name>` (or the position if the name is blank). Only the [broadcast](/guide/multiplayer) machine should publish.

## Multiplayer

Sit-down and lock are server-authoritative. Other clients can look at the chassis; they pull the NDI name if they want the desktop on a wall.

## Limits

- A wall of computers costs their combined refresh rates, not a fixed per-frame tax.
- Browser app is a black window without MCEF. The rest of the OS still works.
- Do not confuse this with the [Web Terminal](/blocks/web-terminal), which *is* a browser-only NDI box.

## Integrations

**MCEF** — Browser app only. [Shimmer](/reference/integrations) — none.
