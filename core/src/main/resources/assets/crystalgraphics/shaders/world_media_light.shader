// The light the smoke and gas in the air scattered toward the eye (CgWorldMedia, lit by CgWorldLights), added over the
// opaque scene at each pixel's depth, out to the grid's far end where it is sky: Unreal's volumetric fog's in-scatter
// (HeightFogCommon.ush's CombineVolumetricFog), without dimming what is behind, which the medium's own drawing does.
// Full screen, before the transparent passes, so what draws over it covers it.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" "Media" = "Off" }
Queue = "Overlay"

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
        float window = texelFetch(cg_DepthBuffer, ivec2(gl_FragCoord.xy), 0).r;
        bool sky = CG_DEPTH_REVERSED ? window <= 0.0 : window >= 1.0;
        vec3 light = cg_MediaLight(gl_FragCoord.xy / CG_RESOLUTION, sky ? CG_MEDIA_FAR : cg_LinearEyeDepth(window));
        // An 8-bit target holds sRGB: the light added near as it would add in linear light.
        fragColor = vec4(CG_LINEAR_SCENE ? light : pow(light, vec3(1.0 / 2.2)), 0.0);
    }
}
