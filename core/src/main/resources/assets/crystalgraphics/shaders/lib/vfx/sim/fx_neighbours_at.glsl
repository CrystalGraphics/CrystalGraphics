// The particles near this one as a module kind reads them (CgVfxWorldInput.NEIGHBOURS): last step's, sorted into a
// hashed grid whose cells are as wide as the widest neighbourRadius() of the pool's kinds. The one input a kind before
// the solver may take: a force from its neighbours.
//
//   void fx_swarm(inout FxParticle p, inout FxForces f, FxStep s, vec4 m, FxNeighbours n) {
//       ivec3 c = fx_neighbour_cell(n, p.position);
//       for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
//           uvec2 run = fx_neighbour_run(n, c + ivec3(x, y, z));
//           for (uint k = run.x; k < run.y; k++) {
//               FxNeighbour o = step_neighbour(k);
//               if (o.index == n.self || o.slot != n.slot) continue;   // itself, and another slot's sharing the hash
//               f.accel += ...;
//           }
//       }
//   }
//
// A hashed cell may hold two real cells' particles, so a reader tests the distance itself. A newborn is not in the grid
// (self is -1); the pool's first step sees no neighbours.
#pragma once

#include "crystalgraphics:shaders/lib/vfx/sim/fx_neighbour_hash.glsl"

// The grid's cell width and table size (0: no grid yet), the particle's slot, and its index in last step's particles.
struct FxNeighbours {
    float cell;
    int cells;
    uint slot;
    int self;
};

// A particle of last step: where it was, how fast, its slot and its index.
struct FxNeighbour {
    vec3 position;
    vec3 velocity;
    uint slot;
    int index;
};

// The Step kernel's, which holds the grid: hashed cell h's run of sorted particles, first and end, and the k-th of them.
uvec2 step_cell_run(int h);
FxNeighbour step_neighbour(uint k);

ivec3 fx_neighbour_cell(FxNeighbours n, vec3 position) {
    return ivec3(floor(position / n.cell));
}

uvec2 fx_neighbour_run(FxNeighbours n, ivec3 cell) {
    return n.cells == 0 ? uvec2(0u) : step_cell_run(fx_neighbour_hash(cell, n.slot, n.cells));
}
