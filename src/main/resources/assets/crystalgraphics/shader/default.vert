#version 130
in vec2 a_pos;
in vec4 a_color;
uniform mat4 u_projection;
out vec4 v_color;
void main() {
    v_color = a_color;
    gl_Position = u_projection * vec4(a_pos, 0.0, 1.0);
}
