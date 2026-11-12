// The harness's post_chain in the material format: the scene halved at half size, that plus a quarter at a quarter
// size, and that over the scene; a step reading its input at a size other than its own draws magenta.
#domain post
Material { Location AFTER_COMPOSITE  SceneColorMargin 0.01 }
Step Down {
    Target RGBA16_FLOAT  Scale 0.5
    void surface(in CgScreen i, inout CgPostSurface s) { s.color = CG_SCENE_COLOR(i.uv).rgb * 0.5; }
}
Step Quarter {
    Target RGBA16_FLOAT  Scale 0.25
    void surface(in CgScreen i, inout CgPostSurface s) {
        bool sized = CG_STEP_SIZE(Down).x == int(CG_RESOLUTION.x * 0.5);
        s.color = sized ? CG_STEP(Down, i.uv).rgb + 0.25 : vec3(1.0, 0.0, 1.0);
    }
}
Step Over {
    void surface(in CgScreen i, inout CgPostSurface s) {
        bool sized = CG_STEP_SIZE(Quarter).x == int(CG_RESOLUTION.x * 0.25);
        s.color = sized ? CG_STEP(Quarter, i.uv).rgb : vec3(1.0, 0.0, 1.0);
    }
}
