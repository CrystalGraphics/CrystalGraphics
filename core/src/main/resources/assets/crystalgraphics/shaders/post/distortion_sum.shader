// The firing's distortion field as one offset texture: _Offsets0 to _Offsets4 summed, black past the field's count.
// Drawn once a firing at the field's size, so each bend reads one texture rather than five and needs two units, which
// a host with eight (legacy Forge) can give. CgPostDistortion draws it.
#type none

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Offsets0 ("Offsets 0", sampler2D) = "black"
    _Offsets1 ("Offsets 1", sampler2D) = "black"
    _Offsets2 ("Offsets 2", sampler2D) = "black"
    _Offsets3 ("Offsets 3", sampler2D) = "black"
    _Offsets4 ("Offsets 4", sampler2D) = "black"
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
        vec2 bend = texture(_Offsets0, i.uv).xy + texture(_Offsets1, i.uv).xy + texture(_Offsets2, i.uv).xy
                + texture(_Offsets3, i.uv).xy + texture(_Offsets4, i.uv).xy;
        fragColor = vec4(bend, 0.0, 0.0);
    }
}
