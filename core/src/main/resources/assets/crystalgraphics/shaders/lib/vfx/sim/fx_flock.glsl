// CgVfxModule.Flock: Reynolds 1987's boids over the pool's neighbour grid. m0: radius, separation, alignment, cohesion;
// m1: how near a neighbour is pushed away, and the most neighbours counted.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_neighbours_at.glsl"

void fx_flock(inout FxParticle p, inout FxForces f, FxStep s, vec4 m0, vec4 m1, FxNeighbours n) {
    float r2 = m0.x * m0.x, near2 = m1.x * m1.x;
    uint most = uint(m1.y), seen = 0u;
    vec3 centre = vec3(0.0), heading = vec3(0.0), apart = vec3(0.0);
    ivec3 c = fx_neighbour_cell(n, p.position);
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                uvec2 run = fx_neighbour_run(n, c + ivec3(x, y, z));
                for (uint k = run.x; k < run.y && seen < most; k++) {
                    FxNeighbour o = step_neighbour(k);
                    if (o.index == n.self || o.slot != n.slot) continue;
                    vec3 d = o.position - p.position;
                    float dd = dot(d, d);
                    if (dd >= r2 || dd < 1.0e-10) continue;
                    seen++;
                    centre += o.position;
                    heading += o.velocity;
                    // Pushed apart harder the nearer: each neighbour by the inverse of its distance.
                    if (dd < near2) apart -= d / dd;
                }
            }
        }
    }
    if (seen == 0u) return;
    float inv = 1.0 / float(seen);
    f.accel += apart * m0.y + (heading * inv - p.velocity) * m0.z + (centre * inv - p.position) * m0.w;
}
