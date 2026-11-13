package com.crystalgraphics.material.shader;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.shader.syntax.CgShaderError;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.github.bsideup.jabel.Desugar;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@code .shader} read: what the material is and every pass it draws in, decided once. What {@link CgGlslWriter}
 * writes programs from, and what the renderer asks a material about.
 *
 * <pre>{@code
 * CgShaderSpec spec = CgShaderSpec.read(CgIO.loadSource(path), path);
 * spec.domain();                                    // SURFACE, POST, UI, GLOBAL
 * spec.features();                                  // its Keywords { }, its factory's first
 * CgShaderPass forward = spec.pass(CgRenderPassVariant.FORWARD);
 * boolean glows = spec.pass(CgRenderPassVariant.EMISSIVE) != null;
 * }</pre>
 *
 * <p>Under a material state, as a material compiles it (an instance overriding its blend mode):</p>
 *
 * <pre>{@code
 * CgShaderSpec additive = spec.withState(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build(), path);
 * }</pre>
 *
 * <ul>
 *   <li>Immutable but for its properties, which are a material's own: each read makes new ones.</li>
 *   <li>A file it cannot read fails with {@link CgShaderError}, placed on the line it names.</li>
 * </ul>
 */
public final class CgShaderSpec {

    /** How its colour is lit: by the world's light from every side, by its {@code normal}, or not at all. */
    public enum Lighting { LIT, UNLIT, SURFACE }

    /** What its colour is written in: sRGB (decoded into a linear scene), linear light, or the target's own encoding. */
    public enum ColorSpace { SRGB, LINEAR, TARGET }

    /**
     * How a material writes depth where it is solid (Godot's alpha scissor and depth pre-pass, HDRP's transparent
     * depth prepass): {@code cg_Clip(value)} says where, at or above its {@link #maskClip()}.
     */
    public enum DepthMode {
        /** What its passes' render states say; {@code cg_Clip} discards below the threshold. */
        NONE,
        /** Its Forward passes write depth, and {@code cg_Clip} discards below the threshold in every pass: ink, debris. */
        CLIP,
        /** Its solid core's depth drawn first, then its Forward passes blend with depth unwritten: smoke. */
        PREPASS
    }

    /** The geometry it needs on a tube slot, from its factory: each chunk on a sphere round it, or capped. */
    public enum Tube { VOLUME, CLOSED }

    /** Whether, and how, its Emissive pass folds into its Forward draw. */
    public enum EmissionMerge {
        /** It stays a draw of its own. */
        NONE,
        /** Its blend is the Forward pass's: the glow is written with the colour's alpha. */
        SAME_BLEND,
        /** It adds (ONE ONE) under a premultiplied Forward pass: written with an alpha of 0, the shared blend adds. */
        ADDED
    }

    /**
     * A post material's step drawing into a texture of its own: {@code scale} of the post target on each side, in
     * {@code format}, bound to the {@code sampler2D} property {@code property} for every step after it.
     */
    @Desugar
    public record PostTarget(String pass, String property, float scale, CgFormat format) {
    }

    /** The engine buffer whose programs read their numbers from a draw's property row ({@code PerDrawValues On}). */
    public static final String ROWS = "rows";

    /**
     * No depth test: passing always and writing nothing, Unreal's {@code CF_Always} for a {@code bDisableDepthTest}
     * translucent draw. Tested at {@code ALWAYS} rather than off, so the writer knows its glow has no scene test to make.
     */
    private static final CgDepthState UNTESTED = new CgDepthState(true, false, GLC.GL_ALWAYS);

    final String path;
    final CgMaterialDomain domain;
    final String vertexType;
    final List<CgMaterialProperty> properties;
    final List<String> features, implicitFeatures;
    final CgShaderPermutations permutations;
    final List<String> engineBuffers;
    final int queue;
    final String renderType;
    final float sceneColorMargin;
    final Lighting lighting;
    final ColorSpace colorSpace;
    final boolean fog, media, handedLights, handedSun, handedScene, writesCustomData, separateTranslucency;
    final DepthMode depthMode;
    @Nullable final String maskClip, postLocation;
    @Nullable final Tube tube;
    final List<CgBufferDecl> buffers;
    final String bufferStructs;
    final List<PostTarget> postTargets;
    final List<CgShaderPass> passes;
    private final EmissionMerge emissionMerge;
    private final boolean sceneFolds;
    private Set<String> identifiers;

