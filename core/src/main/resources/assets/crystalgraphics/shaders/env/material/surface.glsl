#pragma once
// What a #domain surface material's vertex() and surface() take: included ahead of the material's own code, before
// cg_env.glsl, so it declares types only. The engine fills a CgVertex from the material's vertex factory, and a
// CgSurface with its defaults before surface() runs (CgMaterialFormat).

// A vertex as its factory supplies it. world is camera-relative and the one value the engine projects: move it to move
// the vertex (World Position Offset), or assign it outright where the material places its own vertices.
struct CgVertex {
    vec3 position;   // object space, as the factory read it
    vec3 normal;     // world space
    vec4 color;      // white where the factory has none
    vec2 uv0;        // a set the mesh lacks repeats the one before it
    vec2 uv1;
    vec2 uv2;
    vec2 uv3;
    vec3 world;      // camera-relative world position
};

// A material's outputs, Unreal's material result pins. Each output written decides the passes the material draws in.
struct CgSurface {
    vec3 baseColor;          // (1, 1, 1): lit by the shading model, or shown as written when Unlit
    float opacity;           // 1: how much it hides what is behind it, in a translucent blend mode
    float opacityMask;       // 1: solid where at or above MaskClip (Masked, ClipDepth, DepthPrepass)
    vec3 emissive;           // (0, 0, 0): light given off, linear, unlit; past 1 blooms
    vec3 normal;             // (0, 1, 0): world space, for TranslucencyLighting Surface
    vec2 refraction;         // (0, 0): the bend of what is behind it, in screen UV
    float refractionSplit;   // 0: red bends 1 + split as far, blue 1 - split
    float refractionDepth;   // the pixel's eye depth: where the bend starts
    float pixelDepthOffset;  // 0: blocks its depth is pushed farther, never nearer
    vec4 customData;         // (0, 0, 0, 0): data a post material reads
    uvec2 shadingModelId;    // (1, 0): a model and type a post material reads
    vec2 light;              // the draw's (block, sky) light
    float volumeDepth;       // 0: a volume's eye distance, for the volume pass
    float depth;             // the fragment's own: the eye depth it stands at (cg_EyeDepth(point) for a traced point)
};

// What a #domain post material's surface() takes: the pixel of the screen it writes.
struct CgScreen {
    vec2 uv;                 // 0..1 across the target
    vec2 pixel;              // gl_FragCoord.xy
};

// A post material's outputs.
struct CgPostSurface {
    vec3 color;              // the scene's colour at uv where surface() reads it before writing: what the pixel becomes
    float opacity;           // 1: how much of color replaces the scene
};

// What a #domain ui material's surface() takes: the canvas element under the pixel.
struct CgUiInput {
    vec2 uv;                 // the element's texture coordinates (a quad's uv rect; a curve's, t along it)
    vec2 param;              // 0..1 across the element, past either end on an antialiased edge
    vec2 local;              // the pixel inside the element, in its units
    vec2 size;               // the element's size, in the same units
    vec4 color;              // its colour (a quad's color, a curve's along it, a text's paint)
    vec4 custom0;            // its two free values
    vec4 custom1;
};

// A ui material's outputs; clip, opacity and premultiplying are the engine's.
struct CgUiSurface {
    vec4 color;              // the element's colour, straight alpha
    float coverage;          // the element's antialiased edge: how much of the pixel it covers
    bool premultiplied;      // true: color is already premultiplied (a layer drawn at screen resolution)
};

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// What a ui element writes, k its alpha's scale (coverage, clip, opacity), as its pass's blend wants it (CG_FOG_MODE),
// one form per blend mode as Unreal's SimpleElementPixelShader: premultiplied over what is behind, straight alpha,
// added weighed by its alpha, or multiplied toward white where it is transparent. What a primitive ends with.
vec4 cg_UiWrite(CgUiSurface s, float k) {
    vec4 p;
    if (s.premultiplied) {
        p = s.color * k;
    } else {
        float a = clamp(s.color.a * k, 0.0, 1.0);
        p = vec4(clamp(s.color.rgb, 0.0, 1.0) * a, a);
    }
#if CG_FOG_MODE == 1
    return p;
#elif CG_FOG_MODE == 2
    return vec4(p.rgb, 0.0);
#elif CG_FOG_MODE == 3
    return vec4(vec3(1.0 - p.a) + p.rgb, 1.0);
#else
    return p.a > 0.0 ? vec4(p.rgb / p.a, p.a) : vec4(0.0);
#endif
}
#endif
