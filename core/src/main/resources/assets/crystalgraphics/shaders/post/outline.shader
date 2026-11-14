// The outline: a toon outline drawn as one fullscreen pass after lighting, before translucency, over the primitives
// whose custom stencil is _Stencil (a component's customDepth(stencil)). The technique of CC2's battle outline
// (Dragon Ball Z: Kakarot, FPostProcessOutlinePS): each pixel tests three edges over two rings of four diagonal taps a
// sub-pixel width apart: depth, the view normal (outlined shading models only) and the outline ID its material wrote.
// Widths and thresholds follow the distance, scaled by the field of view.
//
// What a material writes for it (cg_env.glsl):
//   CG_CUSTOM_DATA_OUT(vec4(colour, id))     the outline's colour where UseOutlineColor is 0, and its ID (1 or more
//                                            masks the outline beside it; 0.02 to 0.12 turns the normal test off)
//   CG_SHADING_MODEL_OUT(model, type)        type 1 to 4 draws the outline in the outline collection's Typen
//
// Distances are in DistanceUnit blocks (0.01: centimetres, as the technique was tuned); the type colours are the
// crystalgraphics:outline parameter collection, CG_GLOBAL_outline_Type1..4.
#domain post

Material { Location BEFORE_TRANSLUCENCY  ColorSpace Linear }

Properties {
    _Stencil ("The custom stencil value outlined", int) = 1
    OutlinedModels ("Shading models outlined and normal-tested: bit n for model n", int) = -2
    UntypedModels ("Shading models that never take a type colour: bit n for model n", int) = 0
    DistanceUnit ("Blocks per distance unit below: 0.01 for centimetres", float) = 0.01
    OutlineColor ("The outline's colour where UseOutlineColor replaces the material's", color) = (0, 0, 0, 1)
    Blight_Influence ("Brightens a dark material colour; scales OutlineColor", float) = 1
    Intensity ("Scales the colour", float) = 0.35
    UseOutlineColor ("0 the material's colour, 1 OutlineColor", float) = 1
    D_Key0 ("Depth decision at distance 0: (value, distance)", vec4) = (1, 0, 0, 0)
    D_Key1 ("Depth decision's second key", vec4) = (0.2, 200, 0, 0)
    D_Key2 ("Depth decision's third key", vec4) = (0.005, 4000, 0, 0)
    N_Key0 ("Normal decision at distance 0: (value, distance)", vec4) = (3, 0, 0, 0)
    N_Key1 ("Normal decision's second key", vec4) = (2, 200, 0, 0)
    N_Key2 ("Normal decision's third key", vec4) = (0, 500, 0, 0)
    End_Point ("Gone by this distance", float) = 100000
    Near_Dim_Start ("The normal fade's distance", float) = 500
    Near_Point ("Thick by this distance", float) = 500
    Thin_Point ("Thin up to this distance", float) = 1
    Thick_Width ("Width past Near_Point, pixels", float) = 1
    Thin_Width ("Width up close, pixels", float) = 2
    Far_Dim ("The far dim's floor", float) = 0.7
    Tex_Entering ("The ID test's gain", float) = 5
    Width_Depth ("The depth test's width", float) = 1
    Width_Normal ("The normal test's width", float) = 1
    Width_Texture ("The ID test's width", float) = 1
    Normal_Fade ("The normal test's scale", float) = 2
    Tex_Threshold ("The ID threshold: (near, far, from, to)", vec4) = (1, 2, 0, 1000)
    Far_Point ("Dimmed past this distance", float) = 10000
    Tests ("Which edge tests draw: bit 0 depth, 1 normal, 2 ID", int) = 7
}

// 1 where the pixel's shading model is outlined, else 0
float outlined(vec2 uv) {
    return float((uint(OutlinedModels) >> (CG_SHADING_MODEL_ID(uv).x & 31u)) & 1u);
}

float depth_u(vec2 uv) { return CG_SCENE_EYE_DEPTH(uv) / DistanceUnit; }

