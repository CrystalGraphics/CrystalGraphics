#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas; // GL_R8 bitmap atlas, GL_LINEAR filtering
uniform float u_time;

vec3 rainbow(float t) {
    return vec3(
    sin(t),
    sin(t + 2.094), // 2π/3
    sin(t + 4.188)  // 4π/3
    ) * 0.5 + 0.5;
}


void main() {
    float alpha = texture2D(u_atlas, v_uv).r;

    vec4 pixel = vec4(v_color.rgb, v_color.a * alpha);

    fragColor = pixel;//* vec4(rainbow(u_time), 1);
}
