package com.crystalgraphics.api.material;

/**
 * Orchestrator-facing bridge enum that maps a high-level render variant concept to the
 * {@code "LightMode"} tag value written inside a {@code Pass { Tags { } }} block in a
 * CrystalShader {@code .shader} file.
 *
 * <p>It lets a renderer call {@code material.bindForPass(CgRenderPassVariant.DEPTH)} without knowing the
 * shader's internal pass structure. There is no shadow-caster variant: the world's shadows are ray-traced from
 * each pixel (Unreal's MegaLights, {@code CgWorldLights}), so nothing is drawn from a light's side.</p>
 *
 * <h3>Keyword policy</h3>
 * <ul>
 *   <li>{@link #FORWARD} — passes the material's full enabled keyword set to the Forward pass.</li>
 *   <li>{@link #DEPTH} — always passes {@code Collections.emptySet()} for keywords.</li>
 * </ul>
 *
 * <h3>FORWARD routing</h3>
 * <p>Forward dispatch always routes via
 * {@link CgMaterial#bindForPass(CgRenderPassVariant)} →
 * {@code cgMaterialShader.getOrCompileForwardPass(keywords)}, which resolves the
 * authored {@code "Name"} tag of the first Forward pass internally. The
 * {@link #lightModeName()} value ({@code "Forward"}) is <em>not</em> used as the
 * {@code ProgramKey} passName for FORWARD — doing so would break shaders that name
 * their pass {@code "BaseColor"} or similar.</p>
 */
public enum CgRenderPassVariant {

    /**
     * Standard forward-lit pass.
     * <p>Routing: resolved through {@code getOrCompileForwardPass(keywords)} using the
     * authored {@code "Name"} tag of the first Forward pass, not the LightMode string.</p>
     */
    FORWARD("Forward"),

    /**
     * Depth-only pre-pass (v2 — not executed in MVP).
     * <p>{@code bindForPass(DEPTH)} silently no-ops if no Depth pass was compiled.</p>
     * <p>Keywords are always {@code emptySet()}.</p>
     */
    DEPTH("Depth"),

    /**
     * The light a surface emits, drawn into the world's bloom target and spread over the scene by it. Never
     * auto-generated: a material blooms only where it authors a {@code Pass { Tags { "LightMode" = "Emissive" } }}.
     * <p>Takes the material's keywords, like {@link #FORWARD}. Unlit and faded by fog; a fragment behind the
     * scene's depth is discarded before its fragment function runs.</p>
     */
    EMISSIVE("Emissive"),

    /**
     * How a surface bends what is behind it: an offset in UV units and a chromatic split, added into the world's
     * distortion target and applied to the scene once, after the transparent pass. Never auto-generated.
     * <p>Takes the material's keywords, like {@link #FORWARD}. Unlit and unfogged; a fragment behind the scene's depth
     * is discarded before its fragment function runs.</p>
     */
    DISTORTION("Distortion");

    private final String lightModeName;

    CgRenderPassVariant(String lightModeName) {
        this.lightModeName = lightModeName;
    }

    /**
     * Returns the {@code "LightMode"} tag value this variant maps to, as written in
     * {@code Pass { Tags { "LightMode" = "..." } }}.
     *
     * <p>Examples: {@code "Forward"}, {@code "Depth"}, {@code "Emissive"}.</p>
     *
     * @return the LightMode tag string; never {@code null}
     */
    public String lightModeName() {
        return lightModeName;
    }
}
