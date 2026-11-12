#pragma once
// What a #domain surface material's vertex() and surface() take: included ahead of the material's own code, before
// cg_env.glsl, so it declares types only. The engine fills a CgVertex from the material's vertex factory, and a
// CgSurface with its defaults before surface() runs (CgMaterialFormat).

// A vertex as its factory supplies it. world is camera-relative and the one value the engine projects: move it to move
// the vertex (World Position Offset), or assign it outright where the material places its own vertices.
struct CgVertex {
    vec3 position;   // object space, as the factory read it
    vec3 normal;     // object space
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
};
