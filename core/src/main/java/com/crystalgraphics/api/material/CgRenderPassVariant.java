package com.crystalgraphics.api.material;

/**
 * Which of a material's passes a renderer draws: the passes the material format lowers a {@code .shader} onto, from
 * the outputs its {@code surface()} names ({@code docs/MATERIAL_FORMAT.md} § 4.4).
 *
 * <pre>{@code
 * CgPipeline depth = material.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT);
 * boolean glows = material.hasEmissivePass();          // it writes s.emissive
 * }</pre>
 *
 * <p>There is no shadow-caster variant: the world's shadows are ray-traced from each pixel (Unreal's MegaLights,
 * {@code CgWorldLights}), so nothing is drawn from a light's side.</p>
 *
 * <ul>
 *   <li>{@link #FORWARD} takes the material's enabled keywords; {@link #DEPTH} none.</li>
 *   <li>Forward resolves through {@code getOrCompileForwardPass(keywords)}, by the first Forward pass's own name, not
 *       {@link #lightModeName()}.</li>
 * </ul>
 */
public enum CgRenderPassVariant {

    /** The colour pass. */
    FORWARD("Forward"),

    /**
     * Depth only: a solid material's, or a translucent one's under {@code DepthPrepass}
     * ({@link CgMaterial#hasCompiledDepthPass()}). Keywords are always {@code emptySet()}.
     */
    DEPTH("Depth"),

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
    DISTORTION("Distortion");

    private final String lightModeName;

    CgRenderPassVariant(String lightModeName) {
        this.lightModeName = lightModeName;
    }

    /** The pass's name in the lowered form the engine compiles: {@code "Forward"}, {@code "Depth"}, ... */
    public String lightModeName() {
        return lightModeName;
    }
}