    CgShaderSpec(SpecBuilder b, List<CgShaderPass> passes, DepthMode depthMode) {
        this.path = b.path;
        this.domain = b.domain;
        this.vertexType = b.vertexType;
        this.properties = b.properties;
        this.features = b.featuresView();
        this.implicitFeatures = b.implicitFeatures;
        this.permutations = b.permutations;
        this.engineBuffers = b.engineBuffers;
        this.queue = b.queue;
        this.renderType = b.renderType;
        this.sceneColorMargin = b.sceneColorMargin;
        this.lighting = b.lighting;
        this.colorSpace = b.colorSpace;
        this.fog = b.fog;
        this.media = b.media;
        this.handedLights = b.handedLights;
        this.handedSun = b.handedSun;
        this.handedScene = b.handedScene;
        this.writesCustomData = b.writesCustomData;
        this.separateTranslucency = b.separateTranslucency;
        this.maskClip = b.maskClip;
        this.postLocation = b.postLocation;
        this.tube = b.tube;
        this.buffers = b.buffers;
        this.bufferStructs = b.bufferStructs;
        this.postTargets = b.postTargets;
        this.depthMode = depthMode;
        this.passes = Collections.unmodifiableList(new ArrayList<>(passes));
        this.emissionMerge = merge();
        this.sceneFolds = emissionMerge != EmissionMerge.NONE && folds();
    }

    private CgShaderSpec(CgShaderSpec of, List<CgShaderPass> passes, DepthMode depthMode) {
        this.path = of.path;
        this.domain = of.domain;
        this.vertexType = of.vertexType;
        this.properties = of.properties;
        this.features = of.features;
        this.implicitFeatures = of.implicitFeatures;
        this.permutations = of.permutations;
        this.engineBuffers = of.engineBuffers;
        this.queue = of.queue;
        this.renderType = of.renderType;
        this.sceneColorMargin = of.sceneColorMargin;
        this.lighting = of.lighting;
        this.colorSpace = of.colorSpace;
        this.fog = of.fog;
        this.media = of.media;
        this.handedLights = of.handedLights;
        this.handedSun = of.handedSun;
        this.handedScene = of.handedScene;
        this.writesCustomData = of.writesCustomData;
        this.separateTranslucency = of.separateTranslucency;
        this.maskClip = of.maskClip;
        this.postLocation = of.postLocation;
        this.tube = of.tube;
        this.buffers = of.buffers;
        this.bufferStructs = of.bufferStructs;
        this.postTargets = of.postTargets;
        this.identifiers = of.identifiers;
        this.depthMode = depthMode;
        this.passes = Collections.unmodifiableList(new ArrayList<>(passes));
        this.emissionMerge = merge();
        this.sceneFolds = emissionMerge != EmissionMerge.NONE && folds();
    }

    /** Reads {@code source}, a {@code .shader}; {@code path} names it in errors. */
    public static CgShaderSpec read(String source, String path) {
        return SpecReader.read(source, path);
    }

    // ── What it is ────────────────────────────────────────────────────────────────

    public String path() {
        return path;
    }

    public CgMaterialDomain domain() {
        return domain;
    }

    /** The {@code CgVertexFormat} key its programs read: {@code spatial} for a mesh, {@code none} where nothing is fetched. */
    public String vertexType() {
        return vertexType;
    }

    /** Its properties: its template's first (a factory's, a primitive's, a post's steps), then its own, then the engine's. */
    public List<CgMaterialProperty> properties() {
        return properties;
    }

    /** Every keyword it compiles under: its template's, its own {@code Keywords { }}, then {@link #implicitFeatures()}. */
    public List<String> features() {
        return features;
    }

    /**
     * The keywords in {@link #features()} it was given undeclared: a particle program takes each vertex factory's (the
     * strips, and the sprites' where it declares no usage keyword), as Unreal compiles a material once per factory.
     */
    public List<String> implicitFeatures() {
        return implicitFeatures;
    }

    public CgShaderPermutations permutations() {
        return permutations;
    }

    /** The engine buffers its programs read, requirements included ({@code CgEngineBufferRegistry} tokens). */
    public List<String> engineBuffers() {
        return engineBuffers;
    }

    /** Its render queue: {@code CgRenderQueue}'s numbers. */
    public int queue() {
        return queue;
    }

    /** {@code Opaque}, {@code Transparent} or {@code Background}. */
    public String renderType() {
        return renderType;
    }

    /** How far past its own geometry a draw samples the scene's colour, a share of the target's height; NaN when unset. */
    public float sceneColorMargin() {
        return sceneColorMargin;
    }

