// A galaxy in a marble: a spiral galaxy ray-marched inside a glass sphere -- a hot core, blue arms, dark dust lanes,
// stars -- turning slowly, with the glass reflecting the studio over it. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

struct v2f { vec3 worldPos; vec3 normalWs; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // The galaxy's emission and dust at {@code p}, in the marble's own space.
    vec4 galaxy_at(vec3 p, float t) {
        float r = length(p.xz);
        float angle = atan(p.z, p.x);
        // Two arms, wound tighter towards the centre, turning with time.
        float arms = 0.5 + 0.5 * cos(2.0 * (angle - log(r + 0.05) * 2.6 - t * 0.18));
        float disk = exp(-abs(p.y) * (14.0 + 10.0 * r)) * smoothstep(0.92, 0.55, r);
        float clouds = vfx_fbm(vec3(p.x, p.y * 2.0, p.z) * 5.0 + vec3(0.0, 0.0, t * 0.05), 4);
        float density = disk * (0.25 + arms * 1.2) * (0.4 + clouds);
        float dust = disk * smoothstep(0.55, 0.75, vfx_ridged(p * 7.0, 3)) * arms;
        float core = exp(-dot(p, p) * 55.0) * 6.0 + exp(-dot(p, p) * 9.0) * 0.8;
        vec3 armColor = mix(vec3(0.35, 0.55, 1.4), vec3(1.2, 0.45, 0.9), clouds);
        vec3 emission = armColor * density * 1.5 + vec3(1.6, 1.25, 0.8) * core;
        return vec4(emission, dust * 8.0);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        mat3 model = mat3(CG_OBJECT_TO_WORLD);
        mat3 toObject = inverse(model);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 entry = toObject * (i.worldPos - centre);
        vec3 dir = normalize(toObject * normalize(i.worldPos - VFX_CAMERA));
        float span = max(-2.0 * dot(entry, dir), 0.0);
        const int STEPS = 48;
        float dt = span / float(STEPS);
        vec3 light = vec3(0.0);
        float transmit = 1.0;
        for (int k = 0; k < STEPS; k++) {
            vec3 p = entry + dir * (dt * (float(k) + 0.5));
            vec4 g = galaxy_at(p, t);
            light += transmit * g.rgb * dt * 3.0;
            transmit *= exp(-g.a * dt);
        }
        // Stars scattered through the volume, twinkling.
        vec3 starCell = floor((entry + dir * span * 0.5) * 60.0);
        float star = step(0.985, vfx_hash31(starCell)) * (0.6 + 0.4 * sin(t * 4.0 + vfx_hash31(starCell + 3.0) * 30.0));
        light += vec3(1.0, 0.95, 0.9) * star * 0.8;
        // The glass: reflections over the galaxy, strongest at the rim.
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        vec3 glass = vfx_env(reflect(-v, n), 0.0) + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.04);
        vec3 color = light * (1.0 - fresnel) + glass * fresnel + vec3(0.01, 0.012, 0.03);
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
