// The post stack's distortion debug view: the firing's offsets summed over the layers in use, |offset| x 50 in red and
// green, the split in blue. CgPostDebug draws it (-Dcrystalgraphics.post.debug=distortion); nothing else should.
#type none

Tags { "RenderType" = "Opaque" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Overlay"

Properties {
    _Fields     ("Offsets, a layer a slot", sampler2DArray) = "black"
    _FieldCount ("Layers in use", int) = 1
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
        vec4 d = vec4(0.0);
        for (int k = 0; k < _FieldCount; k++) d += textureLod(_Fields, vec3(i.uv, float(k)), 0.0);
        fragColor = vec4(abs(d.xy) * 50.0, d.z, 1.0);
    }
}
