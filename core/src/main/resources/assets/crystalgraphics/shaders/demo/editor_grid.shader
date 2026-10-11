// An editor's ground grid, as Blender's viewport draws one: metre and ten-metre lines in absolute world space, the x
// axis red and the z axis green through the origin, fading with distance. Lines only, so it reads from below too.
// Draw it as a large plane at the floor's height, in CgSortLayer.BACKGROUND so effects draw over it. Line maths after
// Ben Golus, "The Best Darn Grid Shader (Yet)" (MIT). _Night dims and cools it under editor_sky's night.
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Fade ("Distance the grid fades out by", float) = 150.0
    _Night ("0 day, 1 night; between, a blend", float) = 0.0
}

struct v2f { vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // A Pass's helpers compile into the vertex stage too, which has no derivatives: the fragment takes them and passes
    // them in, as each coordinate's change a pixel.

    // Coverage of lines `width` cells wide at the integers of uv, antialiased, a flat tint once denser than a pixel.
    float grid_lines(vec2 uv, vec2 deriv, float width) {
        vec2 draw = clamp(vec2(width), deriv, vec2(0.5));
        vec2 aa = max(deriv, vec2(1e-6)) * 1.5;
        vec2 g = 1.0 - abs(fract(uv) * 2.0 - 1.0);
        vec2 line = smoothstep(draw + aa, draw - aa, g);
        line *= clamp(vec2(width) / draw, 0.0, 1.0);
        line = mix(line, vec2(width), clamp(deriv * 2.0 - 1.0, 0.0, 1.0));
        return mix(line.x, 1.0, line.y);
    }

    // Coverage of the line along one axis: |coord| under half `width` pixels.
    float grid_axis(float coord, float deriv, float width) {
        float d = max(deriv, 1e-6);
        return 1.0 - smoothstep(d * width * 0.5, d * (width * 0.5 + 1.0), abs(coord));
    }

    // Premultiplied `color` at `coverage` over `under`.
    vec4 grid_over(vec4 under, vec3 color, float coverage) {
        return vec4(color * coverage, coverage) + under * (1.0 - coverage);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 xz = CG_ABSOLUTE_WORLD_POS(i.worldPos).xz;
        vec2 dx = dFdx(xz), dy = dFdy(xz);
        vec2 deriv = vec2(length(vec2(dx.x, dy.x)), length(vec2(dx.y, dy.y)));
        // World space is camera-relative: the camera stands at its origin.
        float fade = 1.0 - smoothstep(_Fade * 0.25, _Fade, length(i.worldPos));
        // At night, fainter and moonlit blue.
        fade *= mix(1.0, 0.5, _Night);
        vec3 tint = mix(vec3(1.0), vec3(0.55, 0.68, 0.95), _Night);
        vec4 color = vec4(0.0);
        color = grid_over(color, vec3(0.85) * tint, grid_lines(xz, deriv, 0.02) * 0.35 * fade);
        color = grid_over(color, vec3(0.95) * tint, grid_lines(xz * 0.1, deriv * 0.1, 0.015) * 0.6 * fade);
        color = grid_over(color, vec3(0.55, 0.86, 0.0), grid_axis(xz.x, deriv.x, 1.5) * fade);
        color = grid_over(color, vec3(1.0, 0.2, 0.32), grid_axis(xz.y, deriv.y, 1.5) * fade);
        fragColor = color;
    }
}
