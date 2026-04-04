#version 130

in vec2 v_uv;
in vec4 v_color;

out vec4 fragColor;

uniform sampler2D u_atlas; // GL_R8 bitmap atlas, GL_LINEAR filtering

void main() {
    float alpha = texture2D(u_atlas, v_uv).r;
    fragColor = vec4(v_color.rgb, v_color.a * alpha);
}
