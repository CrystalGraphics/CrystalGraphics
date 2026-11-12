// Waves: World Position Offset (12.7), with a helper the vertex stage calls and a fragment-only built-in
#domain surface
Properties {
    _Amp ("Wave height, blocks", float) = 0.2
    _Water ("Water", color) = (0.1, 0.3, 0.6, 1)
}
struct v2f { vec2 uv0; };
float wave(vec3 p) { return sin(CG_TIME + p.x * 2.0) * _Amp; }
float ripple(vec2 uv) { return 0.8 + 0.2 * fwidth(uv.x); }
void vertex(inout CgVertex v, out v2f o) {
    v.world.y += wave(v.position);
    o.uv0 = v.uv0;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = _Water.rgb * ripple(i.uv0); }
