#version 330 core

// LED video wall simulation — Shimmer bloom variant.
//
// Identical optics to led_wall.fsh, but written as an MRT shader: it is drawn
// during Shimmer's post-entity pass where draw buffer 0 is the main framebuffer
// (the visible wall, pixel-identical to the normal pass) and draw buffer 1 is
// Shimmer's bloom source attachment. The emitted light lands in both, so the
// glow is generated from the wall's actual simulated LED output.
//
// LedParams  = (grid width in LEDs, grid height in LEDs, pixel gap fraction, brightness)
// LedParams2 = (panel gamma, mode, LEDs per panel, calibration variance)
// mode: 0 video, 1 colour bars, 2 alignment grid, 3 white, 4 red, 5 green, 6 blue, 7 checker

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec4 LedParams;
uniform vec4 LedParams2;
// (u offset, v offset, u scale, v scale) — the slice of the video this wall shows,
// same convention as led_wall.fsh (video-processor input window).
uniform vec4 UvRegion;

in vec2 texCoord0;
in vec4 vertexColor;

layout (location = 0) out vec4 fragColor;
layout (location = 1) out vec4 bloomColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 patternColor(vec2 cell, vec2 grid) {
    float mode = LedParams2.y;
    vec2 uv = (cell + 0.5) / grid;
    if (mode < 1.5) {
        // 75% colour bars with a grey-ramp strip at the bottom
        if (uv.y > 0.8) {
            return vec3(floor(uv.x * 16.0) / 15.0);
        }
        int i = int(clamp(floor(uv.x * 8.0), 0.0, 7.0));
        vec3 bars[8] = vec3[8](
            vec3(1.0, 1.0, 1.0), vec3(1.0, 1.0, 0.0), vec3(0.0, 1.0, 1.0), vec3(0.0, 1.0, 0.0),
            vec3(1.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), vec3(0.0, 0.0, 1.0), vec3(0.05, 0.05, 0.05));
        return bars[i] * 0.75;
    } else if (mode < 2.5) {
        // Panel alignment grid: cabinet borders, per-cabinet diagonal, wall outline
        float p = max(LedParams2.z, 2.0);
        vec2 m = mod(cell, p);
        float line = (m.x < 1.0 || m.y < 1.0) ? 1.0 : 0.0;
        vec2 lc = m / p;
        float diag = abs(lc.x - lc.y) < (1.5 / p) ? 0.35 : 0.0;
        float border = (cell.x < 1.0 || cell.y < 1.0 || cell.x > grid.x - 2.0 || cell.y > grid.y - 2.0) ? 1.0 : 0.0;
        return vec3(max(max(line, diag), border));
    } else if (mode < 3.5) {
        return vec3(1.0);
    } else if (mode < 4.5) {
        return vec3(1.0, 0.0, 0.0);
    } else if (mode < 5.5) {
        return vec3(0.0, 1.0, 0.0);
    } else if (mode < 6.5) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(mod(cell.x + cell.y, 2.0));
}

void main() {
    vec2 grid = LedParams.xy;
    vec2 g = texCoord0 * grid;
    vec2 cell = floor(clamp(g, vec2(0.0), grid - 1.0));
    vec2 f = g - cell;

    // --- Per-LED colour: each LED shows exactly one sample of the scaled feed,
    // like a real LED processor. LOD picks the mip matching feed px per LED.
    vec3 col;
    if (LedParams2.y < 0.5) {
        vec2 uvLed = UvRegion.xy + ((cell + 0.5) / grid) * UvRegion.zw;
        vec2 texSize = vec2(textureSize(Sampler0, 0)) * UvRegion.zw;
        float lod = max(0.0, log2(max(texSize.x / grid.x, texSize.y / grid.y)));
        col = textureLod(Sampler0, uvLed, lod).rgb;
    } else {
        col = patternColor(cell, grid);
    }

    // Decode with the configured panel gamma (linear light domain)
    col = pow(max(col, vec3(0.0)), vec3(LedParams2.x));

    // Per-LED calibration variance — uncalibrated modules never match perfectly
    col *= 1.0 + (hash12(cell) - 0.5) * LedParams2.w;

    // --- Pixel structure. Fade it out as LEDs shrink below ~1 screen pixel so the
    // wall resolves into a smooth image at distance (no shimmer/moiré, like eyes do).
    vec2 aa = min(fwidth(g) + vec2(1e-4), vec2(32.0));
    float ledsPerPixel = max(aa.x, aa.y);
    float structFade = clamp((ledsPerPixel - 0.5) / 1.5, 0.0, 1.0);

    float gap = LedParams.z;
    vec2 win = smoothstep(vec2(gap) - aa, vec2(gap) + aa, f)
             * (vec2(1.0) - smoothstep(vec2(1.0 - gap) - aa, vec2(1.0 - gap) + aa, f));
    float window = win.x * win.y;

    // Vertical R|G|B subpixel stripes inside the emitter window.
    //
    // sx spans the emitter, not the whole cell, so the antialiasing width has to be converted
    // into the same units — using aa.x raw under-smooths every stripe edge by the gap fraction.
    float emitter = max(1.0 - 2.0 * gap, 1e-3);
    float sx = clamp((f.x - gap) / emitter, 0.0, 1.0);
    float saa = aa.x / emitter;
    float s0 = smoothstep(0.3333 - saa, 0.3333 + saa, sx);
    float s1 = smoothstep(0.6667 - saa, 0.6667 + saa, sx);
    vec3 stripe = vec3(1.0 - s0, s0 * (1.0 - s1), s1);

    // A stripe thinner than a screen pixel cannot be blended by the display it is drawn on, so
    // it survives as a hard red or blue sliver rather than fusing into a colour: broad content
    // still averages out across neighbouring LEDs, but anything an LED or two wide — UI, text,
    // small icons — comes out chromatically shredded. Real emitters fuse in the eye at that
    // angular size; emulated ones have to be faded deliberately. Below ~3 screen pixels per
    // stripe, blend to a white emitter and keep only the per-LED window, which is the part of
    // the LED look that still resolves.
    float stripePx = emitter / (3.0 * max(aa.x, 1e-4));
    float subFade = clamp((stripePx - 1.0) / 2.0, 0.0, 1.0);
    stripe = mix(vec3(1.0), stripe, subFade);

    // Emitters are small but intense; partially compensate so perceived energy stays constant
    // while structure remains visible up close. A white emitter passes three times the energy of
    // a single stripe, so the fill factor has to track subFade — otherwise fading the stripes
    // out would brighten the wall as you walked away from it.
    float fillFactor = emitter * emitter / mix(1.0, 3.0, subFade);
    float comp = min(1.0 / max(fillFactor, 1e-3), 2.4);
    vec3 structured = col * stripe * window * comp;

    vec3 outCol = mix(structured, col, structFade);

    // Back to display gamma, apply drive brightness
    outCol = pow(max(outCol, vec3(0.0)), vec3(1.0 / 2.2));
    outCol *= LedParams.w;

    fragColor = vec4(outCol, 1.0) * vertexColor * ColorModulator;
    // Bloom source: a reduced-gain copy of the emitted light. Alpha 0 so the
    // composite pass never replaces the directly-rendered wall pixels — only
    // the blurred glow is added. Keeping the gain well under 1.0 also stops
    // camera->wall video feedback loops from amplifying into a white wash.
    bloomColor = vec4(fragColor.rgb * 0.4, 0.0);
}
