#pragma once
// The sun's shadow handed to a program composing its own (Unreal's modulated shadow): CgWorldLights' cascaded shadow
// map of our casters, read as world_lights_sample.shader reads it. Included by the compiler wherever a program's source
// names CG_DIRECTIONAL_SHADOW, which then binds _cg_SunMap, _cg_SunRows, _cg_Sun and _cg_SunEye. Only a transparent
// firing has the map: in an opaque one the deferred pass shadows our pixels already, and this reads 1.
//
//     float lit = CG_DIRECTIONAL_SHADOW(cg_FragmentPosition()) * clamp(3.0 * nol + CG_DIRECTIONAL_SHADOW_WRAP, 0.0, 1.0);
//     c.rgb *= mix(CG_DIRECTIONAL_SHADOW_COLOR, vec3(1.0), lit);

// Cascade k's visibility at eye-relative w: Unreal's Manual3x3PCF and soft comparison, as sun_cascade. Past the last, lit.
float cg_sun_cascade(int k, vec3 w) {
    if (k > 3) return 1.0;
    vec4 r0 = _cg_SunRows(k * 4), r1 = _cg_SunRows(k * 4 + 1), r2 = _cg_SunRows(k * 4 + 2), range = _cg_SunRows(k * 4 + 3);
    vec2 uv = vec2(dot(r0.xyz, w) + r0.w, dot(r1.xyz, w) + r1.w);
    float depth = dot(r2.xyz, w) + r2.w, scale = 1.0 / range.w;
    int size = textureSize(_cg_SunMap, 0).x / 2;
    ivec2 origin = ivec2(k & 1, k >> 1) * size;
    vec2 texel = uv * float(size) - 0.5, f = fract(texel);
    ivec2 corner = ivec2(floor(texel)) - 1;
    float lit = 0.0;
    for (int y = 0; y < 4; y++) {
        float wy = y == 0 ? 1.0 - f.y : y == 3 ? f.y : 1.0;
        for (int x = 0; x < 4; x++) {
            float wx = x == 0 ? 1.0 - f.x : x == 3 ? f.x : 1.0;
            ivec2 at = origin + clamp(corner + ivec2(x, y), ivec2(0), ivec2(size - 1));
            lit += wx * wy * clamp((texelFetch(_cg_SunMap, at, 0).r - depth) * scale + 1.0, 0.0, 1.0);
        }
    }
    return lit / 9.0;
}

// The sun's visibility at camera-relative p: 1 lit, 0 in our casters' shadow, the cascade its view depth falls in
// blended into the next and the last fading to lit. 1 while the sun is down or casts nothing.
float cg_directional_shadow(vec3 p) {
    if (_cg_Sun.x < 0.5 || CG_DAYLIGHT <= 0.0 || CG_SUN_DIRECTION.y <= 0.0) return 1.0;
    vec3 w = p - _cg_SunEye.xyz;
    float z = -(cg_ViewMatrix * vec4(p, 1.0)).z;
    for (int k = 0; k < 4; k++) {
        vec4 range = _cg_SunRows(k * 4 + 3);
        if (z >= range.z) continue;
        float v = cg_sun_cascade(k, w);
        if (z <= range.y) return v;
        return mix(v, cg_sun_cascade(k + 1, w), (z - range.y) / max(range.z - range.y, 1.0e-4));
    }
    return 1.0;
}

// cg_env.glsl's placeholders, which fail to link where nothing hands the sun.
#undef CG_DIRECTIONAL_SHADOW
#undef CG_DIRECTIONAL_SHADOW_COLOR
#undef CG_DIRECTIONAL_SHADOW_WRAP
#define CG_DIRECTIONAL_SHADOW(p) cg_directional_shadow(p)
// What a shadow leaves of the draw's light: its sky light over the whole, as the world's sun shadow takes the sun's part.
#define CG_DIRECTIONAL_SHADOW_COLOR (CG_SKY_LIGHT_COLOR / max(CG_LIGHTMAP(CG_OBJECT_LIGHT), vec3(1.0e-4)))
// Minecraft's sun lights nothing past the terminator.
#define CG_DIRECTIONAL_SHADOW_WRAP 0.0
