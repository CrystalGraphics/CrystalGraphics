#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas; // GL_RGBA16F MTSDF atlas, GL_LINEAR filtering
uniform float u_pxRange;   // SDF range in atlas pixels (default 6.0)

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

    // Use the MTSDF RGB median for body coverage. The alpha channel remains
    // available for separate rounded-distance effects, but mixing it into the
    // fill edge rounds convex corners that the MSDF channels preserve.
    float signedDistance = median(mtsdf.r, mtsdf.g, mtsdf.b);
    float screenPxDist = screenPxRange() * (signedDistance - 0.5);
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
