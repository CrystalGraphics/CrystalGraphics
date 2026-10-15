// The world renderer's volumes blended with their last frame (CgWorldRenderer.Draw.halfResolution, a volume), texel for
// texel at half size: this frame's light, premultiplied, over last frame's reprojected to where this texel's volume was
// then (through cg_PrevViewProj, at the depth it was marched to, else the scene's) and held to this frame's 3x3
// neighbourhood by variance clipping, so smoke that moved or a view it hid leaves no ghost. After Karis, "High Quality
// Temporal Supersampling" (2014), and Salvi, "An Excursion in Temporal Supersampling" (2016).
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Now      ("This frame's volumes, premultiplied",        sampler2D) = "black"
    _NowDepth ("The eye depth each was marched to; 0 for none", sampler2D) = "black"
    _Last     ("Last frame's, resolved",                     sampler2D) = "black"
    _Fresh    ("1 the frame its history is made: nothing to blend", float) = 1.0
    _Blend    ("This frame's share of a texel it reprojects", float) = 0.1
    _Spread   ("Standard deviations the neighbourhood spans", float) = 1.25
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

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 p = ivec2(gl_FragCoord.xy), size = textureSize(_Now, 0);
        vec4 now = texelFetch(_Now, p, 0);
        if (_Fresh > 0.5 || !CG_HISTORY_VALID || cg_ProjMatrix[2][3] == 0.0) {
            fragColor = now;
            return;
        }
        vec4 sum = vec4(0.0), squares = vec4(0.0);
        for (int k = 0; k < 9; k++) {
            vec4 c = texelFetch(_Now, clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), size - 1), 0);
            sum += c;
            squares += c * c;
        }
        vec4 mean = sum / 9.0, spread = sqrt(max(squares / 9.0 - mean * mean, 0.0)) * _Spread;
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        float eye = texelFetch(_NowDepth, p, 0).x;
        if (eye <= 0.0) eye = min(CG_SCENE_EYE_DEPTH(uv), 1.0e4);
        // This texel's point, camera-relative: its ray at that eye depth, through the view's inverse.
        vec2 ndc = uv * 2.0 - 1.0;
        vec3 seen = vec3((ndc.x + cg_ProjMatrix[2][0]) / cg_ProjMatrix[0][0], (ndc.y + cg_ProjMatrix[2][1]) / cg_ProjMatrix[1][1], -1.0) * eye;
        vec4 then = cg_PreviousClip((inverse(cg_ViewMatrix) * vec4(seen, 1.0)).xyz);
        vec2 last = then.xy / then.w * 0.5 + 0.5;
        if (then.w <= 0.0 || any(lessThan(last, vec2(0.0))) || any(greaterThan(last, vec2(1.0)))) {
            fragColor = now;
            return;
        }
        vec4 history = clamp(texture(_Last, last), mean - spread, mean + spread);
        fragColor = mix(history, now, _Blend);
    }
}
