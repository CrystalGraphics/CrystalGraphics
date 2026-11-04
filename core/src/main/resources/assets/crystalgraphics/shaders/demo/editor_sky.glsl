// editor_sky's colour below the horizon, shared with editor_grid so the ground it paints meets the sky without a seam.
#pragma once

// The colour toward a camera-relative direction whose y is `y` (below 0), by day (`night` 0) to night (1).
vec3 editor_sky_below(float y, float night) {
    float down = smoothstep(0.0, -0.25, y);
    vec3 day = mix(vec3(0.62, 0.67, 0.74), vec3(0.40, 0.44, 0.51), down);
    vec3 dark = mix(vec3(0.060, 0.095, 0.150) * 0.55, vec3(0.010, 0.012, 0.018), down);
    return mix(day, dark, night);
}
