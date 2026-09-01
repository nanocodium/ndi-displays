#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Inverse of the pose the vertices were baked through: Position arrives in camera-relative
// view space, and the projective math needs the original block-local coordinates back.
uniform mat4 InvPoseMat;

out vec3 localPos;
out vec4 vertexColor;
out vec3 faceNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    localPos = (InvPoseMat * vec4(Position, 1.0)).xyz;
    vertexColor = Color;
    // UV0 = normal.xy at full precision; z's magnitude from unit length, its sign from Color.g.
    float nz = sqrt(max(0.0, 1.0 - dot(UV0, UV0))) * (Color.g > 0.5 ? 1.0 : -1.0);
    faceNormal = vec3(UV0, nz);
}
