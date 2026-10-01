// Faceted ice: flat crystal faces, blue light glowing up from inside, frost along every edge and a glint now and
// then on a face that catches the key. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // The nearest facet's centre on the unit sphere, and how far this point is from that facet's edge.
    vec4 ice_facet(vec3 p) {
        vec3 cell = floor(p);
        vec3 f = fract(p);
        float best = 8.0, second = 8.0;
        vec3 centre = vec3(0.0);
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec3 o = vec3(float(x), float(y), float(z));
                    vec3 point = o + vfx_hash33(cell + o);
                    float d = length(point - f);
                    if (d < best) {
                        second = best;
                        best = d;
                        centre = cell + point;
                    } else if (d < second) {
                        second = d;
                    }
                }
            }
        }
        return vec4(centre, second - best);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec4 facet = ice_facet(i.objPos * 3.2);
        // A facet faces the way its centre points from the sphere's: flat, with a hard edge to the next.
        vec3 n = normalize(CG_NORMAL_MATRIX * normalize(facet.xyz / 3.2));
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        vec3 surface = vfx_pbr(n, v, vec3(0.75, 0.9, 1.0), 0.0, 0.06, 1.0);
        vec3 inside = vfx_env(refract(-v, n, 0.76), 0.35) * vec3(0.35, 0.7, 1.0);
        // Light scattered inside the ice, strongest at the silhouette.
        vec3 scatter = vec3(0.15, 0.5, 1.2) * (0.35 + 1.4 * pow(1.0 - nv, 2.0))
                * (0.6 + 0.4 * vfx_fbm(i.objPos * 4.0 + vec3(0.0, t * 0.05, 0.0), 4));
        float frost = 1.0 - smoothstep(0.0, 0.07, facet.w);
        float sparkle = step(0.97, vfx_hash31(floor(facet.xyz * 10.0) + floor(t * 3.0)))
                * pow(max(dot(reflect(-v, n), VFX_KEY_DIR), 0.0), 8.0) * 6.0;
        vec3 color = surface * 0.7 + inside * 0.6 + scatter + vec3(0.85, 0.95, 1.0) * frost * 0.9 + sparkle;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
