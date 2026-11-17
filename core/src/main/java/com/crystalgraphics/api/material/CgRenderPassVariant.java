package com.crystalgraphics.api.material;

/**
 * Which of a material's passes a renderer draws: the passes the material format lowers a {@code .shader} onto, from
 * the outputs its {@code surface()} names ({@code docs/MATERIAL_FORMAT.md} § 4.4).
 *
 * <pre>{@code
 * CgPipeline forward = material.pipeline(CgRenderPassVariant.FORWARD, CgInstanceKind.OBJECT);
 * CgPipeline depth = forward.as(CgPipelineForm.DEPTH);   // its depth alone: the depth prepass draws it
 * boolean glows = material.hasEmissivePass();          // it writes s.emissive
 * }</pre>
 *
 * <p>There is no shadow-caster variant: the world's shadows are ray-traced from each pixel (Unreal's MegaLights,
 * {@code CgSceneLights}), so nothing is drawn from a light's side.</p>
 *
 * <ul>
 *   <li>{@link #FORWARD} takes the material's enabled keywords. A depth draw is a form of it ({@code CgPipelineForm.DEPTH}),
 *       so it keeps them.</li>
 *   <li>Forward resolves through {@code getOrCompileForwardPass(keywords)}, by the first Forward pass's own name, not
 *       {@link #lightModeName()}.</li>
 * </ul>
 */
public enum CgRenderPassVariant {

    /** The colour pass. */
    FORWARD("Forward"),

    /**
     * The light a surface emits ({@code s.emissive}), added into the HDR scene and spread by bloom. Takes the
     * material's keywords. Unlit and faded by fog; a fragment behind the scene's depth is discarded.
     */
    EMISSIVE("Emissive"),

    /**
     * How a surface bends what is behind it ({@code s.refraction}): an offset in UV units and a chromatic split,
     * added into the world's distortion target and applied to the scene once, after the transparent pass. Takes the
     * material's keywords. Unlit and unfogged; a fragment behind the scene's depth is discarded.
     */
    DISTORTION("Distortion"),

    /**
     * Where each pixel of a solid surface was last frame ({@code lib/velocity.glsl}): its vertex placed at last frame's
     * transform and bone palette and at this frame's, the difference written into the velocity target. Lowered for every
     * opaque and masked surface; drawn for what moved or deformed (Unreal's velocity pass). Takes the material's keywords.
     */
    VELOCITY("Velocity"),

    /**
     * Where a blended surface covers, its mark in the responsive target: 1 with {@code ResponsiveAA On} (Unreal's
     * Responsive AA, the default), where temporal AA keeps a short history so what blends and writes no velocity leaves
     * no trail; 0.5 with it Off, a blend that keeps its history. Either marks the texel as the engine's. Lowered for
     * every blended surface; drawn while temporal AA shows. Takes the material's keywords.
     */
    RESPONSIVE("Responsive");

    private final String lightModeName;

    CgRenderPassVariant(String lightModeName) {
        this.lightModeName = lightModeName;
    }

    /** The pass's name in the lowered form the engine compiles: {@code "Forward"}, {@code "Depth"}, ... */
    public String lightModeName() {
        return lightModeName;
    }
}
