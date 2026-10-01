// A molten world: black basalt crust, cracked into a fine mosaic, split by branching fissures glowing orange; low
// basins flooded with bright lava under a cooling skin of dark plates; and thin seams of heat in the crust near molten
// rock. The fine detail lives in the per-pixel normal rather than the mesh, so it stays sharp. CgVfxShowcase.
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

    // The land's height at {@code dir}: hills and basins in [0, 1]; below 0.42 is molten.
    float lava_height(vec3 dir) {
        return vfx_fbm(dir * 2.3 + vec3(3.0), 5);
    }

    // The crust's fine relief at {@code dir}: plates domed like pillows, deep grooves between them, rock grain on top.
    float lava_relief(vec3 dir) {
        vec3 cells = vfx_voronoi(dir * 11.0);
        float dome = smoothstep(0.0, 0.16, cells.y - cells.x);
        return dome * 0.75 + vfx_fbm(dir * 24.0, 4) * 0.25;
    }

    // The surface at {@code dir}: the crust raised over the lava seas into ridged hills. What the mesh can carry; the
    // relief is per pixel.
    vec3 lava_surface(vec3 dir) {
        float h = lava_height(dir);
        float hills = vfx_ridged(dir * 4.0, 2) * smoothstep(0.42, 0.5, h);
        return dir * (0.95 + 0.22 * max(h - 0.42, 0.0) + 0.06 * hills);
    }

    void vertex(out v2f o) {
        vec3 dir = normalize(cg_Position);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(lava_surface(dir), 1.0);
        o.worldPos = world.xyz;
        o.objPos = dir;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 dir = normalize(i.objPos);
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float h = lava_height(dir);
        float flooded = 1.0 - smoothstep(0.4, 0.42, h);
        // The normal per pixel, from the surface and the crust's relief on it.
        vec3 side = normalize(abs(dir.y) < 0.99 ? cross(dir, vec3(0.0, 1.0, 0.0)) : cross(dir, vec3(1.0, 0.0, 0.0)));
        vec3 up = cross(side, dir);
        float e = 0.0025;
        float relief = lava_relief(dir);
        float reliefSide = lava_relief(normalize(dir + side * e));
        float reliefUp = lava_relief(normalize(dir + up * e));
        float bump = 0.035 * (1.0 - flooded);
        vec3 p0 = lava_surface(dir) * (1.0 + relief * bump);
        vec3 p1 = lava_surface(normalize(dir + side * e)) * (1.0 + reliefSide * bump);
        vec3 p2 = lava_surface(normalize(dir + up * e)) * (1.0 + reliefUp * bump);
        vec3 normalObj = normalize(cross(p1 - p0, p2 - p0));
        if (dot(normalObj, dir) < 0.0) normalObj = -normalObj;
        vec3 n = normalize(CG_NORMAL_MATRIX * normalObj);
        float pulse = 0.88 + 0.12 * sin(t * 2.0 + h * 12.0);
        // The molten rock: bright, flowing slowly over itself.
        float flow = vfx_fbm(dir * 4.0 + vfx_fbm(dir * 2.0 + vec3(t * 0.05), 3) * 1.5 + vec3(t * 0.12, -t * 0.08, 0.0), 5);
        vec3 molten = mix(vec3(2.0, 0.28, 0.02), vec3(4.5, 2.2, 0.45), smoothstep(0.35, 0.75, flow)) * pulse;
        // The crust: black basalt, grey ash on its high points, lit by the studio and shadowed down in its grooves.
        vec3 cells = vfx_voronoi(dir * 11.0);
        float crack = cells.y - cells.x;
        float ash = smoothstep(0.6, 0.9, relief);
        float occlusion = 0.25 + 0.75 * smoothstep(0.05, 0.6, relief);
        vec3 crust = vfx_pbr_studio(i.worldPos, floorY, n, v, mix(vec3(0.02, 0.016, 0.014), vec3(0.1, 0.095, 0.09), ash), 0.0, 0.85)
                * occlusion;
        // Fissures: thin branching lines of molten rock with a narrow glow either side.
        vec3 warp = vec3(vfx_fbm(dir * 2.0 + vec3(7.0), 3), vfx_fbm(dir * 2.0 + vec3(1.0, 9.0, 2.0), 3), 0.0);
        float ridge = vfx_ridged(dir * 3.2 + warp * 0.8, 5);
        float fissure = smoothstep(0.86, 0.9, ridge);
        float halo = smoothstep(0.74, 0.88, ridge);
        // Near molten rock the crust's cracks glow too: thin seams of heat.
        float near = max(halo, 1.0 - smoothstep(0.42, 0.48, h));
        float seam = (1.0 - smoothstep(0.0, 0.025, crack)) * near;
        crust += vec3(2.2, 0.35, 0.03) * seam * pulse + vec3(1.0, 0.12, 0.01) * halo * 0.35 * pulse;
        // The seas: lava under a cooling skin of dark plates, bright in the cracks between them, drifting.
        vec3 skin = vfx_voronoi(dir * 9.0 + vec3(t * 0.06, 0.0, -t * 0.04));
        float skinCrack = skin.y - skin.x;
        float plate = smoothstep(0.03, 0.08, skinCrack) * step(0.35, skin.z);
        vec3 plateColor = vec3(0.06, 0.02, 0.01) + vec3(1.2, 0.15, 0.01) * (1.0 - smoothstep(0.03, 0.14, skinCrack));
        vec3 sea = mix(molten, plateColor, plate);
        vec3 color = mix(crust, molten * 1.3, fissure * (1.0 - flooded));
        color = mix(color, sea, flooded);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
