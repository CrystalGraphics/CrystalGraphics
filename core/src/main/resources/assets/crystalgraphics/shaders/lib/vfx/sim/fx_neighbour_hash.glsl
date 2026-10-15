// A neighbour grid's cell, hashed with the slot it belongs to into a table of `cells` (a power of two): Teschner et
// al. 2003's primes. The build (env/compute/vfx/neighbours.compute) and the readers (fx_neighbours_at.glsl) share it.
#pragma once

int fx_neighbour_hash(ivec3 c, uint slot, int cells) {
    uint h = (uint(c.x) * 73856093u) ^ (uint(c.y) * 19349663u) ^ (uint(c.z) * 83492791u) ^ (slot * 2654435761u);
    return int(h & uint(cells - 1));
}
