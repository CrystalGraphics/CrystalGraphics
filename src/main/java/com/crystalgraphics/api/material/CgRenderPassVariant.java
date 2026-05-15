package com.crystalgraphics.api.material;

/**
 * Orchestrator-facing bridge enum that maps a high-level render variant concept to the
 * {@code "LightMode"} tag value written inside a {@code Pass { Tags { } }} block in a
 * CrystalShader {@code .shader} file.
 *
 * <p>Unity uses {@code ShaderTagId("ShadowCaster")} for the same purpose; Godot uses
 * {@code PASS_MODE_SHADOW}. This enum serves the identical role in the CrystalShader
 * pipeline: it lets the render orchestrator call
 * {@code material.bindForPass(CgRenderPassVariant.SHADOW)} without knowing the shader's
 * internal pass structure.</p>
 *
 * <h3>Keyword policy</h3>
 * <ul>
 *   <li>{@link #FORWARD} — passes the material's full enabled keyword set to the Forward pass.</li>
 *   <li>{@link #SHADOW} — always passes {@code Collections.emptySet()} for keywords
 *       (shadow geometry never varies on feature keywords).</li>
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
     * Shadow caster pass. Routes to the compiled {@code "ShadowCaster"} program,
     * which is either authored explicitly via a
     * {@code Pass { Tags { "LightMode" = "ShadowCaster" } }} block, or auto-generated
     * from the Forward vertex shader body when {@code castShadows == true} and no
     * explicit ShadowCaster pass exists.
     *
     * <p>Keywords are always {@code emptySet()} for shadow variants — shadow geometry
     * never varies on material feature keywords.</p>
     */
    SHADOW("ShadowCaster"),

    /**
     * Depth-only pre-pass (v2 — not executed in MVP).
     * <p>{@code bindForPass(DEPTH)} silently no-ops if no Depth pass was compiled.</p>
     * <p>Keywords are always {@code emptySet()}.</p>
     */
    DEPTH("Depth");

    private final String lightModeName;

    CgRenderPassVariant(String lightModeName) {
        this.lightModeName = lightModeName;
    }

    /**
     * Returns the {@code "LightMode"} tag value this variant maps to, as written in
     * {@code Pass { Tags { "LightMode" = "..." } }}.
     *
     * <p>Examples: {@code "Forward"}, {@code "ShadowCaster"}, {@code "Depth"}.</p>
     *
     * @return the LightMode tag string; never {@code null}
     */
    public String lightModeName() {
        return lightModeName;
    }
}
