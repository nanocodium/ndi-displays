# OBS, Resolume, and the wall

The game only **receives** (and, for cameras, **sends**) NDI. Something else on the LAN has to publish a source first — usually [OBS](https://obsproject.com/) with [DistroAV](https://distroav.org/), or [Resolume Arena](https://resolume.com/).

Install the [NDI Runtime](/guide/install) first (Windows: [NDIRedistV6](http://ndi.link/NDIRedistV6)). Then build a [first wall](/guide/first-wall) and set **Pattern → NDI Video**.

## OBS → NDI

Install DistroAV (the current OBS NDI plugin). *Tools → DistroAV NDI output*: enable **Main** and, if you want a second feed, **Preview**. Those names are what Minecraft lists in the processor picker.

You can also add an **NDI Source** inside OBS to pull another machine (or NDI Tools Test Patterns) into a scene.

[Watch: using OBS with NDI (DistroAV)](https://www.youtube.com/watch?v=Hu9FwarhJcI)

<div class="wiki-embed">
  <iframe
    src="https://www.youtube-nocookie.com/embed/Hu9FwarhJcI"
    title="How to use OBS with NDI"
    allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
    allowfullscreen
  ></iframe>
</div>

## Patch a screen to a source

Right-click any cabinet, floor, round or curved mount. Pick the source (or type a short unique fragment of the name), set **NDI Video**, then **Apply to Wall**. The card does the same click: [NDI Configuration Card](/items/ndi-config-card).

<video class="wiki-clip" controls preload="metadata" src="/video/patch-screen-ndi.mp4">
  Your browser cannot play this clip. <a href="/video/patch-screen-ndi.mp4">Download the patch walkthrough</a>.
</video>

Name matching is exact first, then a case-insensitive substring. Prefer `Arena - Composition` over the full machine-prefixed string so a hostname change does not break the wall. Empty picker: [Troubleshooting](/guide/troubleshooting).

## Resolume Arena → NDI

Arena publishes the **composition** as an NDI source: **Output → Network streaming → NDI**. Every device on the LAN — including Minecraft — can take that feed. Incoming NDI shows under **Sources → NDI servers**; you do not enable anything extra to receive.

[Watch: NDI send and receive in Resolume Arena](https://www.youtube.com/watch?v=fjKfqIMts4A)

<div class="wiki-embed">
  <iframe
    src="https://www.youtube-nocookie.com/embed/fjKfqIMts4A"
    title="NDI output from Resolume Arena"
    allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
    allowfullscreen
  ></iframe>
</div>

vMix, hardware processors, and NDI Tools Studio Monitor work the same way: one source name on the LAN, then the in-game processor.