    public Lighting lighting() {
        return lighting;
    }

    public ColorSpace colorSpace() {
        return colorSpace;
    }

    /** Whether its colour is put behind the host's fog where its draws carry an object record ({@link #fogged()}). */
    public boolean fog() {
        return fog;
    }

    /** Whether the smoke and gas in front dim it where it is a transparent world material ({@link #mediated()}). */
    public boolean media() {
        return media;
    }

    /** Whether it names the world's lights ({@code cg_NearestLights}, {@code CG_DRAW_POINT_LIGHT_*}): handed the grid. */
    public boolean handedLights() {
        return handedLights;
    }

    /** Whether it names the sun's shadow ({@code CG_DIRECTIONAL_SHADOW}): handed the map. */
    public boolean handedSun() {
        return handedSun;
    }

    /** Whether it reads a scene texture a post material samples (custom stencil, world normal, custom data). */
    public boolean handedScene() {
        return handedScene;
    }

    /** Whether it writes Unreal's custom data or shading model: its opaque draws are drawn again into them. */
    public boolean writesCustomData() {
        return writesCustomData;
    }

    /** {@code TranslucencyPass Separate}: drawn after every other translucent draw. */
    public boolean separateTranslucency() {
        return separateTranslucency;
    }

    public DepthMode depthMode() {
        return depthMode;
    }

    /** What {@code opacityMask} is cut at: a number or a float property's name; null for 0.5. */
    @Nullable
    public String maskClip() {
        return maskClip;
    }

    /** A {@code post} material's {@code Location}, a {@code CgPostPoint} name; null where it names none. */
    @Nullable
    public String postLocation() {
        return postLocation;
    }

    @Nullable
    public Tube tube() {
        return tube;
    }

    /** Its {@code Buffers { }}: what a kernel wrote, read by every pass; bound by {@code CgMaterial.buffer(name, b)}. */
    public List<CgBufferDecl> buffers() {
        return buffers;
    }

    /** The structs its buffers' elements name, as written: emitted ahead of them in every stage. */
    public String bufferStructs() {
        return bufferStructs;
    }

    /** The declared buffer of that name, or null. */
    @Nullable
    public CgBufferDecl buffer(String name) {
        for (int i = 0; i < buffers.size(); i++) if (buffers.get(i).name().equals(name)) return buffers.get(i);
        return null;
    }

    /** A post material's steps that draw into a texture of their own, in order; empty for any other. */
    public List<PostTarget> postTargets() {
        return postTargets;
    }

    // ── Its passes ────────────────────────────────────────────────────────────────

    public List<CgShaderPass> passes() {
        return passes;
    }

    /** The pass named {@code name}, or null. */
    @Nullable
    public CgShaderPass pass(String name) {
        // Indexed: asked per bind, and an immutable list's iterator was a measured allocation there.
        for (int i = 0; i < passes.size(); i++) if (passes.get(i).name().equals(name)) return passes.get(i);
        return null;
    }

    /** The first pass of {@code variant}, or null. */
    @Nullable
    public CgShaderPass pass(CgRenderPassVariant variant) {
        for (int i = 0; i < passes.size(); i++) if (passes.get(i).variant() == variant) return passes.get(i);
        return null;
    }

    /** How its Emissive pass folds into its first Forward pass. */
    public EmissionMerge emissionMerge() {
        return emissionMerge;
    }

    /**
     * Whether its Emissive pass folds into its Forward draw in the HDR scene ({@link CgEngineKeywords#SCENE_FOLD}): it
     * merges, and the Forward blend adds its source scaled by ONE or SRC_ALPHA.
     */
    public boolean sceneFolds() {
        return sceneFolds;
    }

    /**
     * Whether its code names {@code identifier} as a whole word, in any pass: what a renderer asks before writing a
     * value only some shaders read. A name used only inside an included file is not seen.
     */
    public boolean names(String identifier) {
        Set<String> ids = identifiers;
        if (ids == null) {
            ids = new LinkedHashSet<>();
            for (CgShaderPass p : passes) ids.addAll(p.identifiers());
            identifiers = ids;
        }
        return ids.contains(identifier);
    }

    // ── How the engine draws it ───────────────────────────────────────────────────

    /** Whether its draws carry the object record ({@code CG_OBJECT_DATA}): every material but the quad and curve renderers'. */
    public boolean readsObjectRecord() {
        return !engineBuffers.contains("quad") && !engineBuffers.contains("curve");
    }

