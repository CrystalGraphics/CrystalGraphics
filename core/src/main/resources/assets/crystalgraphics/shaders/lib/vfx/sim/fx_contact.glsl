// A module's shape and the rigid collision response, shared by the attracting, colliding and killing kinds; the Java
// side writes the shape (CgVfxShapeRows). A volume is e: its extents (a CgSphere's radius in x, a CgBox's half extents, a
// CgPlane's unit normal) and its form in w (FX_SPHERE, FX_BOX, FX_PLANE); every point is relative to its centre.
//
// Ported from Godot Engine (MIT, (c) 2014-present Godot Engine contributors): the attractor falloff and the sphere and
// box colliders of servers/rendering/renderer_rd/shaders/particles.glsl, the rigid response of
// scene/resources/particle_process_material.cpp. The plane, the container modes and the guard on a particle already
// leaving are this project's. Cascade's damping, count and completions (FxCollision) follow 4.21's
// ParticleModules_Collision.cpp, written here.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_rand.glsl"

#define FX_SPHERE 0.0
#define FX_BOX 1.0
#define FX_PLANE 2.0
// Godot's: a particle just touching counts.
#define FX_CONTACT_EPSILON 0.001

vec3 fx_safe_normalize(vec3 v) {
    float l = length(v);
    return l > 0.0 ? v / l : vec3(0.0);
}

// Godot's attractor falloff: 0 at the centre, 1 at the edge, past 1 outside. A plane reaches nowhere.
float fx_volume_reach(vec4 e, vec3 r) {
    if (e.w == FX_SPHERE) return length(r) / e.x;
    if (e.w == FX_BOX) return max(abs(r.x / e.x), max(abs(r.y / e.y), abs(r.z / e.z)));
    return 2.0;
}

// bevy_hanabi's kill test: whether r is inside, a plane's inside being behind its normal.
bool fx_volume_inside(vec4 e, vec3 r) {
    if (e.w == FX_SPHERE) return dot(r, r) < e.x * e.x;
    if (e.w == FX_BOX) return all(lessThan(abs(r), e.xyz));
    return dot(r, e.xyz) < 0.0;
}

// Whether r is over the volume: always, but past a plane's radius, centre.w.
bool fx_volume_over(vec4 e, vec4 centre, vec3 r) {
    if (e.w != FX_PLANE) return true;
    vec3 t = r - e.xyz * dot(r, e.xyz);
    return dot(t, t) <= centre.w * centre.w;
}

// Godot's collider: whether a particle of radius size at r touches the volume, or, a container, its inside wall. The
// normal out of the surface, and how deep.
bool fx_volume_contact(vec4 e, bool container, vec3 r, float size, out vec3 n, out float depth) {
    n = vec3(0.0);
    depth = 0.0;
    if (e.w == FX_SPHERE) {
        float l = length(r);
        float d = container ? e.x - size - l : l - (size + e.x);
        if (d > FX_CONTACT_EPSILON || l == 0.0) return false;
        n = r / l * (container ? -1.0 : 1.0);
        depth = -d;
        return true;
    }
    if (e.w == FX_BOX) {
        vec3 a = abs(r), sg = sign(r);
        if (container) {
            vec3 over = a + size - e.xyz;
            int axis = over.x >= over.y && over.x >= over.z ? 0 : over.y >= over.z ? 1 : 2;
            if (over[axis] < -FX_CONTACT_EPSILON) return false;
            n[axis] = -(sg[axis] == 0.0 ? 1.0 : sg[axis]);
            depth = over[axis];
            return true;
        }
        if (any(greaterThan(a, e.xyz))) {
            vec3 c = a - min(a, e.xyz);
            float l = length(c);
            float d = l - size;
            if (d > FX_CONTACT_EPSILON) return false;
            n = c / l * sg;
            depth = -d;
            return true;
        }
        vec3 inward = e.xyz - a;
        if (inward.x < inward.y && inward.x < inward.z) {
            n.x = sg.x;
            depth = inward.x + size;
        } else if (inward.y < inward.x && inward.y < inward.z) {
            n.y = sg.y;
            depth = inward.y + size;
        } else {
            n.z = sg.z;
            depth = inward.z + size;
        }
        return true;
    }
    float s = container ? -1.0 : 1.0;
    float d = (r.x * e.x + r.y * e.y + r.z * e.z) * s - size;
    if (d > FX_CONTACT_EPSILON) return false;
    n = e.xyz * s;
    depth = -d;
    return true;
}

