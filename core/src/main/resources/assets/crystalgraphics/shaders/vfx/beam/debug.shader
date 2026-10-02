// A tube layer that shows its own construction: a band every block along the path, eight sectors around it, and a
// white line where the rotation-minimising normal points, which must run straight however the path bends. Colour A
// tints it, colour B is the line. For checking CgVfxTube and CgVfxPath, not for looking at.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath ("Path rings", sampler2D) = "black"
}

struct v2f { vec3 world; vec2 surface; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.surface = vec2(v.ring.arc, v.angle);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float arc = i.surface.x, angle = i.surface.y;
        float band = abs(fract(arc) - 0.5);
        float bandAa = max(fwidth(arc), 1.0e-4);
        float bands = smoothstep(0.25 - bandAa, 0.25 + bandAa, band);
        float sector = mod(floor(angle * 8.0), 2.0);
        float seam = min(angle, 1.0 - angle) * 24.0;
        float line = 1.0 - smoothstep(0.5, 0.5 + fwidth(seam) * 1.5, seam);
        vec3 col = CG_OBJECT_CUSTOM2.rgb * (0.25 + 0.35 * sector + 0.4 * bands);
        col = mix(col, CG_OBJECT_CUSTOM3.rgb, line);
        fragColor = vec4(col * (gl_FrontFacing ? 0.8 : 0.35), 1.0);
    }
}
