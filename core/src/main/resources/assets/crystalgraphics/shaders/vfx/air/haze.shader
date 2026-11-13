// Heat haze round something hot: the scene behind a sphere shimmering in rising, gusting noise (fx_heat), strongest
// where the ray passes nearest the centre and gone at the silhouette and where the sphere meets the scene. Its outline
// is a plume, not the sphere's disc: it reaches the rim above and fades sooner below and to the sides, and its edge is
// torn by noise rising with the air, so it has no shape of its own. Drawn on its sphere's far wall, its entry found
// analytically, so it bends from inside it too; draws nothing, only bends what is behind it (s.refraction), so the
// sharp layers stay unbent. A ray through the hot thing itself (_Source) is left unbent, so the haze shimmers round it
// and never warps it. cg_VfxMesh.values.z is an intensity. The bend reaches _Strength times the intensity times the
// noise, at most about 0.08 of the height.
//
// _InRadii 1 measures the shimmer in the sphere's radius instead of in blocks (_Scale cells across it, _Rise radii a
// second, _Reference radii), so a haze of any size has the same few cells filling its inside: an explosion's, swelling.
// _Edge pulls the bend in from the silhouette, so a shell drawn at its edge stays unbent.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
Material { BlendMode Translucent  ShadingModel Unlit  Faces Back  DepthTest Off  Fog Off  VertexFactory VfxMesh  PerDrawValues On }

Properties {
    _Source ("The hot thing it rises from, left unbent: its radius in the slot's units; 0 for none", float) = 0
    _Strength ("Shimmer at full intensity, share of the screen's height", float) = 0.04
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the haze does on screen", float) = 6
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
    _InRadii  ("1: _Scale, _Rise and _Reference are in its radius, not in blocks", float) = 0
    _Edge     ("Where it stops bending, shares of its radius: whole to x, gone by y", vec4) = (0.85, 1, 0, 0)
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; };

void surface(in v2f i, inout CgSurface s) {
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.world - eye);
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
    float age = cg_Vfx.age, seed = cg_Vfx.seed;
    // Blocks, or the radius: the same cells, rise and reach at any size.
    float unit = _InRadii > 0.5 ? radius : 1.0;
    float scale = _Scale / unit, rise = _Rise * unit, reference = _Reference * unit;
    // Where the ray enters (0 from inside). Its strength is the air's at the point it sees nearest the centre (the
    // eye itself, looking out from inside); its shimmer is sampled there from outside, and halfway to where it
    // leaves from inside, so the view from inside does not move as one sheet.
    float along = dot(centre - eye, ray);
    vec3 line = eye + ray * along - centre;
    float chord = sqrt(max(radius * radius - dot(line, line), 0.0));
    float enter = max(along - chord, 0.0);
    vec3 nearest = eye + ray * max(along, 0.0) - centre;
    vec3 spot = eye + ray * max(along, 0.5 * (along + chord)) - centre;
    vec3 d = nearest / radius;
    float passes = length(d);
    // Hot air rises: squeezed below the centre, let out above.
    vec3 plume = vec3(d.x, d.y * mix(1.7, 0.8, smoothstep(-0.4, 0.4, d.y)), d.z);
    vec3 drift = spot * scale * 0.45 + vec3(0.0, -age * rise * scale * 0.45, 0.0) + seed * 7.0;
    float tear = fx_noise(drift) * 0.7 + fx_noise(drift * 2.3 + 5.1) * 0.3;
    // Full out to half the radius, so a haze round a hot body keeps its strength.
    float body = (1.0 - smoothstep(0.5, 1.0, length(plume) + tear * 0.25)) * (1.0 - smoothstep(_Edge.x, _Edge.y, passes));
    // The hot thing itself: a ray through it stays unbent.
    float core = _Source / max(cg_VfxMesh.size, 1.0e-4);
    if (core > 0.0) body *= smoothstep(core * 0.8, core * 1.3, passes);
    // Nothing of the scene behind scales it: a fade by the scene's depth printed every silhouette into the bend.
    float strength = body * cg_VfxMesh.values.z;
    if (strength < 0.002) discard;
    vec2 wobble = fx_heat(spot * scale, age, rise * scale, seed);
    // Perspective: a far haze moves the scene behind it as little as it covers.
    float far = min(1.0, reference / max(enter, 1.0e-3));
    s.refraction = wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0) * smoothstep(0.0, 0.1, strength);
    s.refractionDepth = FX_EYE_DEPTH(ray, enter);
}
