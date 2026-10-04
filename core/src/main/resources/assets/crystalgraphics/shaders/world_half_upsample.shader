// The world renderer's half-resolution draws added over its target (CgWorldRenderer.Draw.halfResolution): a joint
// bilateral upsample. Each pixel weighs the four half-size texels round it by distance, as bilinear filtering would,
// and by how close the scene's depth where each texel was drawn is to its own, so light on a far wall does not bleed
// onto a near silhouette. Where none agrees (a thin foreground edge), the texel nearest in depth. After Kopf et al.,
// "Joint Bilateral Upsampling" (2007), as GPU Gems 3 ch. 23 composites off-screen particles.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Half ("Half-resolution light", sampler2D) = "black"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        float depth = CG_SCENE_EYE_DEPTH(uv);
        ivec2 size = textureSize(_Half, 0);
        vec2 p = uv * vec2(size) - 0.5;
        ivec2 base = ivec2(floor(p));
        vec2 f = p - vec2(base);
        vec3 sum = vec3(0.0), nearest = vec3(0.0);
        float total = 0.0, best = 1.0e30;
        for (int k = 0; k < 4; k++) {
            ivec2 o = ivec2(k & 1, k >> 1);
            ivec2 t = clamp(base + o, ivec2(0), size - 1);
            // The depth the half-size draw saw: its texel's centre, read as the draw read it.
            float seen = CG_SCENE_EYE_DEPTH((vec2(t) + 0.5) / vec2(size));
            float gap = abs(seen - depth) / max(depth, 1.0e-3);
            vec3 c = texelFetch(_Half, t, 0).rgb;
            if (gap < best) {
                best = gap;
                nearest = c;
            }
            float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) * exp(-gap * 50.0);
            sum += c * w;
            total += w;
        }
        fragColor = vec4(total > 1.0e-4 ? sum / total : nearest, 0.0);
    }
}
