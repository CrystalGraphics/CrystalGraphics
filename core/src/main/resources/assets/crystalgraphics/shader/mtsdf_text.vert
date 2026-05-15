#version 130

in vec2 a_pos;
in vec2 a_uv;
in vec4 a_color;

out vec2 v_uv;
out vec4 v_color;
out vec2 v_worldPos;

uniform mat4 u_projection;
uniform mat4 u_modelview;
uniform float u_time;  // <-- was missing!

const float u_waveAmplitude = 10;
const float u_waveFrequency = 0.005;  // much lower — tune this
const float u_waveSpeed = 1.5;

void main() {
    float wave = u_waveAmplitude * sin(a_pos.x * u_waveFrequency + u_time * u_waveSpeed);
    vec2 warpedPos = vec2(a_pos.x, a_pos.y + wave);

    gl_Position = u_projection * u_modelview * vec4(a_pos, 0.0, 1.0);
    v_uv = a_uv;
    v_color = a_color;
    v_worldPos = a_pos;
}
