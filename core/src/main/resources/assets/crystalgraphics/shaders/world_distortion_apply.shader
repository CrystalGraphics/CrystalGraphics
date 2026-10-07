// The world renderer's distortion apply: each pixel takes the scene's colour from where the Distortion passes' summed
// offset points (layer _Layer of _Distortion: xy in UV units, z the summed chromatic split, w the nearest haze's closeness, 1 / its eye
// depth; CG_DISTORTION writes it), once, after the transparent pass. UVs mirror at the screen's borders (Quantum Break's
// answer to clamping's smear). Only what is behind the haze is bent in, as Unreal's apply refuses scene nearer than the
// distorting surface: a bend whose farthest tap would land nearer than the nearest haze here is shortened until it stops
// at that edge, so the foreground is never pulled in and the bend fades to nothing at its silhouette rather than
// cutting off. It writes the depth of where it read too, so what draws after it (a sharp layer, the host's particles)
// is hidden by the bent scene rather than by the outline each thing had before. CgWorldRenderer draws it; nothing else
// should.
#type none

// It samples at most a bend away from its rect: a haze's largest, with its split, is under 0.1 of the height.
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "SceneColorMargin" = "0.1" }
Queue = "Overlay"

Properties {
    _Distortion ("Offsets", sampler2DArray) = "black"
    _Layer      ("Its layer", int) = 0
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite ON
        Cull OFF
    }

    // How much nearer than the haze a sample may be before it is foreground: a share of the eye depth, the depth
    // buffer's precision.
    const float CG_DISTORTION_LEAK = 0.02;
    // Halvings that find where a shortened bend stops: 1/32 of its length.
    const int CG_DISTORTION_STEPS = 5;
    // The largest split: overlapping hazes add theirs.
    const float CG_DISTORTION_SPLIT = 0.3;

    vec2 cg_mirror(vec2 uv) {
        return 1.0 - abs(1.0 - abs(uv));
    }

    // Whether the colour tap at uv + offset reads foreground: any of the four texels its bilinear footprint blends is
    // nearer than the haze, which starts at eye depth {@code haze}. Filtered depth, or one texel, lets a tap stopped at
    // an edge blend the foreground in.
    float cg_texel_depth(ivec2 texel) {
        return cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, texel, 0).r);
    }

    bool cg_foreground(vec2 uv, vec2 offset, float haze) {
        ivec2 size = textureSize(cg_DepthBuffer, 0);
        vec2 at = cg_mirror(uv + offset) * vec2(size) - 0.5;
        ivec2 a = clamp(ivec2(floor(at)), ivec2(0), size - 1), b = min(a + 1, size - 1);
        float nearest = min(min(cg_texel_depth(a), cg_texel_depth(ivec2(b.x, a.y))),
                            min(cg_texel_depth(ivec2(a.x, b.y)), cg_texel_depth(b)));
        return nearest < haze * (1.0 - CG_DISTORTION_LEAK);
    }

    // The offsets at a pixel, bilinear when the target is smaller than the screen, one texel a pixel when it is not;
    // the closeness the largest of the four, so a haze's edge keeps its depth. Takes the pixel, since helpers reach the
    // vertex stage too, which has no gl_FragCoord.
    vec4 cg_offsets(vec2 pixel) {
        ivec2 size = textureSize(_Distortion, 0).xy;
        vec2 at = pixel * (vec2(size) / CG_RESOLUTION) - 0.5;
        vec2 f = fract(at);
        ivec2 a = clamp(ivec2(floor(at)), ivec2(0), size - 1), b = min(a + 1, size - 1);
        vec4 aa = texelFetch(_Distortion, ivec3(a, _Layer), 0), ba = texelFetch(_Distortion, ivec3(b.x, a.y, _Layer), 0);
        vec4 ab = texelFetch(_Distortion, ivec3(a.x, b.y, _Layer), 0), bb = texelFetch(_Distortion, ivec3(b, _Layer), 0);
        vec4 d = mix(mix(aa, ba, f.x), mix(ab, bb, f.x), f.y);
        d.w = max(max(aa.w, ba.w), max(ab.w, bb.w));
        return d;
    }

    // One quad over CG_OBJECT_CUSTOM0, its hazes' rect as NDC corners x0, y0, x1, y1.
    void vertex(out v2f o) {
        vec2 p = mix(CG_OBJECT_CUSTOM0.xy, CG_OBJECT_CUSTOM0.zw, CG_VERTEX_CORNER);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 d = cg_offsets(gl_FragCoord.xy);
        if (d.x == 0.0 && d.y == 0.0) discard;
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        float split = clamp(d.z, -CG_DISTORTION_SPLIT, CG_DISTORTION_SPLIT);
        // The farthest tap, red's or blue's, is what must stay off the foreground.
        vec2 reach = d.xy * (1.0 + abs(split));
        float haze = 1.0 / max(d.w, 1.0e-4);
        vec2 offset = d.xy;
        if (cg_foreground(uv, reach, haze)) {
            float lo = 0.0, hi = 1.0;
            for (int k = 0; k < CG_DISTORTION_STEPS; k++) {
                float mid = 0.5 * (lo + hi);
                if (cg_foreground(uv, reach * mid, haze)) hi = mid; else lo = mid;
            }
            if (lo == 0.0) discard;
            offset *= lo;
        }
        vec2 from = cg_mirror(uv + offset);
        vec4 g = CG_SCENE_COLOR(from);
        fragColor = vec4(CG_SCENE_COLOR(cg_mirror(uv + offset * (1.0 + split))).r, g.g,
                         CG_SCENE_COLOR(cg_mirror(uv + offset * (1.0 - split))).b, g.a);
        // Unbent, a later draw's depth test cut the old outline out of it: a glow behind a bent block showed a dark copy.
        ivec2 size = textureSize(cg_DepthBuffer, 0);
        gl_FragDepth = texelFetch(cg_DepthBuffer, clamp(ivec2(from * vec2(size)), ivec2(0), size - 1), 0).r;
    }
}
