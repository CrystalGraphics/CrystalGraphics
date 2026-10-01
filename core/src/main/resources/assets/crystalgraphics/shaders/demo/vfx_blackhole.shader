// A black hole: each pixel traces a ray bending around the singularity, through a hot, spinning accretion disk --
// brighter on the side coming towards you -- with the studio behind it lensed round the shadow. The sphere is only
// the volume the ray is traced in. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        mat3 model = mat3(CG_OBJECT_TO_WORLD);
        mat3 toObject = inverse(model);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        // The ray in the sphere's own space, where the disk is the plane y = 0 and the radius is 1.
        vec3 pos = toObject * (i.worldPos - centre);
        vec3 dir = normalize(toObject * normalize(i.worldPos - VFX_CAMERA));
        const float HORIZON = 0.22;
        vec3 light = vec3(0.0);
        float transmit = 1.0;
        bool swallowed = false;
        for (int k = 0; k < 72; k++) {
            float r = length(pos);
            if (r < HORIZON) { swallowed = true; break; }
            if (r > 1.02 && dot(pos, dir) > 0.0) break;
            float dt = clamp(0.4 * (r - HORIZON), 0.012, 0.05);
            // Bend towards the centre, harder the closer it passes.
            dir = normalize(dir - pos / (r * r * r) * 0.012 * dt * 40.0);
            vec3 next = pos + dir * dt;
            // Crossing the disk plane: add what the disk emits there.
            if (pos.y * next.y < 0.0) {
                vec3 hit = mix(pos, next, pos.y / (pos.y - next.y));
                float rr = length(hit.xz);
                if (rr > 0.3 && rr < 0.95) {
                    float angle = atan(hit.z, hit.x);
                    float swirl = vfx_fbm(vec3(rr * 9.0, angle * 2.0 - t * 2.2 / rr, 0.0), 4);
                    float rings = 0.6 + 0.4 * sin(rr * 70.0 - t * 3.0);
                    float heat = pow(1.0 - (rr - 0.3) / 0.65, 1.6);
                    // Doppler beaming: the side of the disk moving towards the viewer is brighter and bluer.
                    vec3 velocity = normalize(vec3(-hit.z, 0.0, hit.x));
                    float doppler = 1.0 + 1.6 * dot(velocity, -dir);
                    vec3 hot = mix(vec3(1.6, 0.5, 0.1), vec3(1.5, 1.4, 1.9), heat);
                    float density = smoothstep(0.3, 0.38, rr) * (1.0 - smoothstep(0.85, 0.95, rr));
                    light += transmit * hot * (heat * 4.0 + 0.4) * swirl * rings * max(doppler, 0.1) * density * 2.2;
                    transmit *= 1.0 - 0.75 * density;
                }
            }
            pos = next;
        }
        // Rays that skim the horizon wrap round it and come back out: the bright ring at the shadow's edge.
        if (!swallowed) light += transmit * vfx_env(normalize(model * dir), 0.0);
        fragColor = vec4(vfx_aces(light), 1.0);
    }
}
