#version 330 core

#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
#include "crystalgraphics:shaders/lib/uv.glsl"

in vec2 v_uv;
in vec4 v_col;

out vec4 out_color;

uniform float u_time;
uniform int   u_mode;

void main() {
    vec2 uv = v_uv;

    if (u_mode == 0) {
        float n = value_noise(uv * 8.0 + u_time * 0.3);
        out_color = vec4(vec3(n), 1.0);

    } else if (u_mode == 1) {
        float f = fbm4(uv * 4.0 + u_time * 0.1);
        float r = fbm_ridged(uv * 3.0, 5);
        out_color = vec4(f, r, 0.5, 1.0);

    } else if (u_mode == 2) {
        vec3 baseCol = v_col.rgb;
        vec3 rotated = rotate_hue(baseCol, fract(u_time * 0.1));
        float lum    = luminance(rotated);
        vec3 desat   = desaturate(rotated, 0.5);
        out_color = vec4(mix(desat, vec3(lum), saturate(sin(u_time) * 0.5 + 0.5)), 1.0);

    } else {
        vec2 rotUv = rotate_uv(uv, u_time * 0.5);
        vec2 polar = cartesian_to_polar_uv(rotUv);
        float n    = value_noise(polar * 6.0);
        out_color  = vec4(n, polar.x, polar.y, 1.0);
    }
}
