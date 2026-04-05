#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas; // GL_RGB16F MSDF atlas, GL_LINEAR filtering
uniform float u_pxRange;   // SDF range in atlas pixels (e.g. 4.0)

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 msdf = texture2D(u_atlas, v_uv).rgb;

    vec2 atlasSize = vec2(textureSize(u_atlas, 0));
    vec2 unitRange = vec2(u_pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(v_uv);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    float sd = median(msdf.r, msdf.g, msdf.b);
    float screenPxDist = screenPxRange * (sd - 0.5);
    float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
    float alpha = v_color.a * opacity;

    if (alpha <= (1.0 / 255.0)) {
        discard;
    }

    fragColor = vec4(v_color.rgb, alpha);
}
