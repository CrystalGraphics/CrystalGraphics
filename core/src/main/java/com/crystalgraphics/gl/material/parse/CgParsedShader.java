package com.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structural representation of a parsed {@code .shader} file.
 *
 * <p>Produced by {@link CgShaderParser#parse(String)} and consumed by
 * {@link CgMaterialShaderCompiler} to generate per-pass GLSL source strings.</p>
 *
 * <p>Per-pass data (v2f struct, global declarations, vertex body, fragment body,
 * render state, fragment output) lives on each {@link CgParsedPass} inside
 * {@link #passes()}. This record holds only material-level data that applies to
 * the shader as a whole.</p>
 *
 * @param shaderType
 *     Shader type as written after {@code #type} (e.g. {@code "spatial"}).
 *     Only {@code "spatial"} is valid in MVP.
 * @param properties
 *     Ordered list of material properties from the {@code Properties { }} block.
 *     Each entry holds the declaration, parsed default value, and current value.
 *     May be empty but never null.
 * @param engineBuffers
 *     Tokens from {@code #pragma cg_use} declarations — engine-provided shader buffers this shader
 *     reads. Resolved through {@code CgEngineBufferRegistry} and attached at parse time, before
 *     anything can compile. See that class for why this is declared rather than attached in Java.
 * @param featureNames
 *     Ordered feature flag names from {@code #pragma cg_feature} declarations.
 *     Always {@link java.util.Collections#emptyList()} if none declared.
 * @param renderQueue
 *     Numeric render queue priority parsed from the optional {@code Queue = "..."} keyword.
 *     Defaults to {@link CgRenderQueue#GEOMETRY} value (2000) when absent.
 * @param renderType
 *     Material-level {@code "RenderType"} tag value (e.g. {@code "Opaque"},
 *     {@code "Transparent"}). Defaults to {@code "Opaque"} when absent or unknown.
 * @param castShadows
 *     {@code false} when the material-level {@code Tags} block contains
 *     {@code "CastShadows" = "Off"}; {@code true} in all other cases (including
 *     when the tag is absent). Controls whether the shadow auto-generation ladder
 *     runs during {@code recompile()}.
 * @param sceneColorMargin
 *     How far past its own geometry a draw samples {@code cg_SceneColor}, a share of the target's height: the
 *     material-level {@code "SceneColorMargin"} tag, NaN when absent, and required of a shader that reads it. The
 *     frame graph copies the target that much wider round a reader.
 * @param unlit
 *     {@code true} when the material-level {@code Tags} block says {@code "Lighting" = "Unlit"}: its Forward colour is
 *     not multiplied by the host's lightmap, as a material drawn with an object record ({@link #readsObjectRecord})
 *     otherwise is.
 * @param unfogged
 *     {@code true} for {@code "Fog" = "Off"}: its Forward colour is not put behind the host's fog, as such a material's
 *     otherwise is, lit or not.
 * @param linearColor
 *     {@code true} for {@code "ColorSpace" = "Linear"}: its Forward colour is linear already (a post pass, a copy of
 *     the scene), so it is not decoded from sRGB when drawn into the HDR scene ({@code CG_LINEAR_SCENE}).
 * @param unmediated
 *     {@code true} for {@code "Media" = "Off"}: a transparent pass is not dimmed by the smoke and gas in front of it
 *     ({@code cg_Media}), as a transparent world material's otherwise is: the engine's own composites, already dimmed.
 * @param emissiveOpacity
 *     {@code true} for {@code "Output" = "EmissiveOpacity"}: its Forward fragment writes Unreal's emissive and opacity,
 *     not a colour to blend, and the generated {@code main} composes them for the pass's blend as Unreal's base pass
 *     does (an additive pass adds emissive times opacity).
 * @param depthMode
 *     The material-level {@code "Depth"} tag: how a transparent material writes depth, {@link DepthMode#NONE} when
 *     absent.
 * @param passes
 *     Ordered, unmodifiable list of parsed {@link CgParsedPass} records.
 *     Contains at least one entry — the parser throws
 *     {@link CgShaderParseException} when no {@code Pass { }} blocks are found.
 *     Duplicate Depth/ShadowCaster passes are silently removed (first kept).
 * @param buffers
 *     The material-level {@code Buffers { }}: buffers a kernel writes, which every pass reads. Buffer {@code i} is
 *     bound by {@code CgMaterial.buffer(name, buffer)}. Empty when there is none.
 * @param bufferStructs
 *     The structs those buffers' elements name, as declared, emitted ahead of them in every stage.
 */
@Desugar
public record CgParsedShader(String shaderType, List<CgMaterialProperty> properties,
                              List<String> featureNames, List<String> engineBuffers, int renderQueue,
                              String renderType, boolean castShadows, float sceneColorMargin, boolean unlit,
                              boolean unfogged, boolean linearColor, boolean unmediated, boolean emissiveOpacity,
                              DepthMode depthMode,
                              List<CgParsedPass> passes, List<CgBufferDecl> buffers, String bufferStructs) {

    /**
     * How a material writes depth where it is solid, by its {@code "Depth"} tag (Godot's alpha scissor and depth
     * pre-pass, HDRP's transparent depth prepass). Its fragment says where it is solid with {@code cg_Clip(value)},
     * solid at or above {@code _Clip} (0.5 where the shader declares none).
     *
     * <pre>{@code
     * Tags { "RenderType" = "Transparent" "Depth" = "Clip" }      // a stroke: blended, writing depth where solid
     * Tags { "RenderType" = "Transparent" "Depth" = "Prepass" }   // a billow: its solid core's depth drawn first
     * ...
     * float cover = shape(i.uv);
     * cg_Clip(cover);                                            // in fragment(), never in a function it calls
     * fragColor = vec4(colour * cover * fade, cover * fade);
     * }</pre>
     */
    public enum DepthMode {
        /** What its passes' render states say; {@code cg_Clip} discards below the threshold. */
        NONE,
        /**
         * One pass, writing depth: its Forward passes write depth, and {@code cg_Clip} discards below the threshold in
         * every pass. For what is solid wherever it draws much at all: ink, debris.
         */
        CLIP,
        /**
         * A transparent material whose depth is drawn first where {@code cg_Clip} passes, before every transparent draw
         * of the firing; then its Forward passes blend with depth unwritten, and {@code cg_Clip} does nothing. For what
         * is solid in its middle and soft at its edge: smoke. One more draw for each of its draws.
         */
        PREPASS
    }

    /**
     * This parse as a material under {@code state} compiles it: each Forward pass under its blend, and with its clip
     * the {@link DepthMode#CLIP} mode, as if the shader had authored them. Cull and depth write are left to the
     * pipeline ({@link CgMaterialState#onPass}); only their refusals are checked here.
     *
     * <pre>{@code
     * CgParsedShader additive = parsed.withState(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build(), path);
     * }</pre>
     *
     * <ul>
     *   <li>A codeless Emissive pass that took the Forward pass's blend takes the new one; under
     *       {@link CgBlendMode#SUBTRACT} it adds, since light is never subtracted from the glow. One with a blend of its
     *       own keeps it, and a Distortion pass keeps the engine's.</li>
     *   <li>The pass bodies are the same strings, so a codeless Emissive pass is still recognised as one.</li>
     * </ul>
     *
     * @throws CgShaderParseException for a state the shader refuses: a depth write where its {@code "Depth"} tag owns
     *                                depth, or a clip on a {@code "Prepass"} shader
     */
    public CgParsedShader withState(CgMaterialState state, String resourcePath) {
        if (state.depthWrite() != null && depthMode != DepthMode.NONE) {
            throw new CgShaderParseException("[" + resourcePath + "] Its \"Depth\" = \"" + (depthMode == DepthMode.CLIP
                    ? "Clip" : "Prepass") + "\" owns its depth write: a material state may not set depthWrite");
        }
        if (state.clip() && depthMode == DepthMode.PREPASS) {
            throw new CgShaderParseException("[" + resourcePath + "] A \"Depth\" = \"Prepass\" shader draws its depth "
                    + "first; a material state may not clip it");
        }
        if (!state.changesProgram()) return this;
        CgParsedPass firstForward = getPassByLightMode(CgParsedPass.LIGHT_MODE_FORWARD);
        CgRenderState authored = firstForward == null ? null : firstForward.renderState();
        CgBlendMode mode = state.blend();
        List<CgParsedPass> out = new ArrayList<>(passes.size());
        for (CgParsedPass pass : passes) {
            CgRenderState rs = pass.renderState();
            if (CgParsedPass.LIGHT_MODE_FORWARD.equals(pass.lightMode())) {
                rs = forwardUnder(rs, state);
            } else if (CgParsedPass.LIGHT_MODE_EMISSIVE.equals(pass.lightMode()) && authored != null
                    && pass.fragmentBody() == firstForward.fragmentBody()) {
                // A codeless pass copied the Forward pass's state where it authored none: what it copied follows.
                CgRenderState forward = forwardUnder(authored, state);
                if (mode != null && Objects.equals(rs.getBlend(), authored.getBlend())) {
                    rs = rs.withBlend((mode == CgBlendMode.SUBTRACT ? CgBlendMode.ADDITIVE : mode).state());
                }
                if (Objects.equals(rs.getDepth(), authored.getDepth())) rs = rs.withDepth(forward.getDepth());
            }
            out.add(rs == pass.renderState() ? pass : new CgParsedPass(pass.lightMode(), pass.name(), rs,
                    pass.v2fStructBody(), pass.globalDecls(), pass.vertexBody(), pass.fragmentBody(), pass.fragOutput()));
        }
        return new CgParsedShader(shaderType, properties, featureNames, engineBuffers, renderQueue, renderType,
                castShadows, sceneColorMargin, unlit, unfogged, linearColor, unmediated, emissiveOpacity,
                state.clip() ? DepthMode.CLIP : depthMode, Collections.unmodifiableList(out), buffers, bufferStructs);
    }

    /** A Forward pass's {@code rs} under {@code state}'s blend, and with its clip writing depth as the parser makes it. */
    private static CgRenderState forwardUnder(CgRenderState rs, CgMaterialState state) {
        if (state.blend() != null) rs = rs.withBlend(state.blend().state());
        if (state.clip()) {
            CgDepthState depth = rs.getDepth() != null ? rs.getDepth() : CgDepthState.TEST_WRITE;
            rs = rs.withDepth(new CgDepthState(depth.test(), true, depth.compareFunc()));
        }
        return rs;
    }

    /** The declared buffer of that name, or null. */
    public CgBufferDecl buffer(String name) {
        for (int i = 0; i < buffers.size(); i++) if (buffers.get(i).name().equals(name)) return buffers.get(i);
        return null;
    }

    /** Whether its draws carry the object record ({@code CG_OBJECT_DATA}): every material but the quad and curve renderers'. */
    public boolean readsObjectRecord() {
        return !engineBuffers.contains("quad") && !engineBuffers.contains("curve");
    }

    /** Whether the compiler multiplies its Forward colour by the host's lightmap. */
    public boolean lit() {
        return !unlit && readsObjectRecord();
    }

    /** Whether the compiler puts its Forward colour behind the host's fog. */
    public boolean fogged() {
        return !unfogged && readsObjectRecord();
    }

    /** Whether the compiler dims its passes by the smoke and gas in front of them: a transparent world material's, below the overlays. */
    public boolean mediated() {
        return !unmediated && readsObjectRecord() && renderQueue >= CgRenderQueue.TRANSPARENT_THRESHOLD
                && renderQueue < CgRenderQueue.OVERLAY_THRESHOLD;
    }

    /**
     * Returns the first pass whose {@code name()} equals {@code name},
     * or {@code null} if no such pass exists.
     *
     * @param name the authored or auto-assigned pass name to look up
     * @return matching {@link CgParsedPass}, or {@code null}
     */
    public CgParsedPass getPassByName(String name) {
        // Indexed: asked per bind, and the iterator of an immutable list was a measured allocation there.
        for (int i = 0; i < passes.size(); i++) {
            if (passes.get(i).name().equals(name)) return passes.get(i);
        }
        return null;
    }

    /**
     * Returns the first pass whose {@code lightMode()} equals {@code lightMode},
     * or {@code null} if no such pass exists.
     *
     * <p>Use the {@code LIGHT_MODE_*} string constants from {@link CgParsedPass}
     * (package-private) or {@link CgRenderPassVariant#lightModeName()}
     * for the lookup value.</p>
     *
     * @param lightMode the LightMode tag value to look up (e.g. {@code "Forward"})
     * @return matching {@link CgParsedPass}, or {@code null}
     */
    public CgParsedPass getPassByLightMode(String lightMode) {
        for (int i = 0; i < passes.size(); i++) {
            if (passes.get(i).lightMode().equals(lightMode)) return passes.get(i);
        }
        return null;
    }
}
