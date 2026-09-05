#version 150

// Blow-through (transparent / mesh) LED wall simulation.
//
// Same optics as led_wall.fsh, but the inter-emitter area is genuinely open: those
// fragments are discarded rather than shaded, so the world, and any lighting rig
// behind the screen, reads straight through — which is the entire point of a
// blow-through cabinet. Discarding (instead of blending alpha 0) also keeps the
// depth buffer honest, so the gaps do not occlude what is behind them.
//
// LedParams  = (grid width in LEDs, grid height in LEDs, pixel gap fraction, brightness)
// LedParams2 = (panel gamma, mode, LEDs per panel, calibration variance)
// mode: 0 video, 1 colour bars, 2 alignment grid, 3 white, 4 red, 5 green, 6 blue, 7 checker

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec4 LedParams;
uniform vec4 LedParams2;
// (u offset, v offset, u scale, v scale) — slice of the video this surface shows;
// walls use (0,0,1,1), kinetic tiles their rectangle of the shared canvas.
uniform vec4 UvRegion;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

/**
 * Emitter coverage of a blow-through cabinet. Real transparent LED is a grid of thin
 * strips: narrow in one axis, very open in the other. STRIP_W is the fraction of the
 * cell the emitter spans horizontally, STRIP_H vertically — the product is roughly the
 * opacity, so ~60% here and ~40% transparent. Denser than a real mesh product, deliberately:
 * output colour clamps at white, so coverage is the only thing that can make the wall read
 * bright in daylight, and at a third open it was hard to see at all.
 */
const float STRIP_W = 0.80;
const float STRIP_H = 0.75;

/**
 * Emitter drive, relative to a solid cabinet, in linear light and constant at every
 * distance. Real transparent LED is driven far harder than a solid wall so it still reads
 * against daylight through 78% open area, and this stands in for that.
 *
 * It must not vary with distance. Screen brightness here is the product of the emitter
 * area actually covered and this gain; making either one distance-dependent makes the wall
 * change brightness as the camera moves, which is what an earlier version did.
 */
const float EMITTER_GAIN = 4.2;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 patternColor(vec2 cell, vec2 grid) {
    float mode = LedParams2.y;
    vec2 uv = (cell + 0.5) / grid;
    if (mode < 1.5) {
        if (uv.y > 0.8) {
            return vec3(floor(uv.x * 16.0) / 15.0);
        }
        int i = int(clamp(floor(uv.x * 8.0), 0.0, 7.0));
        vec3 bars[8] = vec3[8](
            vec3(1.0, 1.0, 1.0), vec3(1.0, 1.0, 0.0), vec3(0.0, 1.0, 1.0), vec3(0.0, 1.0, 0.0),
            vec3(1.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), vec3(0.0, 0.0, 1.0), vec3(0.05, 0.05, 0.05));
        return bars[i] * 0.75;
    } else if (mode < 2.5) {
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

    // --- Emitter mask. Centred strip within the cell; everything else is open air.
    vec2 aa = min(fwidth(g) + vec2(1e-4), vec2(32.0));
    vec2 halfSize = vec2(STRIP_W, STRIP_H) * 0.5;
    vec2 d = abs(f - 0.5);
    vec2 cover = (1.0 - smoothstep(halfSize - aa, halfSize + aa, d));
    float mask = cover.x * cover.y;

    // Beyond the distance where a whole cell is smaller than a screen pixel the strips
    // can no longer be resolved; fall back to the cell's average coverage so the wall
    // fades to an even haze instead of aliasing into noise.
    float ledsPerPixel = max(aa.x, aa.y);
    float structFade = clamp((ledsPerPixel - 0.5) / 1.5, 0.0, 1.0);
    float coverage = mix(mask, STRIP_W * STRIP_H, structFade);
    if (coverage < 0.004) {
        discard;
    }

    vec3 col;
    if (LedParams2.y < 0.5) {
        vec2 uvLed = UvRegion.xy + ((cell + 0.5) / grid) * UvRegion.zw;
        vec2 texSize = vec2(textureSize(Sampler0, 0)) * UvRegion.zw;
        float lod = max(0.0, log2(max(texSize.x / grid.x, texSize.y / grid.y)));
        col = textureLod(Sampler0, uvLed, lod).rgb;
    } else {
        col = patternColor(cell, grid);
    }

    col = pow(max(col, vec3(0.0)), vec3(LedParams2.x));
    col *= 1.0 + (hash12(cell) - 0.5) * LedParams2.w;

    col *= EMITTER_GAIN;

    col = pow(max(col, vec3(0.0)), vec3(1.0 / 2.2));
    col *= LedParams.w;

    // Emitters are opaque. Up close a strip pixel is solid picture and the gaps between
    // strips are discarded, so the world shows through the open area. Once a cell is smaller
    // than a screen pixel the strips cannot be resolved and the wall reads as a solid
    // picture at full brightness rather than as a 60% haze: alpha follows the anti-aliased
    // strip edge only, never the average coverage. An earlier version used coverage as alpha
    // so the mean light stayed constant with distance, but that made the picture dim at any
    // distance where strips blur — the colour clamps at white, so it could not be driven
    // harder to compensate. Brightness wins over the far-field haze here.
    float alpha = mix(clamp(mask / max(STRIP_W * STRIP_H, 1e-3), 0.0, 1.0), 1.0, structFade);
    fragColor = vec4(col, alpha) * vertexColor * ColorModulator;
}
