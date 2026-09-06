---
layout: home
hero:
  name: NDI Stage Displays
  text: Real NDI video in Minecraft
  tagline: Forge 1.20.1 LED walls, kinetic parks, and broadcast cameras that speak the same protocol as OBS, Resolume, and vMix.
  actions:
    - theme: brand
      text: Install
      link: /guide/install
    - theme: alt
      text: Block catalog
      link: /blocks/
    - theme: alt
      text: GitHub
      link: https://github.com/nanocodium/ndi-displays
features:
  - title: In
    details: Live NDI from OBS, a media server, or another machine on the LAN.
  - title: Through
    details: Walls, floors, curves, projectors, kinetic tiles, chain hoists, switchers, racks, and a processor GUI.
  - title: Out
    details: Broadcast, PTZ, jib, dolly, handheld, drone, computer, and switcher program as real NDI sources.
---

Every client pulls NDI itself — no server transcode, no shared texture upload. Walls look like rental cabinets (pitch, subpixels, bezels, gamma). Cameras publish the world back onto the same network, so a jib can land on the IMAG wall in the same world.

![Control room with mixing desk and an NDI wall](/img/hero/recording_studio.png)

![Stage with LED tiles flown on kinetic winches](/img/hero/stage_winch_leds.png)

## Start here

- [Install the NDI Runtime](/guide/install) (Windows redistributable, Tools, SDK)
- [Build a first LED wall](/guide/first-wall)
- [OBS, Resolume, and the wall](/guide/ndi-software) — DistroAV output, patch a cabinet, Arena NDI
- [Block catalog](/blocks/) generated from `NdiDisplays.java`
- [Video Projector](/blocks/projector) — throw NDI onto the world
- [Computer](/blocks/computer) · [Vision Switcher](/blocks/vision-switcher) · [Equipment Rack](/blocks/equipment-rack)
- [Chain Hoist](/blocks/chain-hoist) — fly truss, SEF speakers, scenery
- [Hoist Remote](/items/hoist-remote) — yellow belly-box, groups, e-stop
- [Troubleshooting](/guide/troubleshooting) if the picker is empty or walls stay on colour bars

Wiki: [https://wiki.nailec.fr](https://wiki.nailec.fr)