    /** Whether its materials' values live in its arena, so draws of its materials join in one multi-draw. */
    public boolean valuesInArena() {
        return "spatial".equals(vertexType) && readsObjectRecord();
    }

    /** Whether the writer multiplies its Forward colour by the host's lightmap. */
    public boolean lit() {
        return lighting != Lighting.UNLIT && readsObjectRecord();
    }

    public boolean unlit() {
        return lighting == Lighting.UNLIT;
    }

    public boolean surfaceLit() {
        return lighting == Lighting.SURFACE;
    }

    /** Whether the writer puts its Forward colour behind the host's fog. */
    public boolean fogged() {
        return fog && readsObjectRecord();
    }

    /** Whether its colour is not authored in sRGB, so it is not decoded into the HDR scene. */
    public boolean linearColor() {
        return colorSpace != ColorSpace.SRGB;
    }

    /** Whether its colour is linear light, encoded where it draws onto the host's own target. */
    public boolean linearLight() {
        return colorSpace == ColorSpace.LINEAR;
    }

    /** Whether the writer dims its passes by the smoke in front: a transparent world material's, below the overlays. */
    public boolean mediated() {
        return media && readsObjectRecord() && queue >= CgRenderQueue.TRANSPARENT_THRESHOLD
                && queue < CgRenderQueue.OVERLAY_THRESHOLD;
    }

    // ── Under a material state ────────────────────────────────────────────────────

    /**
     * This spec as a material under {@code state} compiles it: each Forward pass under its blend, and with its clip
     * the {@link DepthMode#CLIP} mode, as if the file had said so. Cull and depth write are the pipeline's
     * ({@link CgMaterialState#onPass}); only their refusals are checked here.
     *
     * <ul>
     *   <li>A codeless Emissive pass that took the Forward pass's blend takes the new one; under
     *       {@link CgBlendMode#SUBTRACT} it adds, since light is never subtracted from the glow.</li>
     *   <li>A blend without {@code clip()} drops a Clip depth mode, as an instance overriding its masked parent's blend
     *       mode takes the new one whole: no clip, depth written only under {@link CgBlendMode#OPAQUE}.</li>
     * </ul>
     *
     * @throws CgShaderError for a state it refuses: a depth write where its depth mode owns depth (unless its blend
     *                       dropped a Clip mode), a clip on a Prepass shader, its depth test off where it may not be
     */
    public CgShaderSpec withState(CgMaterialState state, String resourcePath) {
        boolean unclip = state.blend() != null && !state.clip() && depthMode == DepthMode.CLIP;
        if (state.depthWrite() != null && depthMode != DepthMode.NONE && !unclip) {
            throw CgShaderError.of(resourcePath, "its depth mode " + depthMode + " owns its depth write: a material state"
                    + " may not set depthWrite");
        }
        if (state.clip() && depthMode == DepthMode.PREPASS) {
            throw CgShaderError.of(resourcePath, "a DepthPrepass shader draws its depth first; a material state may not"
                    + " clip it");
        }
        if (!state.depthTested()) {
            if (depthMode == DepthMode.PREPASS) {
                throw CgShaderError.of(resourcePath, "a DepthPrepass shader draws its depth first; a material state may"
                        + " not turn its depth test off");
            }
            if (state.blend() == null && queue < CgRenderQueue.TRANSPARENT_THRESHOLD) {
                throw CgShaderError.of(resourcePath, "an opaque-queue shader is depth tested: only a translucent blend"
                        + " may turn the depth test off");
            }
        }
        if (!state.changesProgram()) return this;
        CgShaderPass firstForward = pass(CgRenderPassVariant.FORWARD);
        CgRenderState authored = firstForward == null ? null : firstForward.state();
        CgBlendMode mode = state.blend();
        List<CgShaderPass> out = new ArrayList<>(passes.size());
        for (CgShaderPass pass : passes) {
            CgRenderState rs = pass.state();
            if (pass.variant() == CgRenderPassVariant.FORWARD) {
                rs = forwardUnder(rs, state, unclip);
            } else if (!state.depthTested() && (pass.variant() == CgRenderPassVariant.EMISSIVE
                    || pass.variant() == CgRenderPassVariant.DISTORTION)) {
                // Unreal's bDisableDepthTest is the material's: its glow and its bend are not hidden by the scene either
                rs = rs.withDepth(UNTESTED);
            } else if (pass.copiesForward() && authored != null) {
                // A codeless pass copied the Forward pass's state where it authored none: what it copied follows
                CgRenderState forward = forwardUnder(authored, state, unclip);
                if (mode != null && Objects.equals(rs.getBlend(), authored.getBlend())) {
                    rs = rs.withBlend((mode == CgBlendMode.SUBTRACT ? CgBlendMode.ADDITIVE : mode).state());
                }
                if (Objects.equals(rs.getDepth(), authored.getDepth())) rs = rs.withDepth(forward.getDepth());
            }
            out.add(pass.withState(rs));
        }
        return new CgShaderSpec(this, out, state.clip() ? DepthMode.CLIP : unclip ? DepthMode.NONE : depthMode);
    }

