// A force field: a cellular energy shell, its edges lit, cells flaring at random and three impacts rolling ripples
// across it. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // A ripple from an impact at {@code at} on the unit sphere, struck {@code age} seconds ago.
    float shield_ripple(vec3 p, vec3 at, float age) {
        float d = acos(clamp(dot(p, at), -1.0, 1.0));
        float front = age * 1.6;
        float wave = exp(-pow((d - front) * 9.0, 2.0)) * exp(-age * 1.8);
        return wave + exp(-d * 9.0) * exp(-age * 6.0) * 2.0;
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 p = normalize(i.objPos);
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = abs(dot(n, v));
        vec3 cells = vfx_voronoi(p * 5.0);
        float edge = 1.0 - smoothstep(0.0, 0.06, cells.y - cells.x);
        float flare = pow(0.5 + 0.5 * sin(t * 2.5 + cells.z * 40.0), 12.0);
        float impacts = 0.0;
        for (int k = 0; k < 3; k++) {
            float period = 2.2 + float(k) * 0.7;
            float cycle = floor(t / period + float(k) * 0.37);
            vec3 at = normalize(vfx_hash33(vec3(cycle, float(k), 9.0)) - 0.5);
            impacts += shield_ripple(p, at, mod(t + float(k) * period * 0.37, period));
        }
        float rim = pow(1.0 - nv, 3.0);
        vec3 blue = vec3(0.25, 0.6, 1.6);
        vec3 color = blue * (0.04 + rim * 1.6 + edge * (0.25 + impacts * 1.5) + flare * 0.25 * (1.0 - edge))
                + vec3(0.7, 0.9, 1.4) * impacts * (0.35 + edge);
        if (!gl_FrontFacing) color *= 0.5;
        fragColor = vec4(color, 1.0);
    }
}
