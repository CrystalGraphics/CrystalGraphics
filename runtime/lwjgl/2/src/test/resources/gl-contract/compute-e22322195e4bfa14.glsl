#version 460
layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

layout(binding = 0, std430) buffer B
{
    uint v[];
} b;

layout(binding = 0, std140) uniform U
{
    vec4 c;
} u;

layout(binding = 1, std140) uniform P
{
    uint at;
} p;

void main()
{
    b.v[p.at] = uint(u.c.x);
}

