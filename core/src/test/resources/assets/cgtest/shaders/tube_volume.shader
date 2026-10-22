// A material whose archetype draws a tube chunk by chunk on a sphere ("Tube" = "Volume"): CgVfxSlotRendererTest.
#type spatial

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Tube" = "Volume" }
Queue = "Transparent"

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthWrite OFF
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(i.world * 0.0, 0.0);
    }
}
