// CgVfxModule.Drag: air resistance, which the solver applies implicitly. Its declared inputs in order: m.x the drag,
// a second; m.y its scale (Cascade's AccelerationDragScaleOverLife); m.z use_quadratic; m.w the quadratic term, a block.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_drag(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.drag += m.x * m.y;
    f.dragQuad += m.z > 0.5 ? m.w : 0.0;
}
