// CgVfxModule.Follow: steering toward where the grid holds more of a channel, Physarum's sensor rule (Jones 2010) in
// 3D: five sensors m.y blocks off, one ahead and four turned m.z radians from it, their frame rolled round the
// heading by a hash of the particle and the step (sensors fixed to world up lock agents into axis-aligned great
// circles: Jenson's 3D Physarum rolls them too); the particle turns toward the
// strongest at m.w a second, its speed kept exactly: a blend of two directions is shorter than either, so a particle
// turning by one slowed every turn and the agents clumped. Past m1.x (0: no ceiling) a sensor reads no more: in a full
// trail every sensor ties and the particle goes straight on, out of it. m: (channel, sensor distance, sensor angle,
// turn rate); m1: (full, 0, 0, 0).
#pragma once

float fx_grid_follow_sense(FxGrid grid, vec3 at, vec4 mask, float full) {
    float v = dot(fx_grid_value(grid, at), mask);
    return full > 0.0 ? min(v, full) : v;
}

void fx_grid_follow(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, vec4 m1, FxGrid grid) {
    float speed = length(p.velocity);
    if (!grid.live || speed < 1.0e-3) return;
    vec3 h = p.velocity / speed;
    vec3 u0 = normalize(cross(h, abs(h.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 w0 = cross(h, u0);
    uint k = p.id * 747796405u + uint(p.age * 240.0) * 2891336453u;
    k = (k ^ (k >> 16u)) * 0x7feb352du;
    k ^= k >> 15u;
    float roll = float(k & 0xffffu) * (6.2831853 / 65536.0);
    vec3 u = u0 * cos(roll) + w0 * sin(roll), w = cross(h, u);
    vec4 mask = fx_grid_mask(m.x);
    float c = cos(m.z), sn = sin(m.z);
    vec3 best = h;
    float most = fx_grid_follow_sense(grid, p.position + h * m.y, mask, m1.x);
    vec3 ways[4] = vec3[4](u, -u, w, -w);
    for (int i = 0; i < 4; i++) {
        vec3 d = h * c + ways[i] * sn;
        float v = fx_grid_follow_sense(grid, p.position + d * m.y, mask, m1.x);
        if (v > most) {
            most = v;
            best = d;
        }
    }
    vec3 turned = normalize(mix(h, best, clamp(m.w * s.dt, 0.0, 1.0))) * speed;
    f.accel += (turned - p.velocity) / max(s.dt, 1.0e-6);
}
