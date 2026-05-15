#version 130

in vec2 v_uv;

out vec4 fragColor;

uniform sampler2D u_atlas;
uniform int u_atlasType; // 0 = bitmap (R8), 1 = MSDF (RGB16F)

void main() {
    if (u_atlasType == 0) {
        // Bitmap atlas: single red channel = grayscale coverage
        float alpha = texture2D(u_atlas, v_uv).r;
        fragColor = vec4(alpha, alpha, alpha, 1.0);
    } else {
        // MSDF atlas: show raw RGB channels
        vec3 rgb = texture2D(u_atlas, v_uv).rgb;
        fragColor = vec4(rgb, 1.0);
    }
}