// 0 where no draw of ours wrote a normal (a host-drawn pixel): such a tap is not outlined, so it never counts.
// Compared in a view space looking down +z: the normal test takes a vector's largest component.
vec3 view_normal(vec2 uv) {
    vec3 w = CG_SCENE_WORLD_NORMAL(uv);
    vec3 v = mat3(cg_ViewMatrix) * (dot(w, w) > 0.0 ? normalize(w) : w);
    return vec3(v.xy, -v.z);
}

// The ID's mask term: 1 for an ID of 1 or more, and for an ID of exactly 0
float id_step(float id) { return ceil(ceil(id) * (id - 1.0) + 0.98); }

vec3 type_colour(uint type) {
    return type == 1u ? CG_GLOBAL_outline_Type1.rgb : type == 2u ? CG_GLOBAL_outline_Type2.rgb
            : type == 3u ? CG_GLOBAL_outline_Type3.rgb : CG_GLOBAL_outline_Type4.rgb;
}

void surface(in CgScreen i, inout CgPostSurface s) {
    vec2 uv = i.uv;
    if (CG_CUSTOM_STENCIL(uv) != _Stencil || outlined(uv) == 0.0) discard;
    uvec2 model = CG_SHADING_MODEL_ID(uv);
    vec4 custom = CG_CUSTOM_DATA(uv);
    float id = custom.a;
    float d = depth_u(uv);
    vec3 normal = view_normal(uv);
    vec2 px = 1.0 / CG_RESOLUTION.xy;
    uint tests = uint(Tests);

    // The width: thin to thick by distance, over the field of view (degrees, horizontal)
    float fov = 2.0 * atan(1.0 / cg_ProjMatrix[0][0]) * 57.29578;
    float sw = fov / 60.0;
    float fovScale = min(0.9999, max(0.0, 60.0 / fov));
    float t = clamp(d / Near_Point - Thin_Point / (Near_Point - Thin_Point), 0.0, 1.0);
    float far = clamp((d / End_Point - Far_Point / (End_Point - Far_Point)) * sw, 0.0, 1.0);
    float near = clamp((d / Far_Point - Near_Dim_Start / (Far_Point - Near_Dim_Start)) * sw, 0.0, 1.0);
    float width = -mix(Thin_Width, Thick_Width, t) * fovScale;
    float wDepth = Width_Depth * width, wNormal = Width_Normal * width, wTex = Width_Texture * width;

    // Two rings of four diagonal taps, at floor(w) and floor(w) + 1 pixels, at least one
    float ringA = min(-1.0, floor(wDepth)), ringB = ringA + 1.0;
    float texA = min(-1.0, floor(wTex)), texB = texA + 1.0;
    vec2 a = px * ringA, b = px * ringB, ta = px * texA, tb = px * texB;

    // 1. Depth: each ring's sum against four times the pixel's
    float lapA = depth_u(uv - a) + depth_u(uv + vec2(-a.x, a.y)) + depth_u(uv + vec2(a.x, -a.y)) + depth_u(uv + a) - 4.0 * d;
    float lapB = depth_u(uv - b) + depth_u(uv + vec2(-b.x, b.y)) + depth_u(uv + vec2(b.x, -b.y)) + depth_u(uv + b) - 4.0 * d;

    // 2. Normal: one ring at the normal width, its outlined taps against as many times the pixel's
    vec2 n = px * wNormal;
    vec2 nTap[4] = vec2[4](uv - n, uv + vec2(-n.x, n.y), uv + vec2(n.x, -n.y), uv + n);
    vec3 sumN = vec3(0.0);
    float counted = 0.0;
    for (int k = 0; k < 4; k++) {
        float o = outlined(nTap[k]);
        counted += o;
        sumN += view_normal(nTap[k]) * o;
    }
    float fade = Normal_Fade * pow(clamp(1.0 - d / Near_Dim_Start * sw, 0.0, 1.0), 5.0);
    vec3 dn = (sumN - normal * counted) * fade;
    float normalLap = max(dn.x, max(dn.y, dn.z));

    // The decision curves: three keys, the second segment eased into the first
    vec2 seg2 = clamp((vec2(d) - vec2(D_Key1.y, N_Key1.y)) / vec2(D_Key2.y - D_Key1.y, N_Key2.y - N_Key1.y) * sw, 0.0, 1.0);
    vec2 seg1 = clamp((vec2(d) - vec2(D_Key0.y, N_Key0.y)) / vec2(D_Key1.y - D_Key0.y, N_Key1.y - N_Key0.y) * sw, 0.0, 1.0);
    vec2 key2 = seg2 * vec2(D_Key2.x - D_Key1.x, N_Key2.x - N_Key1.x) + vec2(D_Key1.x, N_Key1.x);
    vec2 decision = 1.0 / (seg1 * (key2 - vec2(D_Key0.x, N_Key0.x)) + vec2(D_Key0.x, N_Key0.x));

    float idAbove002 = ceil(id - 0.02);
    float keepNormal = max(1.0 - idAbove002, ceil(id - 0.12));
    float normalEdge = clamp(normalLap - decision.y, 0.0, 1.0) * keepNormal * float((tests >> 1u) & 1u);
    float edgeA = ceil(clamp(lapA - decision.x, 0.0, 1.0));
    float edgeB = ceil(clamp(lapB - decision.x, 0.0, 1.0));
    float depthEdge = mix(edgeB, edgeA, fract(-wDepth)) * float(tests & 1u);
    float geometric = max(depthEdge, normalEdge) * idAbove002;

    // 3. ID: each ring's sum against four times the pixel's, less the threshold; and a count of taps unlike it
    float threshold = clamp((d - Tex_Threshold.z) / (Tex_Threshold.w - Tex_Threshold.z) * sw, 0.0, 1.0)
            * (Tex_Threshold.y - Tex_Threshold.x) + Tex_Threshold.x;
    float idA[4] = float[4](CG_CUSTOM_DATA(uv - ta).a, CG_CUSTOM_DATA(uv + vec2(-ta.x, ta.y)).a,
                            CG_CUSTOM_DATA(uv + vec2(ta.x, -ta.y)).a, CG_CUSTOM_DATA(uv + ta).a);
    float idB[4] = float[4](CG_CUSTOM_DATA(uv - tb).a, CG_CUSTOM_DATA(uv + vec2(-tb.x, tb.y)).a,
                            CG_CUSTOM_DATA(uv + vec2(tb.x, -tb.y)).a, CG_CUSTOM_DATA(uv + tb).a);
    float sumA = 0.0, sumB = 0.0, stepA = 0.0, stepB = 0.0, masked = 0.0;
    for (int k = 0; k < 4; k++) {
        sumA += idA[k];
        sumB += idB[k];
        stepA += id_step(idA[k]);
        stepB += id_step(idB[k]);
        masked += trunc(idA[k]) + trunc(idB[k]);
    }
    float own = id_step(id);
    float idEdgeA = floor(clamp(max(stepA - 4.0 * own, (sumA - 4.0 * id) * Tex_Entering - threshold), 0.0, 1.0));
    float idEdgeB = floor(clamp(max(stepB - 4.0 * own, (sumB - 4.0 * id) * Tex_Entering - threshold), 0.0, 1.0));
    float idEdge = mix(idEdgeB, idEdgeA, fract(-wTex)) * float((tests >> 2u) & 1u);

    // Masked beside an ID of 1; gone by End_Point; dimmed past Far_Point
    float edge = max(geometric, idEdge) * (1.0 - clamp(masked, 0.0, 1.0));
    edge = min(min(1.0, 5.0 * (1.0 - far)) * min(1.0, max(Far_Dim, 1.0 - near)), edge);
    edge = 1.0 - min(1.0, 1.0 - edge);

    // The colour: the material's, brightened where dark, or OutlineColor; times the edge; or the type's colour
    float lum = dot(custom.rgb, vec3(0.299, 0.587, 0.114));
    vec3 c = custom.rgb * ((1.0 - lum) * Blight_Influence + 1.0);
    c = Intensity * mix(c, OutlineColor.rgb * Blight_Influence, UseOutlineColor);
    c *= edge;
    uint type = ((uint(UntypedModels) >> (model.x & 31u)) & 1u) != 0u ? 0u : model.y;
    if (type >= 1u && type <= 4u) c = type_colour(type);
    s.color = mix(s.color, c, edge);   // s.opacity is not used: naming it turns the pass white
}
