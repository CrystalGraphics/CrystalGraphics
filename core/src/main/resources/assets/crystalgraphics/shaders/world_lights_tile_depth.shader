// The window depth's range over each 4x4 pixels, the sky left out: the first of the entity tiles' two steps
// (world_lights_tiles.shader reads 4x4 of these), so no invocation fetches a 16x16 tile's 256 depths in a row.
// Written: the least and the most window depth; (2, -1) where every pixel is sky or off the target.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" }
Queue = "Overlay"

Properties {
    _Screen   ("The full target's width and height",                vec4) = (1, 1, 0, 0)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 first = ivec2(gl_FragCoord.xy) * 4, size = ivec2(_Screen.xy);
        bool reversed = cg_DepthParams.x > 0.5;
        float lo = 2.0, hi = -1.0;
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                ivec2 px = first + ivec2(x, y);
                if (px.x >= size.x || px.y >= size.y) continue;
                float window = texelFetch(cg_DepthBuffer, px, 0).r;
                if (reversed ? window <= 0.0 : window >= 1.0) continue;
                lo = min(lo, window);
                hi = max(hi, window);
            }
        }
        fragColor = vec4(lo, hi, 0.0, 0.0);
    }
}
