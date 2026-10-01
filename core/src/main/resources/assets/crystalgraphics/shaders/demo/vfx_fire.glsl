// The supernova's fire, shared by its heart and its corona so the flames off the rim burn the colours of the ball.
// CgVfxShowcase.
#pragma once

// Heat in [0, 1] to HDR fire: ember red, orange, gold, white-hot.
vec3 vfx_fire(float heat) {
    vec3 c = mix(vec3(0.5, 0.04, 0.0), vec3(2.3, 0.55, 0.04), smoothstep(0.1, 0.4, heat));
    c = mix(c, vec3(3.0, 1.55, 0.2), smoothstep(0.38, 0.65, heat));
    return mix(c, vec3(4.2, 3.4, 2.0), smoothstep(0.78, 1.0, heat));
}
