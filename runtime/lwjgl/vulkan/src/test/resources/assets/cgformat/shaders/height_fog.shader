// The engine's post/height_fog in the material format: Unreal's exponential height fog over the lit opaque scene.
#domain post
Material { Location BEFORE_TRANSLUCENCY  SceneColorMargin 0.001 }
Properties {
    _Fog0      ("Density at the observer, falloff a block, eye above observer, start distance", vec4) = (0, 0, 0, 0)
    _Fog1      ("Density, observer above the fog height, cutoff or 0, least transmittance", vec4) = (0, 0, 0, 0)
    _FogColour ("Inscattering colour, linear", color) = (0.447, 0.638, 1, 1)
}
// CalculateLineIntegralShared: 0.6931 and 0.2402 are ln 2 and (ln 2)^2 / 2, its Taylor expansion at 0
float fog_line_integral(float falloff, float rayZ, float originTerms) {
    float f = max(-127.0, falloff * rayZ);
    return originTerms * (abs(f) > 0.01 ? (1.0 - exp2(-f)) / f : 0.6931 - 0.2402 * f);
}
void surface(in CgScreen i, inout CgPostSurface s) {
    vec3 scene = CG_SCENE_COLOR(i.uv).rgb;
    if (!CG_LINEAR_SCENE) scene = cg_SceneDecode(scene);
    float eye = CG_SCENE_EYE_DEPTH(i.uv);
    vec2 ndc = i.uv * 2.0 - 1.0;
    vec3 viewPos = vec3(ndc.x / cg_ProjMatrix[0][0], ndc.y / cg_ProjMatrix[1][1], -1.0) * eye;
    vec3 ray = transpose(mat3(cg_ViewMatrix)) * viewPos;
    ray.y += _Fog0.z;
    float rayLength = length(ray);
    float originTerms = _Fog0.x, span = rayLength, rayZ = ray.y;
    float exclude = _Fog0.w;
    if (exclude > 0.0) {
        float t = exclude / rayLength;
        float z = _Fog1.y + t * ray.y;
        span = (1.0 - t) * rayLength;
        rayZ = ray.y - t * ray.y;
        originTerms = _Fog1.x * exp2(-max(-127.0, _Fog0.y * z));
    }
    float transmittance = max(clamp(exp2(-fog_line_integral(_Fog0.y, rayZ, originTerms) * span), 0.0, 1.0), _Fog1.w);
    if (_Fog1.z > 0.0 && rayLength > _Fog1.z) transmittance = 1.0;
    vec3 fogged = scene * transmittance + _FogColour.rgb * (1.0 - transmittance);
    vec3 result = mix(scene, fogged, POST_WEIGHT);
    s.color = CG_LINEAR_SCENE ? result : cg_SceneEncode(result);
}
