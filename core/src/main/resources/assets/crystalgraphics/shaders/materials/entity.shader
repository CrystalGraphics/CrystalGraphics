// A Minecraft entity's look: vanilla's rendertype_entity_cutout (1.17.1 to 26.3, include/light.glsl), the material
// contract on top. Two fixed lights, max(0, n.L0) + max(0, n.L1) times 0.6 plus 0.4 at most 1, per vertex; cutout below
// _Cutoff; the overlay mixed over the texture; the lightmap at the draw's light and the fog by the generated main.
// Its blend and cull are a CgMaterialState: cutout (the default), no cull, translucent.
#domain surface

Properties {
    _MainTex      ("The entity's texture, nearest-filtered", sampler2D) = "white"
    _Cutoff       ("Alpha below this is not drawn: vanilla's 0.1; 0 for none", float) = 0.1
    _Tint         ("The contract's tint: multiplies the colour, by its alpha", color) = (1, 1, 1, 1)
    _Flash        ("The contract's flash: its colour over the texture by its alpha (vanilla's overlay: the hurt flash is red at 0.3)", color) = (1, 0, 0, 0)
    _RimColor     ("The contract's rim colour", color) = (1, 1, 1, 1)
    _RimStrength  ("The contract's rim strength: 0 for none", float) = 0.0
    _RimPower     ("The contract's rim power: higher hugs the silhouette", float) = 2.0
}

struct v2f { vec2 uv; vec4 colour; vec3 normal; vec3 world; };

void vertex(inout CgVertex v, out v2f o) {
    vec3 n = normalize(CG_NORMAL_MATRIX * cg_Normal);
    // minecraft_mix_light, in world axes: the camera's turn cancels between Minecraft's view-space light and normal
    float light = min(1.0, (max(0.0, dot(CG_ENTITY_LIGHT0, n)) + max(0.0, dot(CG_ENTITY_LIGHT1, n))) * 0.6 + 0.4);
    o.uv = cg_TexCoord0;
    o.colour = vec4(cg_Color.rgb * light, cg_Color.a);
    o.normal = n;
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.world = world.xyz;
    v.world = world.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 c = texture(_MainTex, i.uv);
    if (c.a < _Cutoff) discard;
    c *= i.colour;
    c.rgb *= mix(vec3(1.0), _Tint.rgb, clamp(_Tint.a, 0.0, 1.0));
    // The overlay, as vanilla's fragment mixes it: mix(overlay.rgb, colour, overlay.a), alpha 1 - the flash's
    c.rgb = mix(c.rgb, _Flash.rgb, clamp(_Flash.a, 0.0, 1.0));
    if (_RimStrength > 0.0) {
        vec3 toEye = normalize(CG_CAMERA_WORLD_POS - i.world);
        float facing = 1.0 - max(dot(normalize(i.normal), toEye), 0.0);
        c.rgb += _RimColor.rgb * _RimStrength * pow(facing, _RimPower);
    }
    s.baseColor = c.rgb;
}
