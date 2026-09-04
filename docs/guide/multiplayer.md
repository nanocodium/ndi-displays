# Multiplayer

Install the mod on the **server**. The server never opens NDI: no runtime, no config, no ports.

**Receiving** works for every client with the [NDI Runtime](/guide/install) and LAN access.

**Broadcasting** (cameras, drone, handheld, web terminal, router outputs) must come from **one** machine. Otherwise every client publishes a duplicate of every camera and renders it again.

```toml
# config/ndidisplays-client.toml
[broadcast]
    mode = "ALWAYS"   # operator machine only
    handheld = true
```

| `broadcast.mode` | Behaviour |
|------------------|-----------|
| `AUTO` (default) | Broadcast in singleplayer or when hosting a LAN world. Silent as a client on a dedicated server. |
| `ALWAYS` | Always broadcast. Set this on the video operator’s machine. |
| `NEVER` | Receive only. |

The [handheld camera](/items/handheld-camera) is named per player (`MC Handheld <player>`) and has its own switch (`broadcast.handheld`, default `true`), so several operators can each carry one.

Drones publish as `MC Drone <id>` from the **same client** that broadcasts the other rigs.

Full defaults: [Client config](/reference/config).
