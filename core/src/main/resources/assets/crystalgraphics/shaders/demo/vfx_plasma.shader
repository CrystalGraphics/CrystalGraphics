// A swirling energy vortex: streaks of magenta and violet energy spiralling round a white-hot core inside a ball of
// light, white at their crests, layered through its depth, the whole vortex wobbling on its axis and throbbing, and
// rays turning from the core. CgVfxShowcase.
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
        Cull OFF
    }

    // The energy at {@code x} in the vortex's frame: spiral streaks round its axis, faster nearer it, churned by
    // noise, held in a shell about the core. Answers the streaks in x and their white-hot crests in y.
    vec2 plasma_streaks(vec3 x, float t) {
        float r = length(x.xz);
        float angle = atan(x.z, x.x);
        float churn = vfx_noise(x * 3.0 + vec3(0.0, t * 0.9, 0.0)) * 2.5;
        float spin = angle * 3.0 + r * 5.0 - t * (6.0 + 3.5 / (0.3 + r)) + x.y * 2.0 + churn;
        float bands = pow(0.5 + 0.5 * sin(spin), 10.0);
        float fine = pow(0.5 + 0.5 * sin(spin * 2.0 + x.y * 7.0 + churn * 1.7), 18.0);
        float shell = smoothstep(0.12, 0.35, length(x)) * (1.0 - smoothstep(0.75, 1.0, length(x)));
        return vec2(bands + fine * 0.6, pow(bands, 4.0)) * shell;
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        vec3 n = normalize(i.normalWs);
        if (!gl_FrontFacing) n = -n;
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        float nv = max(dot(n, v), 0.0);
        float throb = 1.0 + 0.18 * sin(t * 3.3) + 0.08 * sin(t * 7.9);
        // The vortex's axis wobbles: everything inside is turned into a frame that precesses slowly.
        float tilt = 0.28 * sin(t * 0.6), turn = t * 0.45;
        vec3 axis = normalize(vec3(sin(tilt) * cos(turn), cos(tilt), sin(tilt) * sin(turn)));
        vec3 ax = normalize(cross(axis, vec3(0.0, 0.0, 1.0)));
        vec3 az = cross(ax, axis);
        mat3 toVortex = transpose(mat3(ax, axis, az));
        // Straight through the ball, gathering the light of every streak on the way: from the glass to the far side,
        // or from inside, from the eye to the wall ahead.
        vec3 d = -v;
        vec3 q = (gl_FrontFacing ? i.worldPos - centre : VFX_CAMERA - centre) / radius;
        float across = gl_FrontFacing ? max(-2.0 * dot(q, d), 0.0) : length(i.worldPos - VFX_CAMERA) / radius;
        vec3 light = vec3(0.0);
        for (int k = 0; k < 28; k++) {
            vec3 x = toVortex * (q + d * ((float(k) + 0.5) / 28.0 * across));
            vec2 energy = plasma_streaks(x, t);
            float depth = length(x);
            vec3 tint = mix(vec3(1.5, 0.15, 1.15), vec3(0.45, 0.2, 1.7), smoothstep(0.3, 0.9, depth));
            light += tint * energy.x + vec3(1.4, 1.1, 1.5) * energy.y * 1.5;
            light += vec3(1.6, 1.2, 1.7) * exp(-depth * depth * 28.0) * (2.5 + 0.8 * sin(t * 7.0));
        }
        light *= across / 28.0 * 1.7 * throb;
        // Rays turning from the core, in the plane facing you.
        vec3 offset = q - d * dot(q, d);
        vec3 right = normalize(cross(d, vec3(0.0, 1.0, 0.0)));
        vec3 up = cross(right, d);
        float rayAngle = atan(dot(offset, up), dot(offset, right));
        float pass = length(offset);
        float rays = pow(0.5 + 0.5 * cos(rayAngle * 6.0 + t * 1.3), 12.0) + pow(0.5 + 0.5 * cos(rayAngle * 4.0 - t * 0.9), 16.0);
        light += vec3(1.3, 0.6, 1.5) * rays * exp(-pass * 3.0) * 0.9 * throb;
        // A deep violet haze behind it all, and a bright rim where the ball's light thickens.
        vec3 color = vec3(0.08, 0.0, 0.14) + light + vec3(0.9, 0.18, 1.3) * pow(1.0 - nv, 3.0) * 1.6 * throb;
        fragColor = vec4(vfx_aces(color), 1.0);
    }
}
