// The sun's shadow map (CgWorldLights): our opaque meshes drawn into its atlas, a cascade a quadrant, as Unreal lays a
// directional light's cascades out in one shadow depth texture. Each pixel holds the nearest caster's depth from the
// light in blocks: in colour, because the graph samples no depth attachment; the depth attachment only keeps the nearest.
// Unreal's ShadowDepthPixelShader.usf, depth only.
//
// Each instance carries its cascade, not the pass: CG_OBJECT_CUSTOM0 and 1 the rows from eye-relative world to the
// cascade's x and y (-1 to 1 across it), 2 the row to its depth from the light, 3 its quadrant's centre in the atlas,
// 1 / its far depth and its depth bias in blocks. A fragment outside its cascade is discarded rather than spilling into
// the next quadrant.
#type spatial

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Geometry"

struct v2f { float depth; vec2 cascade; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend      OFF
        DepthTest  LEQUAL
        DepthWrite ON
        Cull       OFF
    }

    void vertex(out v2f o) {
        vec4 w = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.cascade = vec2(dot(CG_OBJECT_CUSTOM0, w), dot(CG_OBJECT_CUSTOM1, w));
        // Unreal's caster bias (ShadowDepthCommon.ush): the constant, plus three times it by the slope to the light, the
        // slope at most 1 (r.Shadow.CSMSlopeScaleDepthBias, r.Shadow.MaxSlopeScaleDepthBias).
        vec3 n = normalize(mat3(CG_OBJECT_TO_WORLD) * cg_Normal);
        float nol = abs(dot(n, CG_OBJECT_CUSTOM2.xyz));
        float slope = nol > 0.0 ? clamp(sqrt(clamp(1.0 - nol * nol, 0.0, 1.0)) / nol, 0.0, 1.0) : 1.0;
        o.depth = dot(CG_OBJECT_CUSTOM2, w) + CG_OBJECT_CUSTOM3.w * (1.0 + 3.0 * slope);
        // The target's depth convention, as the frame block carries it: reversed, and 0 to 1 or -1 to 1.
        float z = clamp(o.depth * CG_OBJECT_CUSTOM3.z, 0.0, 1.0);
        if (cg_DepthParams.x > 0.5) z = 1.0 - z;
        if (cg_DepthParams.y < 0.5) z = z * 2.0 - 1.0;
        gl_Position = vec4(o.cascade * 0.5 + CG_OBJECT_CUSTOM3.xy, z, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (any(greaterThan(abs(i.cascade), vec2(1.0)))) discard;
        fragColor = vec4(i.depth, 0.0, 0.0, 1.0);
    }
}
