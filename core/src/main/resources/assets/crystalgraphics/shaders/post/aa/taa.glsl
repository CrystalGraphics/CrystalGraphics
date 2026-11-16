// Temporal AA's blend, shared by its resolve (taa_resolve.shader) and the look weights' (taa_look.shader), so a look's
// edge settles at the share its colour does. After the post environment and velocity.glsl.

// The history's alpha: its age in frames (under 64) and its cut count (0 to 31) times 64, integers a half float holds
const float TAA_AGES = 64.0;

// Catmull-Rom's weight x texels out: the input filter, Unreal's r.TemporalAACatmullRom (sharper than its Gaussian)
float taa_catmull_rom(float x) {
    x = abs(x);
    return x < 1.0 ? 1.0 + x * x * (-2.5 + 1.5 * x) : x < 2.0 ? 2.0 + x * (-4.0 + x * (2.5 - 0.5 * x)) : 0.0;
}

// Texel at's motion a frame in uv, and its 3x3's farthest texel's: each the velocity where the jittered projection put
// the surface less the jitter (jitterNdc), so a still picture fetches its history in place. The nearest texel's, so an
// edge moves as its foreground does (the engine's depth: nearer is greater). Fragment stage: velocity.glsl's.
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
void taa_motion(sampler2D velocity, ivec2 at, ivec2 last, vec2 jitterNdc, out vec2 motion, out vec2 farMotion) {
    vec2 size = vec2(last + 1);
    ivec2 nearest = at, farthest = at;
    float nearestDepth = cg_EngineDepth(texelFetch(cg_DepthBuffer, at, 0).r), farthestDepth = nearestDepth;
    for (int k = 0; k < 9; k++) {   // LOOP-BOUND: the 3x3 round the texel
        ivec2 q = clamp(at + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last);
        float d = cg_EngineDepth(texelFetch(cg_DepthBuffer, q, 0).r);
        if (d > nearestDepth) {
            nearestDepth = d;
            nearest = q;
        }
        if (d < farthestDepth) {
            farthestDepth = d;
            farthest = q;
        }
    }
    motion = (cg_Velocity(velocity, (vec2(nearest) + 0.5) / size, texelFetch(cg_DepthBuffer, nearest, 0).r) - jitterNdc) * 0.5;
    farMotion = (cg_Velocity(velocity, (vec2(farthest) + 0.5) / size, texelFetch(cg_DepthBuffer, farthest, 0).r) - jitterNdc) * 0.5;
}
#endif

// Still: neither the near nor the far texel moves a twentieth of a texel (speeds in texels a frame)
float taa_still(float speed, float farSpeed) {
    return 1.0 - smoothstep(0.05, 0.25, max(speed, farSpeed));
}

// This frame's share: a still texel's run's (kept frames blended), a moving one's the least share rising with speed
// (resolve: x the least, y at speed); a quarter at least under a blended draw
float taa_share(vec4 resolve, float speed, float still, float kept, bool responsive) {
    float share = max(mix(resolve.x, resolve.y, clamp(speed / 8.0, 0.0, 1.0)) * (1.0 - still), 1.0 / (kept + 1.0));
    return responsive ? max(share, 0.25) : share;
}
