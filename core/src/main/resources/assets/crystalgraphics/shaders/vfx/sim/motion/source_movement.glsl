// CgVfxModule.SourceMovement, after the solver: each particle carried by its source's movement this step, times m0.xyz,
// once it has lived past two steps (Cascade's SourceMovement, FinalUpdate). moved.xyz: the source's movement this step.
#pragma once

void fx_source_movement(inout FxParticle p, FxStep s, vec4 m0, vec4 moved) {
    if (p.age > 2.0 * s.dt) p.position += m0.xyz * moved.xyz;
}
