#version 460

layout(binding = 0, std140) uniform P
{
    int a;
    int b;
} p;

layout(location = 0) out vec4 o;

void main()
{
    o = vec4(float(p.a), float(p.b), 0.0, 1.0);
}

