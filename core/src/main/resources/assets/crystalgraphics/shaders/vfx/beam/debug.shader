// A tube layer that shows its own construction: a band every block along the path, eight sectors around it, and a
// white line where the rotation-minimising normal points, which must run straight however the path bends. _Shell
// tints it, _Core is the line. For checking CgVfxTube and CgVfxPath, not for looking at.
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Tube  PerDrawValues On }

Properties {
    _Shell    ("Tints it", color) = (0.15, 0.55, 1.6, 1)
    _Core     ("The normal's line", color) = (1, 1, 1, 1)
}

struct v2f { vec2 uv0; };

void surface(in v2f i, inout CgSurface s) {
    float arc = cg_Tube.ring.arc, angle = cg_Tube.angle;
    float band = abs(fract(arc) - 0.5);
    float bandAa = max(fwidth(arc), 1.0e-4);
    float bands = smoothstep(0.25 - bandAa, 0.25 + bandAa, band);
    float sector = mod(floor(angle * 8.0), 2.0);
    float seam = min(angle, 1.0 - angle) * 24.0;
    float line = 1.0 - smoothstep(0.5, 0.5 + fwidth(seam) * 1.5, seam);
    vec3 col = _Shell.rgb * (0.25 + 0.35 * sector + 0.4 * bands);
    col = mix(col, _Core.rgb, line);
    s.baseColor = col * (gl_FrontFacing ? 0.8 : 0.35);
}
