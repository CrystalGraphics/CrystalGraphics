// Events (CgVfxEvent): a child's key, the same bits as CgVfxEvent.childKey.
#pragma once

// Child i of event `event` of parent particle `parent`: the child's spawn index in its slot, and its id.
uint fx_child_key(uint parent, uint event, uint i) {
    return parent << 8u | event << 5u | i;
}
