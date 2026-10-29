// The fields a group places, evaluated where a particle is: Chaos's field nodes (FieldSystemNodes.cpp's Evaluate), each
// graph flattened by CgFieldWorld into FIELDS in post-order, its root last, read here by a 16-register interpreter.
// CgFieldEval is its mirror in Java. A scalar fills all four lanes of its register, so a scalar and a vector broadcast.
//
//     fx_field_apply(fields, group, FX_FIELD_LINEAR_FORCE, scale, p, f, s);   // a physics_field module's whole work
#pragma once
#include "crystalgraphics:shaders/lib/vfx/fx_rand.glsl"

#define FX_FIELD_LINEAR_FORCE 0
#define FX_FIELD_LINEAR_VELOCITY 1
#define FX_FIELD_KILL 2
#define FX_FIELD_ANGULAR_VELOCITY 3
#define FX_FIELD_ORBIT 4
#define FX_FIELD_SPEED_SCALE 5
#define FX_FIELD_SCALE 6
#define FX_FIELD_GROUPS 32

// The flattened fields' n-th uvec4: defined by the data interface, after the buffer it reads.
uvec4 fx_field_word(int i);

// Where this instance's particles are from the fields' anchor block.
struct FxFields {
    vec3 origin;
};

float fx_field_f(uint w) {
    return uintBitsToFloat(w);
}

vec4 fx_field_v(uint at) {
    return uintBitsToFloat(fx_field_word(int(at)));
}

