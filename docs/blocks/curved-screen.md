# Curved LED Screen

Cylindrical arc: radius, opening angle, height. 360° closes it into a full video column. Concave (audience inside) or convex (audience outside), with optional video repeat around the barrel.

## Registry ID

`ndidisplays:curved_screen`

## Crafting

See [Recipes](/reference/recipes) (`curved_screen.json`).

## Configuration

Right-click → curved-screen processor: source, pitch, brightness, gamma, radius, opening angle, height, concave/convex, video repeat, crop.

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