// A collider's Cascade half, four vec4s of its words (CgVfxModule.CollisionResponse): damping's low end and whether it
// is set (1 a roll per axis, 2 one roll); its high end and the delay, a share of life; the rotation restitution and
// the completion (FX_COMPLETE_*, 0 for no count); the count's range and a world collider's lead.
struct FxCollision { vec4 low, high, turn, most, turnHigh; };

#define FX_COMPLETE_KILL 1.0
#define FX_COMPLETE_FREEZE 2.0
#define FX_COMPLETE_HALT 3.0
#define FX_COMPLETE_FREEZE_TRANSLATION 4.0
#define FX_COMPLETE_FREEZE_ROTATION 5.0
#define FX_COMPLETE_FREEZE_MOVEMENT 6.0
// FxParticle.state: collisions ignored after a completion; translation held (Cascade's STATE_Particle_* flags).
#define FX_STATE_HALT 2u
#define FX_STATE_STILL 4u
// What a collider's rolls are keyed by, with the particle's id.
#define FX_COLLISION_KEY 0x434F4C4Cu

// Whether a collider leaves p alone this step: resting, past a completion, or within its delay, which Cascade tests
// against the particle's relative time (:314-321).
bool fx_collision_skips(FxParticle p, FxCollision c) {
    return p.resting || (p.state & FX_STATE_HALT) != 0u || p.age < c.high.w * p.life;
}

// The contacts p takes before its completion: Cascade's MaxCollisions rolled and rounded (:155), at least 1, since the
// count is decremented before it is tested above 0 (:406-411).
float fx_collision_most(FxParticle p, FxCollision c) {
    return max(floor(mix(c.most.x, c.most.y, fx_rand(p.id, FX_COLLISION_KEY, 3u)) + 0.5), 1.0);
}

// Three rolls of p from channel `first`, each axis taking the one its code names (CgVfxLifeRoll.rolls): code - 1 is
// y's roll plus twice z's, x always its own; Cascade's per-channel range and its LockedAxes.
vec3 fx_collision_rolls(FxParticle p, float code, uint first) {
    vec3 r = vec3(fx_rand(p.id, FX_COLLISION_KEY, first), fx_rand(p.id, FX_COLLISION_KEY, first + 1u),
            fx_rand(p.id, FX_COLLISION_KEY, first + 2u));
    int c = int(code + 0.5) - 1;
    return vec3(r.x, r[c % 2], r[c / 2]);
}

// Whether a hit along n spends the count: every one, or with c.most.w at 0 or above only one whose normal's vertical
// plus that fudge reaches 1 (bOnlyVerticalNormalsDecrementCount, :397-408; Unreal's z is the host's y).
bool fx_collision_counts(vec3 n, FxCollision c) {
    return c.most.w < 0.0 || dot(n, n) < 1e-8 || abs(n.y) + c.most.w >= 1.0;
}

// Cascade's DampingFactor for p, rolled from its id (:153).
vec3 fx_collision_damping(FxParticle p, FxCollision c) {
    return mix(c.low.xyz, c.high.xyz, fx_collision_rolls(p, c.low.w, 0u));
}

// Cascade's DampingFactorRotation for p: its constant, or rolled from its id between its ends (:154).
vec3 fx_collision_rotation(FxParticle p, FxCollision c) {
    return c.turnHigh.w > 0.5 ? mix(c.turn.xyz, c.turnHigh.xyz, fx_collision_rolls(p, c.turnHigh.w, 4u)) : c.turn.xyz;
}

