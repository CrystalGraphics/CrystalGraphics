// Bloom's source under the HDR scene: the light past _Threshold in each 2x2 block of the scene, averaged into one
// half-size texel. Each texel's excess is pulled toward _Highlight (Karis's weighting as a soft cap), so a lone hot
// texel cannot flicker the glow. One triangle over the half-size target. CgBloom draws it.
// UNREAL_SETUP is Unreal's instead (PostProcessBloom.usf's BloomSetup on the half-size scene): the 2x2 average kept
// saturate((luminance - _Threshold) / 2) of, or whole at a threshold of -1 or less, where Unreal runs no setup.
#type none
#pragma cg_feature UNREAL_SETUP
#pragma cg_permutation UNREAL_SETUP

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Target" }
Queue = "Overlay"

Properties {
    _Scene     ("The linear HDR scene", sampler2D) = "black"
    _Threshold ("Light past this blooms", float) = 1.0
    _Highlight ("Where an excess is held to", float) = 32.0
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    vec3 excess(ivec2 p, ivec2 size) {
        vec3 c = max(texelFetch(_Scene, min(p, size - 1), 0).rgb - _Threshold, 0.0);
        return c / (1.0 + max(c.r, max(c.g, c.b)) / _Highlight);
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 size = textureSize(_Scene, 0);
        ivec2 p = ivec2(gl_FragCoord.xy) * 2;
#ifdef UNREAL_SETUP
        vec3 c = 0.25 * (texelFetch(_Scene, min(p, size - 1), 0).rgb + texelFetch(_Scene, min(p + ivec2(1, 0), size - 1), 0).rgb
                + texelFetch(_Scene, min(p + ivec2(0, 1), size - 1), 0).rgb + texelFetch(_Scene, min(p + ivec2(1, 1), size - 1), 0).rgb);
        // Luminance() is dot(rgb, (0.3, 0.59, 0.11)) in Unreal's Common.ush
        if (_Threshold > -1.0) c *= clamp((dot(c, vec3(0.3, 0.59, 0.11)) - _Threshold) * 0.5, 0.0, 1.0);
        fragColor = vec4(max(c, vec3(0.0)), 1.0);
        return;
#endif
        vec3 sum = excess(p, size) + excess(p + ivec2(1, 0), size) + excess(p + ivec2(0, 1), size) + excess(p + ivec2(1, 1), size);
        fragColor = vec4(sum * 0.25, 1.0);
    }
}
