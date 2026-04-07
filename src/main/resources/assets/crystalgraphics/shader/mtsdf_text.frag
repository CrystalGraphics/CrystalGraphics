#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas; // GL_RGBA16F MTSDF atlas, GL_LINEAR filtering
uniform float u_pxRange;   // SDF range in atlas pixels (e.g. 4.0)

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec4 mtsdf = texture2D(u_atlas, v_uv);

    vec2 atlasSize = vec2(textureSize(u_atlas, 0));
    vec2 unitRange = vec2(u_pxRange) / atlasSize;
    vec2 screenTexSize = vec2(1.0) / fwidth(v_uv);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    float msdfDist = median(mtsdf.r, mtsdf.g, mtsdf.b);
    float sdfDist = mtsdf.a;
    float hybridDist = max(min(msdfDist, sdfDist), max(msdfDist + sdfDist - 1.0, 0.0));
    float disagreement = abs(msdfDist - sdfDist);
    float sdfFallback = smoothstep(0.03, 0.10, disagreement);
    float signedDistance = mix(hybridDist, sdfDist, sdfFallback);
    float screenPxDist = screenPxRange * (signedDistance - 0.5);
    float opacity = clamp(screenPxDist + 0.5, 0.0, 1.0);
    float alpha = v_color.a * opacity;

    //General alpha test
    if (alpha <= (1.0 / 255.0)) { //0.003
        discard;
    }
    
    //We still want to see those, but dont to modify the depth
     if (alpha <= 0.01) {
        gl_FragDepth = 1;
    }

    fragColor = vec4(v_color.rgb, alpha);
}
