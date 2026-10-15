// CgVfxModule.Follow: steering toward where the grid holds more of a channel, Physarum's sensor rule (Jones 2010) in
// 3D: five sensors m.y blocks off, one ahead and four turned m.z radians from it; the particle turns toward the
// strongest at m.w a second, keeping its speed. m: (channel, sensor distance, sensor angle, turn rate).
#pragma once

void fx_grid_follow(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxGrid grid) {
    float speed = length(p.velocity);
    if (!grid.live || speed < 1.0e-3) return;
    vec3 h = p.velocity / speed;
    vec3 u = normalize(cross(h, abs(h.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 w = cross(h, u);
    vec4 mask = fx_grid_mask(m.x);
    float c = cos(m.z), sn = sin(m.z);
    vec3 best = h;
    float most = dot(fx_grid_value(grid, p.position + h * m.y), mask);
    vec3 ways[4] = vec3[4](u, -u, w, -w);
    for (int i = 0; i < 4; i++) {
        vec3 d = h * c + ways[i] * sn;
        float v = dot(fx_grid_value(grid, p.position + d * m.y), mask);
        if (v > most) {
            most = v;
            best = d;
        }
    }
    f.accel += (best * speed - p.velocity) * m.w;
}
