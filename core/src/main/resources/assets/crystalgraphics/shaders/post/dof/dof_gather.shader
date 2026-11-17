// Depth of field's gather (CgDepthOfField), one layer a draw: the farther (_Layer.x 0) or the nearer (1). Each texel
// looks round it on rings out to its tile's radius and takes every sample whose own blur reaches it, measured along
// the aperture's polygon toward it, so a bright point blurs to the aperture's shape. A sample spreads its light over
// its whole blur, so it weighs the inverse of that blur's area. The nearer layer's alpha is how much of the texel its
// blurs cover: each sample stands for its share of the rings' disc. With _Carry.x 1 each sample is weighed by _Half's
// blur as the colour was, its value read from _HalfCarry, and the gathered value written alone (CgDepthOfField.carry).
#domain global
#include "crystalgraphics:shaders/env/material/post.glsl"
#include "crystalgraphics:shaders/post/dof/dof.glsl"
Material { Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Half  ("The setup's half-size scene, alpha the circle of confusion", sampler2D) = "black"
    _Tiles ("Each tile's largest nearer (r) and farther (g) radius, dilated", sampler2D) = "black"
    _Shape ("x the blades, y how far from a circle to their polygon, z its turn in radians", vec4) = (5, 0, 0, 0)
    _Layer ("x 1 for the nearer layer, 0 for the farther", vec4) = (0, 0, 0, 0)
    _Carry ("x 1: gather _HalfCarry by the colour's weights", vec4) = (0, 0, 0, 0)
    _HalfCarry ("The setup's half-size carried texture", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}

const int MAX_RINGS = 6;

void fragment(in v2f i, out vec4 fragColor) {
    vec2 size = vec2(textureSize(_Half, 0));
    ivec2 at = ivec2(gl_FragCoord.xy);
    bool near = _Layer.x > 0.5;
    vec2 tile = texelFetch(_Tiles, min(at / 8, textureSize(_Tiles, 0) - 1), 0).rg;
    float radius = near ? tile.r : tile.g;
    vec4 centre = texelFetch(_Half, at, 0);
    bool carry = _Carry.x > 0.5;
    vec4 centreValue = carry ? texelFetch(_HalfCarry, at, 0) : vec4(centre.rgb, 0.0);
    if (radius < 0.5) {
        fragColor = near ? vec4(0.0) : centreValue;
        return;
    }
    float side = near ? -1.0 : 1.0;
    // The centre: its own blur covers it whatever its size
    float own = max(side * centre.a, 0.0);
    float w = own > 0.0 ? 1.0 / max(own * own, 1.0) : 0.0;
    vec4 sum = centreValue * w;
    float weight = w;
    int rings = int(clamp(ceil(radius / 3.0), 1.0, float(MAX_RINGS)));
    int count = 1;
    for (int k = 1; k <= MAX_RINGS; k++) {   // LOOP-BOUND: six rings, 169 samples with the centre
        if (k > rings) break;
        float r = radius * float(k) / float(rings);
        int n = 8 * k;
        for (int j = 0; j < 8 * MAX_RINGS; j++) {   // LOOP-BOUND: a ring's samples, eight more a ring
            if (j >= n) break;
            float a = (float(j) + 0.5 * float(k & 1)) * 6.2831853 / float(n);
            vec2 v = vec2(cos(a), sin(a)) * r;
            vec2 uv = (vec2(at) + 0.5 + v) / size;
            vec4 s = textureLod(_Half, uv, 0.0);
            count++;
            float blur = side * s.a;
            if (blur <= 0.0) continue;
            // How far its blur reaches back toward this texel, along the aperture's edge that way
            float reach = blur * dof_edge(a + 3.1415927, _Shape);
            float sw = clamp(reach - r + 1.0, 0.0, 1.0) / max(blur * blur, 1.0);
            sum += (carry ? textureLod(_HalfCarry, uv, 0.0) : vec4(s.rgb, 0.0)) * sw;
            weight += sw;
        }
    }
    vec4 value = weight > 0.0 ? sum / weight : centreValue;
    float cover = near ? clamp(weight * radius * radius / float(count), 0.0, 1.0) : (weight > 0.0 ? 1.0 : 0.0);
    fragColor = carry ? value : vec4(value.rgb, cover);
}
