// Heat haze along a beam: a sheath of bent air round a path, past the body's glow where the scene still shows through,
// rippling forward along the beam and torn at its edge by rising noise, its strongest bend split slightly by colour.
// The bend is a share of the screen's height, held to _Hold of the sheath's width on screen, so a far beam keeps it. A
// tube layer, its radius the layer's times the ring's, capped at _Cap blocks. Drawn on the tube's far wall, where the
// ray meets it found analytically, so it bends from inside it too; draws nothing, only bends what is behind it
// (s.refraction), so the sharp layers stay unbent. A ray through the beam itself, within _Core times its ring's radius
// of the path, is left unbent.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_haze.glsl"
Material { BlendMode Translucent  ShadingModel Unlit  Faces Back  DepthTest Off  Fog Off  VertexFactory Tube  PerDrawValues On }

Properties {
    _Cap      ("Widest the sheath gets, blocks: it holds still while the beam throbs; 0 for no cap", float) = 0
    _Strength ("Bend at full intensity, share of the screen's height", float) = 0.012
    _Hold     ("The bend's most, as a share of the sheath's width on screen", float) = 0.12
    _Core     ("The beam's visible reach, in ring radii: left unbent", float) = 1
    _Peak     ("Where the bend is strongest, in ring radii: just past the body's glow", float) = 3.2
    _Flow     ("Share of the bend in ripples racing along the beam; the rest shimmers", float) = 0.6
    _Rings    ("Ripples a block along the beam", float) = 0.6
    _Pulse    ("Ripples passing a point a second, toward the head", float) = 3
    _Fringe   ("How much more red bends than green, and blue less", float) = 0.15
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
    _Noise ("Noise", sampler3D) = "cg_noise"
}

// the path's point at this ring; the sheath's radius
struct v2f { vec3 world; vec3 axis; float edge; };

void vertex(inout CgVertex v, out v2f o) {
    // Capped below the body's throb, so the haze holds still while the beam pulses; the taper at its ends survives.
    float full = max(cg_Tube.ring.radius * cg_Tube.size, 1.0e-4);
    float edge = _Cap > 0.0 ? min(full, _Cap) : full;
    v.position = cg_Tube.ring.position + (v.position - cg_Tube.ring.position) * (edge / full);
    v.world = cg_Tube.origin + v.position;
    o.world = v.world;
    o.axis = cg_Tube.origin + cg_Tube.ring.position;
    o.edge = edge;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.world - eye);
    vec3 t = normalize(cg_Tube.ring.tangent);
    float age = cg_Tube.header.w, seed = cg_Tube.header.z;
    float ring = cg_Tube.ring.radius, edge = i.edge;
    // Where the ray enters the sheath (0 from inside). Its strength is the air's at the point it sees nearest the
    // path (the eye itself, looking out from inside); its shimmer is sampled there from outside, and halfway to
    // where it leaves from inside, so the view from inside does not move as one sheet.
    vec4 q = fx_ray_axis(eye, ray, i.axis, t);
    float chord = sqrt(max(edge * edge - q.y * q.y, 0.0)) / max(q.w, 0.05);
    float enter = max(q.x - chord, 0.0);
    vec3 seen = eye + ray * max(q.x, 0.0) - i.axis;
    float passes = length(seen - t * dot(seen, t));
    vec3 at = eye + ray * max(q.x, 0.5 * (q.x + chord));
    vec3 rel = at - i.axis;
    float arc = cg_Tube.ring.arc + dot(rel, t);
    // From the path's origin, not the camera: camera-relative, the shimmer slid as the camera moved.
    vec3 local = at - cg_Tube.origin;
    vec3 drift = local * _Scale * 0.45 + vec3(0.0, -age * _Rise * _Scale * 0.45, 0.0) + seed * 7.0;
    float tear = fx_noise(drift) * 0.7 + fx_noise(drift * 2.3 + 5.1) * 0.3;
    // Unbent through the beam, strongest past its glow, torn away toward the sheath's edge.
    float peak = min(_Peak * ring, edge * 0.7);
    float strength = smoothstep(ring * _Core * 0.9, peak, passes)
            * (1.0 - smoothstep(peak, edge, passes + tear * 0.25 * (edge - peak)));
    // Nothing of the scene behind scales it: a fade by the scene's depth printed every silhouette into the bend.
    if (strength < 0.002) discard;
    // Ripples racing toward the head push the scene along the beam on screen; the rest is rising shimmer.
    float ripple = sin((arc * _Rings - age * _Pulse + tear * 0.5) * 6.28318531);
    vec2 flow = FX_HAZE_SCREEN_DIR(t) * ripple * (0.6 + 0.4 * fx_noise(drift * 0.7 + 3.3));
    vec2 wobble = mix(fx_heat(local * _Scale, age, _Rise * _Scale, seed), flow, _Flow);
    float hold = FX_HAZE_HOLD(_Strength, edge, distance(eye, at), _Hold);
    float fade = smoothstep(0.0, 0.1, strength);
    s.refraction = wobble * _Strength * hold * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0) * fade;
    s.refractionSplit = _Fringe * fade;
    s.refractionDepth = FX_EYE_DEPTH(ray, enter);
}
