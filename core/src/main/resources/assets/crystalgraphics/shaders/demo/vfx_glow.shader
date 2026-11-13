// The glow around an emissive sphere, standing in for bloom: a larger sphere's far wall, added on top of everything,
// brightest just outside the sphere's edge and fading to nothing at its own. Colour and strength per draw in
// CG_OBJECT_CUSTOM1 (rgb, intensity); CG_OBJECT_CUSTOM2.x is the glowing sphere's radius over this one's.
// CgVfxShowcase.
#domain surface
#include "crystalgraphics:shaders/demo/vfx_halo.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Back  DepthTest Off }

struct v2f { vec3 worldPos; };

void vertex(inout CgVertex v, out v2f o) {
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.worldPos = world.xyz;
    v.world = world.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    float shell = length(CG_OBJECT_TO_WORLD[0].xyz);
    float source = shell * CG_OBJECT_CUSTOM2.x;
    vec3 camera = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.worldPos - camera);
    vec2 pass = vfx_pass_by(camera, ray, centre);
    // Brightest where the ray passes nearest the centre, nothing at the shell's edge.
    float through = sqrt(max(1.0 - pass.y * pass.y / (shell * shell), 0.0));
    float glow = pow(through, 2.6) * 0.9 + pow(through, 9.0) * 1.4;
    // Only the light in front of the scene, the glowing sphere included; none from inside that sphere.
    glow *= vfx_seen(pass.x, sqrt(max(pass.y, source) * source), VFX_SCENE_DISTANCE(ray)) * vfx_limb(pass.y, source);
    glow *= smoothstep(source * 0.95, source * 1.4, length(camera - centre));
    s.baseColor = CG_OBJECT_CUSTOM1.rgb * CG_OBJECT_CUSTOM1.a * glow;
    s.opacity = 1.0;
}
