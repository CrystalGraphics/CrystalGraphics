// CgVfxModule.Drag: air resistance, which the solver applies implicitly. m.x: linear, a second; m.y: quadratic, a block.
#pragma once
#include "crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl"

void fx_drag(inout FxParticle p, inout FxForces f, FxStep s, vec4 m) {
    f.drag += m.x;
    f.dragQuad += m.y;
}
