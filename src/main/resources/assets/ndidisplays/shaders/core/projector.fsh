#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

// x: brightness, y: soft-edge feather width (fraction of frame), z: pattern mode, w: additive flag
uniform vec4 ProjParams;
// The projector's full optical transform, from this drape's local space to frame clip space.
uniform mat4 ProjectorMat;
// The shadow pass's transform (padded symmetric frustum, aspect 1) into the depth map.
uniform mat4 ShadowMat;
// x: depth bias in METRES, y: shadow-map texel size, z: map available (1/0), w: pass far plane
uniform vec4 ShadowParams;

// Lens position in the same local space as localPos (w unused).
uniform vec4 LensPos;

in vec3 localPos;
in vec4 vertexColor;
in vec3 faceNormal;

out vec4 fragColor;

// The projector's frame content: mode 0 samples the video; the rest are the same test patterns
// the LED screens use, generated in frame space so a calibration grid projected onto a facade
// shows exactly how the optics land on the architecture.
vec3 frame(vec2 uv, float mode) {
    if (mode < 0.5) {
        return texture(Sampler0, uv).rgb;
    }
    if (mode < 1.5) { // SMPTE-ish bars
        float band = floor(uv.x * 8.0);
        if (band < 1.0) return vec3(1.0);
        if (band < 2.0) return vec3(1.0, 1.0, 0.0);
        if (band < 3.0) return vec3(0.0, 1.0, 1.0);
        if (band < 4.0) return vec3(0.0, 1.0, 0.0);
        if (band < 5.0) return vec3(1.0, 0.0, 1.0);
        if (band < 6.0) return vec3(1.0, 0.0, 0.0);
        if (band < 7.0) return vec3(0.0, 0.0, 1.0);
        return vec3(0.05);
    }
    if (mode < 2.5) { // alignment grid + centre crosshair + frame border
        vec2 cell = fract(uv * 8.0);
        float line = step(cell.x, 0.04) + step(0.96, cell.x)
                   + step(cell.y, 0.04) + step(0.96, cell.y);
        float cross = step(abs(uv.x - 0.5), 0.004) + step(abs(uv.y - 0.5), 0.004);
        float border = step(uv.x, 0.01) + step(0.99, uv.x) + step(uv.y, 0.01) + step(0.99, uv.y);
        vec3 c = vec3(0.06) + vec3(0.9) * clamp(line, 0.0, 1.0);
        c = mix(c, vec3(1.0, 0.2, 0.2), clamp(cross, 0.0, 1.0));
        c = mix(c, vec3(0.2, 1.0, 0.4), clamp(border, 0.0, 1.0));
        return c;
    }
    if (mode < 3.5) return vec3(1.0);
    if (mode < 4.5) return vec3(1.0, 0.0, 0.0);
    if (mode < 5.5) return vec3(0.0, 1.0, 0.0);
    if (mode < 6.5) return vec3(0.0, 0.0, 1.0);
    // checker
    vec2 sq = floor(uv * 8.0);
    return vec3(mod(sq.x + sq.y, 2.0));
}

void main() {
    // Per-fragment projective texturing: local position through the projector's matrices,
    // perspective divide included — exact at every pixel, at any angle and distance.
    vec4 pp = ProjectorMat * vec4(localPos, 1.0);
    if (pp.w <= 0.0001) {
        discard; // behind the lens
    }
    vec2 uv = vec2(pp.x / pp.w * 0.5 + 0.5, 0.5 - pp.y / pp.w * 0.5);
    // Outside the frame no light lands: this is the frustum clip in frame space.
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        discard;
    }

    // Shadow-map occlusion: the fragment is lit only if nothing sits nearer the lens along its
    // own ray. Grazing surfaces get two defences against acne — the lookup point is pushed off
    // the surface along its normal (more the shallower the beam), and the depth bias is
    // slope-scaled — then 3x3 PCF softens the silhouette by about a map texel.
    float shadow = 1.0;
    if (ShadowParams.z > 0.5) {
        vec3 n = normalize(faceNormal);
        float cosI = clamp(abs(dot(n, normalize(localPos - LensPos.xyz))), 0.05, 1.0);
        vec3 lookup = localPos + n * (0.02 + 0.14 * (1.0 - cosI));
        vec4 sp = ShadowMat * vec4(lookup, 1.0);
        if (sp.w > 0.0001) {
            vec3 suv = sp.xyz / sp.w * 0.5 + 0.5;
            if (suv.x > 0.0 && suv.x < 1.0 && suv.y > 0.0 && suv.y < 1.0 && suv.z < 1.0) {
                // Compare in LINEAR distance, bias in metres. NDC depth packs the whole far
                // field into a sliver near 1.0 — at 20 m a one-block wall is ~0.001 of depth,
                // smaller than any workable NDC bias, so light leaked straight through walls.
                float near = 0.05;
                float far = ShadowParams.w;
                float fragLin = 2.0 * near * far / (far + near - (suv.z * 2.0 - 1.0) * (far - near));
                float bias = ShadowParams.x * (1.0 + 2.0 * (1.0 - cosI) / cosI);
                bias = min(bias, 0.9);
                float texel = ShadowParams.y;
                float lit = 0.0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        float d = texture(Sampler1, suv.xy + vec2(dx, dy) * texel).r;
                        float mapLin = 2.0 * near * far / (far + near - (d * 2.0 - 1.0) * (far - near));
                        lit += step(fragLin - bias, mapLin);
                    }
                }
                shadow = lit / 9.0;
            }
        }
        if (shadow <= 0.001) {
            discard;
        }
    }
    // Soft edge: real blend feathering, distance-to-edge in frame space.
    float f = 1.0;
    float feather = ProjParams.y;
    if (feather > 0.0001) {
        float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
        f = smoothstep(0.0, feather, edge);
    }
    vec3 c = frame(uv, ProjParams.z);
    // vertexColor carries the incidence shading (grazing surfaces catch less beam).
    float intensity = ProjParams.x * f * vertexColor.r * shadow;
    if (ProjParams.w > 0.5) {
        // Additive: light onto the world; black in the frame projects nothing.
        fragColor = vec4(c * intensity, 1.0);
    } else {
        // Replace: opaque image, still feathered at the edges.
        fragColor = vec4(c * ProjParams.x * vertexColor.r, f * shadow);
    }
}
