#version 460

layout(binding = 0, std140) uniform U
{
    vec4 c;
} u;

layout(location = 0) out vec4 o;

void main()
{
    o = u.c;
}

