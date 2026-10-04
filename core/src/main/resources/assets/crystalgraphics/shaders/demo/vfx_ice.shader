// An ice orb: a cloudy white core of frozen-in air streaming out from the centre, clear blue ice around it, flat
// straight-edged fractures that flash silver seen edge-on, air bubbles caught at different depths, and patches of
// feathery frost on its skin that sparkle. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _Voronoi ("Voronoi", sampler3D) = "cg_voronoi"
}

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    // The fractures a ray crosses, in the ball's own frame ({@code q} on the unit sphere, {@code across} its chord):
    // flat polygons inside the ball, silvered, brightest seen edge-on as a reflection is.
    float ice_cracks(vec3 q, vec3 d, float across) {
        float light = 0.0;
        for (int k = 0; k < 7; k++) {
            float seed = float(k);
            vec3 normal = normalize(fx_hash33(vec3(seed, 3.0, 9.0)) - 0.5);
            vec3 centre = (fx_hash33(vec3(seed, 8.0, 4.0)) - 0.5) * 1.0;
            float facing = dot(d, normal);
            if (abs(facing) < 1.0e-3) continue;
            float s = dot(centre - q, normal) / facing;
            if (s < 0.0 || s > across) continue;
            vec3 x = q + d * s;
            float size = 0.3 + 0.35 * fx_hash31(vec3(seed, 2.0, 6.0));
            // A straight-edged outline: the disc cut by four lines at random angles round it.
            vec3 u = normalize(cross(normal, vec3(0.0, 1.0, 0.3)));
            vec3 w = cross(normal, u);
            vec2 at = vec2(dot(x - centre, u), dot(x - centre, w));
            float inside = 1.0 - smoothstep(size - 0.01, size, length(at));
            for (int c = 0; c < 4; c++) {
                float a = (float(c) + fx_hash31(vec3(seed, float(c), 12.0))) * 1.5708;
                float cut = size * (0.45 + 0.4 * fx_hash31(vec3(seed, float(c), 13.0)));
                inside *= 1.0 - smoothstep(cut - 0.01, cut, dot(at, vec2(cos(a), sin(a))));
            }
            // Silvered, flashing seen edge-on, with hackle marks across it.
            float hackles = 0.6 + 0.4 * fx_value_ridged(vec3(at * 12.0, seed), 2);
            float sheen = (0.1 + 1.3 * pow(1.0 - abs(facing), 3.0)) * hackles;
            light += inside * sheen;
        }
        return light;
    }

    // Air bubbles caught at a few depths along the ray: small bright rings, each found by its distance from the ray.
    float ice_bubbles(vec3 q, vec3 d, float across) {
        float light = 0.0;
        for (int k = 0; k < 5; k++) {
            vec3 x = (q + d * ((float(k) + 0.5) / 5.0 * across)) * 6.0;
            vec3 cell = floor(x);
            vec3 h = fx_hash33(cell + float(k) * 17.0);
            if (h.z < 0.7) continue;
            vec3 bubble = cell + 0.2 + 0.6 * h;
            if (length(bubble) > 5.4) continue;
            vec3 to = bubble - x;
            float along = dot(to, d);
            float dist = length(to - d * along);
            float size = 0.04 + 0.06 * fx_hash31(cell + 3.0);
            light += (smoothstep(size, size * 0.75, dist) - 0.75 * smoothstep(size * 0.7, size * 0.35, dist))
                    * step(abs(along), 0.8);
        }
        return light;
    }

    // The cloudy core along a ray: air frozen in as streaks running out from the centre, dense there and gone by the
    // outer third. Answers the light scattered toward the eye in rgb and the share of what is behind still seen in a.
    vec4 ice_core(vec3 q, vec3 d, float across) {
        float seen = 1.0;
        float light = 0.0;
        float dt = across / 10.0;
        for (int k = 0; k < 10; k++) {
            vec3 x = q + d * ((float(k) + 0.5) * dt);
            float r = length(x);
            vec3 radial = x / max(r, 1.0e-3);
            float streaks = fx_value_noise(radial * 14.0) * 0.6 + fx_value_noise(radial * 31.0) * 0.4;
            float density = (1.0 - smoothstep(0.08, 0.48, r)) * smoothstep(0.35, 0.75, streaks) * 4.0;
            float absorbed = 1.0 - exp(-density * dt);
            light += seen * absorbed;
            seen *= 1.0 - absorbed;
        }
        return vec4(vec3(0.82, 0.92, 1.0) * light, seen);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        float floorY = centre.y - CG_OBJECT_CUSTOM3.x;
        vec3 camera = FX_CAMERA;
        vec3 wall = normalize(i.normalWs);
        vec3 n = gl_FrontFacing ? wall : -wall;
        vec3 v = normalize(camera - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        // Through the ice: refracted in, across, refracted out to the studio behind, tinted blue the thicker it is.
        // From inside the eye is in the ice, and the ray leaves it at the wall ahead.
        float ior = 1.31;
        vec3 inside, exitPoint, exitNormal, start;
        float across;
        if (gl_FrontFacing) {
            inside = refract(-v, n, 1.0 / ior);
            across = -2.0 * dot(i.worldPos - centre, inside);
            exitPoint = i.worldPos + inside * across;
            exitNormal = normalize(exitPoint - centre);
            start = i.worldPos;
        } else {
            inside = -v;
            across = length(i.worldPos - camera);
            exitPoint = i.worldPos;
            exitNormal = wall;
            start = camera;
        }
        vec3 leaving = refract(inside, -exitNormal, ior);
        if (dot(leaving, leaving) < 1.0e-4) leaving = reflect(inside, -exitNormal);
        float depth = across / radius;
        vec3 behind = vfx_studio(exitPoint, leaving, 0.2, floorY) * exp(-depth * vec3(1.5, 0.55, 0.2));
        // What is frozen inside, in the ball's frame so it turns with it.
        mat3 toObject = transpose(mat3(CG_OBJECT_TO_WORLD)) / (radius * radius);
        vec3 q = toObject * (start - centre);
        vec3 d = normalize(toObject * inside);
        vec4 core = ice_core(q, d, depth);
        vec3 body = behind * core.a + core.rgb * vfx_studio(i.worldPos, n, 1.0, floorY) * 0.9;
        body += vec3(0.75, 0.92, 1.0) * ice_cracks(q, d, depth) * 0.8 + vec3(0.9, 0.97, 1.0) * ice_bubbles(q, d, depth) * 0.5;
        body += vec3(0.15, 0.6, 1.2) * (0.12 + pow(1.0 - nv, 2.0) * 0.9);
        float fresnel = (0.02 + 0.98 * pow(1.0 - nv, 5.0)) * (gl_FrontFacing ? 1.0 : 0.25);
        vec3 reflection = vfx_studio(i.worldPos, reflect(-v, n), 0.15, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.12);
        vec3 clearIce = mix(body, reflection, fresnel);
        // Frost: a light dusting everywhere, a feathery crust in patches and toward the rim, its crystals glinting.
        float feathers = fx_value_ridged(i.objPos * 9.0, 4);
        float crust = smoothstep(0.42, 0.7, fx_value_fbm(i.objPos * 2.6 + vec3(4.0), 5));
        crust *= smoothstep(0.62, 0.7, fx_value_fbm(i.objPos * 1.4 + vec3(9.0), 3) + 0.25);
        float frost = clamp(0.06 + crust * 0.8 * (0.5 + 0.5 * feathers) + pow(1.0 - nv, 3.0) * 0.3, 0.0, 1.0);
        vec3 frostColor = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(0.86, 0.93, 1.0) * (0.85 + 0.15 * feathers), 0.0, 0.8);
        vec3 cells = fx_voronoi(i.objPos * 110.0);
        vec3 crystal = normalize(n + (fx_hash33(vec3(cells.z * 131.0, 5.0, 2.0)) - 0.5) * 0.7);
        vec3 seen = vfx_studio(i.worldPos, reflect(-v, crystal), 0.0, floorY);
        float glint = smoothstep(1.1, 3.2, dot(seen, vec3(0.2126, 0.7152, 0.0722))) * smoothstep(0.3, 0.1, cells.x) * frost;
        vec3 color = mix(clearIce, frostColor, frost * 0.85) + vec3(0.9, 0.97, 1.0) * glint * 4.0;
        fragColor = vec4(fx_aces(0.8 * color), 1.0);
    }
}
