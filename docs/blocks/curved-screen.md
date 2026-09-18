# Curved LED Screen

![Curved LED screen](/img/blocks/curved_screen.png)

Cylindrical arc: radius (0.5–256 m), opening angle, height (0.5–256 m), and a **distance** (0–512 m) that pushes the arc out in front of its mount. 360° closes it into a full video column. Concave (audience inside) or convex (audience outside), with optional video repeat around the barrel.

## Registry ID

`ndidisplays:curved_screen`

## Crafting

See [Recipes](/reference/recipes) (`curved_screen.json`).

## Configuration

Right-click → curved-screen processor: source, pitch, brightness, gamma, radius, opening angle, height, concave/convex, video repeat, crop, and **Mount: shown / hidden**. Hidden makes the centre hub invisible with a small hitbox, so only the arc shows — hover the centre to find and reopen it.

Video sits on one face of a thin slab (`CURVED_THICKNESS` 0.12).

## NDI behavior

**Receive only.**

## Multiplayer

Client receive. Config on the server.

## Limits

- Native resolution grows with radius, wrap, and height — watch the 3840×2160 feed cap.
- 360° wrap is a full column, not a merged wall of cabinets.

## Integrations

Shimmer (optional). Theatrical 2ch screen DMX (optional).