vec3 fx_field_turn(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

// Chaos's EvalFalloffFunction: none, linear, inverse, squared, logarithmic, as the declaration orders them.
float fx_field_falloff(float falloff, float f) {
    int k = int(falloff + 0.5);
    if (k == 0) return 1.0;
    if (k == 1) return f;
    if (k == 2) return 2.0 * (1.0 - 1.0 / (f + 1.0));
    if (k == 3) return f * f;
    return log2(f + 1.0);
}

// ScaleFunctionResult: magnitude × (min + (max − min) × g).
float fx_field_ranged(vec4 a, float falloff, float f) {
    return a.x * (a.y + (a.z - a.y) * fx_field_falloff(falloff, f));
}

// The graph of the instance whose header is at `head`, at `q` in its own space: its root's value.
vec4 fx_field_graph(uint head, vec3 q, uint seed, uint key) {
    uvec4 h = fx_field_word(int(head));
    float age = fx_field_v(head + 4u).w;
    vec4 r[16];
    uint nodes = min(h.y, 16u);
    uint at = head + 5u;
    for (uint n = 0u; n < nodes; n++, at += 5u) {
        uvec4 op = fx_field_word(int(at));
        vec4 a = fx_field_v(at + 1u), b = fx_field_v(at + 2u), c = fx_field_v(at + 3u), d = fx_field_v(at + 4u);
        vec4 x = vec4(0.0);
        if (op.x == 1u) {            // radial_falloff: magnitude, min, max, default; radius, position; falloff
            float radius = b.x, dist = length(q - b.yzw);
            if (radius > 0.0) x = vec4(dist < radius ? fx_field_ranged(a, c.x, 1.0 - dist / radius) : a.w);
        } else if (op.x == 2u) {     // plane_falloff: magnitude, min, max, default; distance, position; normal; falloff
            float dist = b.x;
            if (dist > 0.0) {
                float s = dot(c.xyz, q) - dot(c.xyz, b.yzw);
                x = vec4(s < -1e-8 && s > -dist ? fx_field_ranged(a, c.w, 1.0 + s / dist) : a.w);
            }
        } else if (op.x == 3u) {     // box_falloff: magnitude, min, max, default; location, rotation, scale; falloff
            vec4 turn = vec4(-b.w, -c.x, -c.y, c.z);
            vec3 local = fx_field_turn(turn, q - b.xyz) / vec3(c.w, d.xy);
            vec3 e = abs(local) - 0.5;
            if (max(e.x, max(e.y, e.z)) < 0.0) x = vec4(fx_field_ranged(a, d.z, -min(max(e.x, max(e.y, e.z)), 0.0) / 0.5));
            else x = vec4(a.w);
        } else if (op.x == 4u) {     // wave_scalar: magnitude, position; wavelength, period, function, falloff
            float t = max(age, 0.0), radius = b.x * t / b.y;
            if (int(b.z + 0.5) == 3) {
                float decay = t / b.y;
                x = vec4(a.x * exp(-decay * decay));
            } else {
                float dist = length(q - a.yzw), frac = 1.0 - dist / radius;
                float phase = -(b.x != 0.0 ? 6.28318530718 / b.x : 0.0) * radius * frac;
                int fn = int(b.z + 0.5);
                if (fn == 0) x = vec4(a.x * cos(phase));
                else if (fn == 1) x = vec4(a.x * exp(-phase * phase));
                else if (dist < radius && radius > 0.0) x = vec4(a.x * fx_field_falloff(b.w, frac));
            }
        } else if (op.x == 5u) {     // uniform_scalar
            x = vec4(a.x);
        } else if (op.x == 6u) {     // uniform_vector: magnitude, direction
            x = vec4(a.x * a.yzw, 0.0);
        } else if (op.x == 7u || op.x == 8u) {   // radial_vector, rotated_radial_vector: magnitude, position (angle, axis)
            vec3 v = q - a.yzw;
            float len = length(v);
            v = len > 1e-8 ? v / len : vec3(0.0);
            if (op.x == 8u) {
                float al = length(b.yzw);
                vec3 axis = al > 1e-8 ? b.yzw / al : vec3(0.0);
                vec4 turn = vec4(axis * sin(b.x * 0.5), cos(b.x * 0.5));
                v = fx_field_turn(turn, v);
            }
            x = vec4(a.x * v, 0.0);
        } else if (op.x == 9u) {     // random_vector: seeded by the particle, the node and the step
            vec3 v = vec3(fx_rand(seed, n, key * 3u), fx_rand(seed, n, key * 3u + 1u), fx_rand(seed, n, key * 3u + 2u)) * 2.0 - 1.0;
            float len = length(v);
            x = vec4(a.x * (len > 1e-8 ? v / len : vec3(0.0)), 0.0);
        } else if (op.x == 10u) {    // operator: magnitude, op; left, right (FSumScalar, FSumVector)
            bool hl = op.y != 0u, hr = op.z != 0u;
            vec4 l = hl ? r[op.y - 1u] : vec4(0.0), rr = hr ? r[op.z - 1u] : vec4(0.0);
            int o = int(a.y + 0.5);
            if (hl && hr) x = o == 0 ? rr * l : o == 1 ? l / rr : o == 2 ? rr + l : l - rr;
            else x = hl ? l : rr;
            x *= a.x;
        } else if (op.x == 11u) {    // culling: op; culling, field (FCullingField: inside where it is 0)
            float cull = op.y != 0u ? r[op.y - 1u].x : 0.0;
            bool keep = int(a.x + 0.5) == 0 ? cull == 0.0 : cull != 0.0;
            x = keep && op.z != 0u ? r[op.z - 1u] : vec4(0.0);
        }
        r[n] = x;
    }
    return nodes == 0u ? vec4(0.0) : r[nodes - 1u];
}

// Every instance of `group` whose bounds hold the particle, applied for `target`, times `scale`.
void fx_field_apply(FxFields F, int group, int target, float scale, inout FxParticle p, inout FxForces f, FxStep s) {
    if (group < 0 || group >= FX_FIELD_GROUPS) return;
    uvec4 g = fx_field_word(group);
    uint seed = floatBitsToUint(p.seed), key = floatBitsToUint(p.age);
    vec3 w = F.origin + p.position;
    uint head = g.x;
    for (uint i = 0u; i < g.y; i++) {
        uvec4 h = fx_field_word(int(head));
        if (int(h.x) == target) {
            vec4 place = fx_field_v(head + 1u), inverse = fx_field_v(head + 2u);
            vec4 lo = fx_field_v(head + 3u), hi = fx_field_v(head + 4u);
            vec3 q = fx_field_turn(inverse, w - place.xyz);
            if (all(greaterThanEqual(q, lo.xyz)) && all(lessThanEqual(q, hi.xyz))) {
                vec4 v = fx_field_graph(head, q, seed, key) * place.w * scale
                        * (1.0 + fx_rand(seed, 16u + i, key) * lo.w);
                vec4 forward = vec4(-inverse.xyz, inverse.w);
                vec3 out3 = fx_field_turn(forward, v.xyz);
                if (target == FX_FIELD_LINEAR_FORCE) f.accel += out3;
                else if (target == FX_FIELD_LINEAR_VELOCITY) p.position += out3 * s.dt;
                else if (target == FX_FIELD_KILL) { if (v.x > 0.0) p.life = p.age; }
                // Rates per 30 fps frame on what the particle keeps, never reset; in the field's own axes, unturned.
                else if (target == FX_FIELD_SPEED_SCALE) {
                    float k = v.x * s.dt * 30.0;
                    p.speedScale += k;
                    if (k < 0.0) p.speedScale = max(p.speedScale, 0.0);
                }
                else if (target == FX_FIELD_ANGULAR_VELOCITY) p.fieldTurn += v.xyz * (s.dt * 30.0);
                else if (target == FX_FIELD_SCALE) p.fieldScale += v.xyz * (s.dt * 30.0);
                else if (target == FX_FIELD_ORBIT) {
                    float rate = length(out3);
                    if (rate > 1e-8) {
                        vec3 axis = out3 / rate, from = w - place.xyz;
                        float angle = rate * s.dt;
                        vec4 turn = vec4(axis * sin(angle * 0.5), cos(angle * 0.5));
                        // CC2 moves it by this displacement times the speed scale too.
                        p.position += (fx_field_turn(turn, from) - from) * p.speedScale;
                    }
                }
            }
        }
        head += 5u + h.y * 5u;
    }
}
