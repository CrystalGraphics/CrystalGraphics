#version 460

layout(binding = 0, std140) uniform A
{
    vec4 c;
} a;

layout(binding = 1, std140) uniform B
{
    vec4 c;
} b;

layout(location = 0) out vec4 o;

void main()
{
    o = a.c + b.c;
}

