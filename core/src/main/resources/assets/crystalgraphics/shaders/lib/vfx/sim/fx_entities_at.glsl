// The entities round the camera as a module kind after the solver reads them (CgVfxWorldInput.ENTITIES): a box each,
// feet to head and its width across, from CgEntityQuery once a frame. Every point is relative to the particle's
// instance origin, as FxParticle's positions are.
//
//   void fx_sting(inout FxParticle p, FxStep s, vec4 m, FxEntities entities) {
//       vec3 at, n;
//       uint entity;
//       if (!fx_entities_enter(entities, p.previous, p.position, 0.0, at, n, entity)) return;
//       p.hitEntity = entity;
//       fx_hit(p, n, FX_HIT_ENTITY);
//   }
//
// No level, or none near enough: count is 0 and every reader answers "nothing there".
#pragma once

// The instance's origin in the boxes' frame, and how many boxes there are.
struct FxEntities {
    vec3 origin;
    int count;
};

// Box i, its corners in the boxes' frame, and its entity: the Step kernel's, which holds the buffer.
void step_entity(int i, out vec3 lo, out vec3 hi, out uint id);

// Whether the step from previous to position entered a box, each grown by pad: the nearest entry, never a box it started
// inside, so a particle born in its caster flies out. Where it entered, the face's outward normal, and the entity.
bool fx_entities_enter(FxEntities e, vec3 previous, vec3 position, float pad, out vec3 at, out vec3 n, out uint entity) {
    at = position;
    n = vec3(0.0, 1.0, 0.0);
    entity = 0u;
    vec3 a = previous + e.origin, d = position - previous;
    vec3 inv = 1.0 / mix(d, vec3(1e-20), equal(d, vec3(0.0)));
    float best = 2.0;
    for (int i = 0; i < e.count; i++) {
        vec3 lo, hi;
        uint id;
        step_entity(i, lo, hi, id);
        lo -= pad;
        hi += pad;
        if (all(greaterThan(a, lo)) && all(lessThan(a, hi))) continue;
        vec3 t0 = (lo - a) * inv, t1 = (hi - a) * inv;
        vec3 near = min(t0, t1), far = max(t0, t1);
        float enter = max(max(near.x, near.y), near.z), leave = min(min(far.x, far.y), far.z);
        if (enter > leave || enter < 0.0 || enter > 1.0 || enter >= best) continue;
        best = enter;
        entity = id;
        int axis = near.x >= near.y && near.x >= near.z ? 0 : near.y >= near.z ? 1 : 2;
        n = vec3(0.0);
        n[axis] = d[axis] > 0.0 ? -1.0 : 1.0;
    }
    if (best > 1.0) return false;
    at = previous + d * best;
    return true;
}
