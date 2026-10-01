// A force field under fire: an energy barrier of hexagonal cells, evenly laid over the sphere on a spherical Fibonacci
// lattice, edges lit and a scan band sweeping up it. Each plasma bolt that strikes it bursts in an orange splash and a
// white flash, and a shockwave rolls out lighting the cells it passes one by one. CG_OBJECT_CUSTOM0 to 2: the three
// impacts, a direction from the centre in world space and the seconds since it struck (negative: not yet).
// CgVfxShowcase.
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

    // Lattice point {@code id} of the n-point spherical Fibonacci lattice.
    vec3 shield_point(float id, float n) {
        float phi = 6.2831853 * fract(id * 1.6180339887);
        float cosTheta = 1.0 - (2.0 * id + 1.0) / n;
        float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
        return vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
    }

    // The hexagonal cell unit vector {@code p} lies in: x its id, y how far p is from the cell's border; its centre in
    // {@code centre}. Keinert et al.'s inverse spherical Fibonacci mapping (2015) finds the lattice cell, as in Inigo
    // Quilez's GLSL port; the 4x4 candidates round it give the nearest point and its neighbours' bisectors.
    vec2 shield_cell(vec3 p, float n, out vec3 centre) {
        const float tau = 6.2831853, phi = 1.6180339887;
        float k = max(2.0, floor(log2(n * tau * 0.5 * sqrt(5.0) * (1.0 - p.z * p.z)) / log2(phi + 1.0)));
        float fk = pow(phi, k) / sqrt(5.0);
        vec2 f = vec2(round(fk), round(fk * phi));
        vec2 ka = 2.0 * f / n;
        vec2 kb = tau * (fract((f + 1.0) * phi) - (phi - 1.0));
        mat2 inverseBasis = mat2(ka.y, -ka.x, kb.y, -kb.x) / (ka.y * kb.x - ka.x * kb.y);
        vec2 c = floor(inverseBasis * vec2(atan(p.y, p.x), p.z - 1.0 + 1.0 / n));
        float best = 8.0, bestId = 0.0;
        vec3 near = vec3(0.0, 0.0, 1.0);
        for (int s = 0; s < 16; s++) {
            float id = clamp(dot(f, vec2(float(s % 4) - 1.0, float(s / 4) - 1.0) + c), 0.0, n - 1.0);
            vec3 q = shield_point(id, n);
            float d = dot(q - p, q - p);
            if (d < best) {
                best = d;
                bestId = id;
                near = q;
            }
        }
        float border = 8.0;
        for (int s = 0; s < 16; s++) {
            float id = clamp(dot(f, vec2(float(s % 4) - 1.0, float(s / 4) - 1.0) + c), 0.0, n - 1.0);
            if (id == bestId) continue;
            vec3 q = shield_point(id, n);
            border = min(border, dot(0.5 * (near + q) - p, normalize(q - near)));
        }
        centre = near;
        return vec2(bestId, border);
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
        vec3 centreWs = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 p = normalize(i.objPos);
        vec3 pw = normalize(i.worldPos - centreWs);
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = abs(dot(n, v));
        vec3 cellCentre;
        vec2 cell = shield_cell(p, 380.0, cellCentre);
        vec3 cellWs = normalize(mat3(CG_OBJECT_TO_WORLD) * cellCentre);
        float h = vfx_hash31(vec3(cell.x, 3.0, 7.0));
        float aa = fwidth(cell.y);
        float edge = 1.0 - smoothstep(0.004, 0.004 + aa * 1.5, cell.y);
        float bevel = 1.0 - smoothstep(0.0, 0.06, cell.y);
        // The impacts: a splash and a flash where each bolt struck, and a shockwave lighting whole cells as it rolls
        // out, a thinner bright ring riding it.
        float cellsLit = 0.0, ring = 0.0, flash = 0.0, splash = 0.0, residue = 0.0;
        vec4 hits[3] = vec4[3](CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM1, CG_OBJECT_CUSTOM2);
        for (int k = 0; k < 3; k++) {
            float age = hits[k].w;
            if (age < 0.0) continue;
            vec3 at = normalize(hits[k].xyz);
            float d = acos(clamp(dot(pw, at), -1.0, 1.0));
            float dc = acos(clamp(dot(cellWs, at), -1.0, 1.0));
            float front = age * 1.5;
            cellsLit += exp(-pow((dc - front) * 6.0, 2.0)) * exp(-age * 1.1);
            ring += exp(-pow((d - front) * 16.0, 2.0)) * exp(-age * 1.4);
            flash += exp(-d * d * 60.0) * exp(-age * 7.0);
            splash += exp(-d * d * 25.0) * exp(-age * 10.0);
            residue += exp(-dc * 3.5) * exp(-age * 1.8) * step(0.55, fract(h * 7.0 + floor(t * 12.0) * 0.37));
        }
        // A scan band sweeping up, a slow tide of energy across it, and cells flaring now and then on their own.
        float scanY = fract(t * 0.22) * 2.8 - 1.4;
        float scan = exp(-pow((pw.y - scanY) * 5.0, 2.0));
        float tide = vfx_fbm(p * 3.5 + vec3(0.0, t * 0.35, t * 0.2), 3);
        float flare = pow(0.5 + 0.5 * sin(t * 1.6 + h * 60.0), 40.0);
        float rim = pow(1.0 - nv, 3.0);
        vec3 blue = vec3(0.2, 0.55, 1.6);
        vec3 cyan = vec3(0.45, 0.95, 1.8);
        float lines = 0.12 + rim * 1.2 + scan * 0.9 + tide * 0.25 + cellsLit * 2.2 + residue * 0.8;
        float fill = 0.015 + rim * 0.5 + scan * 0.06 + flare * 0.18 + cellsLit * 0.55 + residue * 0.25;
        vec3 color = cyan * edge * lines + blue * (fill + bevel * (0.05 + cellsLit * 0.6 + scan * 0.1));
        color += vec3(1.3, 1.5, 1.9) * (ring * 1.4 + flash * 3.0) + vec3(2.2, 0.7, 0.12) * splash * 2.5;
        if (!gl_FrontFacing) color *= 0.45;
        fragColor = vec4(color, 1.0);
    }
}