// Damps p's rotation rates by d from now: a sprite's by d.x, a mesh's per axis (:421-426), its turn so far kept.
void fx_collision_turn(inout FxParticle p, vec3 d) {
    p.spinRate *= d.x;
    p.turnBase += (p.turnScale - p.turnScale * d) * p.age;
    p.turnScale *= d;
}

// Cascade's completion (:497-534): killed; or held still, unturned or both; every one ignores collisions after.
void fx_collision_complete(inout FxParticle p, float how) {
    if (how < FX_COMPLETE_KILL + 0.5) {
        p.life = p.age;
        return;
    }
    p.state |= FX_STATE_HALT;
    bool still = abs(how - FX_COMPLETE_FREEZE) < 0.5 || abs(how - FX_COMPLETE_FREEZE_TRANSLATION) < 0.5
            || abs(how - FX_COMPLETE_FREEZE_MOVEMENT) < 0.5;
    bool unturned = abs(how - FX_COMPLETE_FREEZE) < 0.5 || abs(how - FX_COMPLETE_FREEZE_ROTATION) < 0.5
            || abs(how - FX_COMPLETE_FREEZE_MOVEMENT) < 0.5;
    if (still) {
        p.state |= FX_STATE_STILL;
        p.velocity = vec3(0.0);
    }
    if (unturned) fx_collision_turn(p, vec3(0.0));
}

// The response to a contact, pushed out by depth along n. With a count (c.turn.w), every contact is a hit counted
// toward it and the one that spends it completes instead of bouncing, as Cascade's does. A particle already leaving
// is left alone. With Cascade's damping (c.low.w) the whole velocity is mirrored and damped per axis, with no friction
// (:453); else Godot's rigid response: the speed into the surface taken away, g.y of the rest as friction, and a
// bounce of g.x once the impact is fast enough (slide_to_bounce_trigger), the punctual hit a collision event fires on,
// slower than g.z after it resting. what: the FX_HIT_ code; spends: whether a counted hit spends the count.
void fx_contact_respond(inout FxParticle p, vec3 n, float depth, vec3 g, FxCollision c, float what, bool spends) {
    p.position += n * depth;
    bool counted = c.turn.w > 0.0;
    if (counted) {
        if (spends) fx_hit(p, n, what);
        else p.hit = vec4(n, what);
        if (float(p.collisions) >= fx_collision_most(p, c)) {
            fx_collision_complete(p, c.turn.w);
            return;
        }
    }
    float response = n.x * p.velocity.x + n.y * p.velocity.y + n.z * p.velocity.z;
    if (response >= 0.0) return;
    if (c.low.w > 0.0) {
        p.velocity = fx_collision_damping(p, c) * reflect(p.velocity, n);
        if (!counted) fx_hit(p, n, what);
        fx_collision_turn(p, fx_collision_rotation(p, c));
        return;
    }
    float trigger = -response < 2.0 / max(1.0, min(g.x + 1.0, 2.0)) ? 0.0 : 1.0;
    float keep = 1.0 - max(0.0, min(g.y, 1.0));
    p.velocity = (p.velocity - n * response) * keep;
    p.velocity -= n * response * (g.x * trigger);
    if (trigger > 0.0) {
        if (!counted) fx_hit(p, n, what);
        fx_collision_turn(p, fx_collision_rotation(p, c));
    }
    if (length(p.velocity) < g.z) {
        p.velocity = vec3(0.0);
        p.spinRate = 0.0;
        p.resting = true;
    }
}

// fx_contact_respond, the hit spending the count as fx_collision_counts says.
void fx_contact_respond(inout FxParticle p, vec3 n, float depth, vec3 g, FxCollision c, float what) {
    fx_contact_respond(p, n, depth, g, c, what, fx_collision_counts(n, c));
}
