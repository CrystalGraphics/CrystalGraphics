// Chameleon pearl car paint: teal where it faces you, through violet and magenta to gold at the edges; fine flakes
// that glint only where they catch a light; and a mirror clear coat over it all, reflecting the studio and the floor.
// CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // The pearl's colour seen at {@code nv}, the cosine of the viewing angle: gold at the edges, teal facing you.
    vec3 carpaint_flip(float nv) {
        vec3 c = mix(vec3(1.0, 0.6, 0.1), vec3(0.8, 0.05, 0.42), smoothstep(0.08, 0.3, nv));
        c = mix(c, vec3(0.3, 0.07, 0.9), smoothstep(0.28, 0.55, nv));
        return mix(c, vec3(0.0, 0.62, 0.78), smoothstep(0.55, 0.92, nv));
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        vec3 base = carpaint_flip(nv);
        vec3 paint = vfx_pbr_studio(i.worldPos, floorY, n, v, base, 0.55, 0.38);
        // Flakes: small round mirrors, each tilted its own way, glinting where one reflects a softbox or the horizon.
        vec3 cells = vfx_voronoi(i.objPos * 150.0);
        float flake = smoothstep(0.3, 0.12, cells.x) * step(0.4, cells.z);
        vec3 flakeNormal = normalize(n + (vfx_hash33(vec3(cells.z * 131.0, 7.0, 3.0)) - 0.5) * 0.45);
        vec3 seen = vfx_studio(i.worldPos, reflect(-v, flakeNormal), 0.0, floorY);
        float glint = smoothstep(1.2, 3.5, dot(seen, vec3(0.2126, 0.7152, 0.0722))) * flake;
        paint += mix(base, vec3(1.0), 0.45) * glint * 5.0;
        // The clear coat: a dielectric mirror over the paint, faint facing you and strong at the edges.
        float coat = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 clear = vfx_studio(i.worldPos, reflect(-v, n), 0.02, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.04)
                + vfx_direct(n, v, VFX_RIM_DIR, VFX_RIM_COLOR, vec3(1.0), 0.0, 0.04);
        vec3 color = paint * (1.0 - coat) + clear * coat;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
