#version 130

in vec2 a_pos;
in vec2 a_uv;
in vec4 a_color; // GL_UNSIGNED_BYTE normalized: 4 packed bytes -> vec4 [0,1]

out vec2 v_uv;
out vec4 v_color;

uniform mat4 u_projection;
uniform mat4 u_modelview;

void main() {
    gl_Position = u_projection * u_modelview * vec4(a_pos, 0.0, 1.0);
    v_uv = a_uv;
    v_color = a_color;
}
