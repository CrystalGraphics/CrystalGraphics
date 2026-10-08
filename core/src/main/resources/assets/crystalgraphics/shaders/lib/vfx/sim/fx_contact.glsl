// CgVfxModule.Volume and the rigid collision response, shared by the attracting, colliding and killing kinds; the CPU
// side is CgVfxContacts, term for term. A volume is e: its extents (a sphere's radius in x, a box's half extents, a
// plane's unit normal) and its form in w (FX_SPHERE, FX_BOX, FX_PLANE); every point is relative to its centre.
//
// Ported from Godot Engine (MIT, (c) 2014-present Godot Engine contributors): the attractor falloff and the sphere and
// box colliders of servers/rendering/renderer_rd/shaders/particles.glsl, the rigid response of
// scene/resources/particle_process_material.cpp. The plane, the container modes and the guard on a particle already
// leaving are this project's.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

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

// Godot's rigid response to a contact: pushed out by depth, the speed into the surface taken away, friction of the
// rest, and a bounce once the impact is fast enough (slide_to_bounce_trigger), which is the punctual hit a collision
// event fires on. Slower than rest after it, the particle rests. r: bounce, friction, rest (blocks a second), and kill:
// Godot's hide on contact, a hit then death, on the surface so what the hit spawns starts there.
void fx_contact_respond(inout FxParticle p, vec3 n, float depth, vec4 r) {
    p.position += n * depth;
    if (r.w > 0.0) {
        fx_hit(p, n);
        p.life = p.age;
        return;
    }
    float response = n.x * p.velocity.x + n.y * p.velocity.y + n.z * p.velocity.z;
    if (response >= 0.0) return;
    float trigger = -response < 2.0 / max(1.0, min(r.x + 1.0, 2.0)) ? 0.0 : 1.0;
    float keep = 1.0 - max(0.0, min(r.y, 1.0));
    p.velocity = (p.velocity - n * response) * keep;
    p.velocity -= n * response * (r.x * trigger);
    if (trigger > 0.0) fx_hit(p, n);
    if (length(p.velocity) < r.z) {
        p.velocity = vec3(0.0);
        p.spinRate = 0.0;
        p.resting = true;
    }
}
