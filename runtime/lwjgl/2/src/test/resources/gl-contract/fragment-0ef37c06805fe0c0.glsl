#version 460
#extension GL_ARB_bindless_texture : require

layout(binding = 0, std140) uniform P
{
    uint tex;
} p;

layout(binding = 0, std430) readonly buffer CgHeap_textures { sampler2D textures[]; };

layout(location = 0) out vec4 o;

vec4 read(sampler2D s)
{
    return texture(s, vec2(0.5));
}

void main()
{
    o = read(textures[p.tex]);
}

