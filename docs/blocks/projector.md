# Video Projector

The fixture body is a block; the picture is thrown into the world by the renderer. Placement aims it the way the player is looking (pitch included). Right-click opens the lens GUI: source, throw, keystone, and brightness.

## Registry ID

`ndidisplays:projector`

## Crafting

See [Recipes](/reference/recipes) (`projector.json`).

## NDI behavior

**Receive.** The frustum is a world-space projection, not an LED cabinet mesh. Shadows and falloff live on the client that is looking at it.
