// Events (CgVfxEvent): a child's key, the same bits as CgVfxEvent.childKey.
#pragma once

// Chris Wellons's lowbias32 (hash-prospector, public domain).
uint fx_event_mix(uint x) {
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return x;
}

// Child i of event `event` of parent particle `parent`: the child's spawn index in its slot, and its id.
uint fx_child_key(uint parent, uint event, uint i) {
    return fx_event_mix(parent ^ fx_event_mix(event << 10u | i));
}

// fx_child_key for an event that repeats (a collision, a rate, a location), at its firing-th firing: each firing's
// children differ. CgVfxEvent.childKey(int, int, int, int).
uint fx_child_key_at(uint parent, uint event, uint i, uint firing) {
    return fx_event_mix(fx_child_key(parent, event, i) + firing * 0x9E3779B9u);
}

// The key of every level past the first: child i of event `event` of child particle `parent`, at its firing-th firing
// (0 for an event that fires once), every bit of the parent's id hashed in. CgVfxEvent.grandchildKey.
uint fx_grandchild_key(uint parent, uint event, uint i, uint firing) {
    return fx_event_mix(fx_event_mix(parent ^ fx_event_mix(firing)) + (event << 10u | i));
}