    /**
     * A Forward pass's {@code rs} under {@code state}'s blend, and with its clip writing depth as a Clip mode does;
     * {@code unclip}, the clip dropped, writing depth only under an opaque blend, as Unreal's blend modes do.
     */
    private static CgRenderState forwardUnder(CgRenderState rs, CgMaterialState state, boolean unclip) {
        if (state.blend() != null) rs = rs.withBlend(state.blend().state());
        if (!state.depthTested()) return rs.withDepth(UNTESTED);
        if (state.clip() || unclip) {
            CgDepthState depth = rs.getDepth() != null ? rs.getDepth() : CgDepthState.TEST_WRITE;
            rs = rs.withDepth(new CgDepthState(depth.test(), state.clip() || state.blend() == CgBlendMode.OPAQUE,
                    depth.compareFunc()));
        }
        return rs;
    }

    // ── The glow's merge ──────────────────────────────────────────────────────────

    /**
     * Only a codeless Emissive pass merges, never one the shared code tells apart by {@code CG_EMISSIVE_PASS}; and only
     * where one blend serves both outputs, since below GL 4.0 every draw buffer shares the draw's blend.
     */
    private EmissionMerge merge() {
        CgShaderPass emissive = pass(CgRenderPassVariant.EMISSIVE), forward = pass(CgRenderPassVariant.FORWARD);
        if (emissive == null || forward == null || !emissive.copiesForward() || forward.namesEmissivePass()) {
            return EmissionMerge.NONE;
        }
        CgRenderState f = forward.state(), e = emissive.state();
        // A cull the Emissive pass leaves unset is whatever its pass leaves; the merged draw takes the Forward pass's
        boolean sameCull = e.getCull() == null || e.getCull().equals(f.getCull());
        if (f.writesNothing() || !sameCull || !sameTest(f.getDepth(), e.getDepth())) return EmissionMerge.NONE;
        CgBlendState fb = f.getBlend(), eb = e.getBlend();
        if (fb == null || !fb.enabled()) return EmissionMerge.NONE;   // an opaque Forward pass would overwrite the glow
        if (fb.equals(eb)) return EmissionMerge.SAME_BLEND;
        boolean premultiplied = fb.srcRgb() == GLC.GL_ONE && fb.dstRgb() == GLC.GL_ONE_MINUS_SRC_ALPHA
                && fb.blendEquationRgb() == GLC.GL_FUNC_ADD;
        boolean adds = eb != null && eb.enabled() && eb.srcRgb() == GLC.GL_ONE && eb.dstRgb() == GLC.GL_ONE
                && eb.blendEquationRgb() == GLC.GL_FUNC_ADD;
        return premultiplied && adds ? EmissionMerge.ADDED : EmissionMerge.NONE;
    }

    /** Whether the merged glow, added to the colour, lands as the target-plus-emission picture does. */
    private boolean folds() {
        CgBlendState blend = pass(CgRenderPassVariant.FORWARD).state().getBlend();
        return blend.blendEquationRgb() == GLC.GL_FUNC_ADD && (blend.srcRgb() == GLC.GL_ONE || blend.srcRgb() == GLC.GL_SRC_ALPHA);
    }

    /**
     * Whether the Forward pass's hardware test hides what the Emissive pass's scene-depth discard hides: both test, or
     * neither. An Emissive pass discards unless it tests {@code ALWAYS}; a Forward pass with no depth state tests.
     */
    private static boolean sameTest(@Nullable CgDepthState forward, @Nullable CgDepthState emissive) {
        boolean forwardTests = forward == null || forward.test() && forward.compareFunc() != GLC.GL_ALWAYS;
        boolean emissiveTests = emissive == null || !(emissive.test() && emissive.compareFunc() == GLC.GL_ALWAYS);
        return forwardTests == emissiveTests;
    }

    @Override
    public String toString() {
        return "CgShaderSpec[" + path + ", " + domain + ", " + passes.size() + " passes]";
    }
}
