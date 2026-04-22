#version 130

in vec2 v_uv;
in vec4 v_color;
in vec2 v_worldPos;   // <-- add this

out vec4 fragColor;

uniform sampler2D u_atlas;
uniform float u_pxRange;

uniform float u_time;
const float u_rainbowScale = 100;  // world-units per full colour cycle, e.g. 200.0
const float u_scrollSpeed = 0.5;   // cycles per second, e.g. 0.5

vec3 rainbow(float t) {
    return vec3(
    sin(t),
    sin(t + 2.094),
    sin(t + 4.188)
    ) * 0.5 + 0.5;
}

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

float screenPxRange() {
    vec2 atlasSize = vec2(textureSize(u_atlas, 0));
    vec2 unitRange = vec2(u_pxRange) / atlasSize;
    vec2 uvFwidth = max(fwidth(v_uv), vec2(1.0e-6));
    vec2 screenTexSize = vec2(1.0) / uvFwidth;
    return max(0.5 * dot(unitRange, screenTexSize), 1.0);
}

void main() {
    vec4 mtsdf = texture2D(u_atlas, v_uv);

    float signedDistance = median(mtsdf.r, mtsdf.g, mtsdf.b);
    float screenPxDist = screenPxRange() * (signedDistance - 0.5);
    float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
    float alpha = v_color.a * opacity;

    if (alpha <= (1.0 / 255.0)) {
        discard;
    }

    if (alpha <= 0.01) {
        gl_FragDepth = 1;
    }

    // Phase driven by world X + time scroll
    float phase = (v_worldPos.x / u_rainbowScale + u_time * u_scrollSpeed) * 6.2832;

    fragColor = vec4(v_color.rgb, alpha);// * vec4(rainbow(phase), 1.0);
//    fragColor = vec4(mod(v_worldPos.x / u_rainbowScale, 1.0), 0.0, 0.0, alpha);
}

