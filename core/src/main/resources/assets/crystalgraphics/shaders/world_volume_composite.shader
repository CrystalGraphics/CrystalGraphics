// The world renderer's volumes laid over its target in the transparent pass (CgWorldRenderer.Draw.halfResolution, a
// volume): their resolved half-size light, premultiplied, upsampled as world_half_upsample.shader upsamples added light
// (each pixel weighing the four texels round it by bilinear distance and by how near the scene's depth where each was
// marched is to its own, the nearest in depth where none agrees), then blended over what is behind it.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Transparent"

Properties {
    _Volumes ("Resolved volumes, half size, premultiplied", sampler2D) = "black"
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
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
        ivec2 size = textureSize(_Volumes, 0);
        vec2 p = uv * vec2(size) - 0.5;
        ivec2 base = ivec2(floor(p));
        vec2 f = p - vec2(base);
        vec4 sum = vec4(0.0), nearest = vec4(0.0);
        float total = 0.0, best = 1.0e30;
        for (int k = 0; k < 4; k++) {
            ivec2 o = ivec2(k & 1, k >> 1);
            ivec2 t = clamp(base + o, ivec2(0), size - 1);
            float seen = CG_SCENE_EYE_DEPTH((vec2(t) + 0.5) / vec2(size));
            float gap = abs(seen - depth) / max(depth, 1.0e-3);
            vec4 c = texelFetch(_Volumes, t, 0);
            if (gap < best) {
                best = gap;
                nearest = c;
            }
            float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) * exp(-gap * 50.0);
            sum += c * w;
            total += w;
        }
        vec4 volumes = total > 1.0e-4 ? sum / total : nearest;
        if (volumes.a < 1.0e-4 && max(volumes.r, max(volumes.g, volumes.b)) < 1.0e-4) discard;
        fragColor = volumes;
    }
}
