package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedPass;
import com.crystalgraphics.gl.material.parse.CgParsedShader;
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that ends a batch and is not data: a shader's pass and keyword variant, the render state that pass
 * declares, and the kind of instance it reads. A key, interned to an {@code int} so a batch compares integers, and
 * made with no GL at all: the program behind it is resolved on the render thread when a batch draws with it.
 *
 * <pre>{@code
 * CgPipeline p = material.pipeline(CgInstanceKind.QUAD);       // on any thread; compiles nothing
 *
 * // at execution, on the render thread:
 * if (p.bind()) {                                               // compiles the variant on first use
 *     p.instanceBase(first);
 *     CgGL.glDrawElementsInstanced(...);
 * }
 * }</pre>
 *
 * <p>A caller that must not wait on the driver asks {@link #prepare()} first, frame by frame, and draws once it
 * answers true.</p>
 *
 * <ul>
 *   <li>The render state's unset domains are the pass's: an executor applies its pass's state, then this one's.</li>
 *   <li>A shader reloaded in place keeps its pipelines: {@link #program()} follows the shader's revision.</li>
 *   <li>Lives for the session. Interning is thread-safe; {@link #program()}, {@link #bind()},
 *       {@link #prepare()}, {@link #instanceBase} and {@link #vertexBase} are render thread only.</li>
 * </ul>
 */
public final class CgPipeline {

    private static final ConcurrentHashMap<Key, CgPipeline> INTERNED = new ConcurrentHashMap<>();
    private static final Object REGISTERING = new Object();
    private static volatile CgPipeline[] byId = new CgPipeline[64];
    private static int count;

    /** Not yet looked up for the program as it is now compiled. */
    private static final int UNRESOLVED = -2;

    private final int id;
    private final CgMaterialShader shader;
    private final CgRenderPassVariant pass;
    private final Set<String> keywords;
    private final CgRenderState state;
    private final CgInstanceKind kind;
    private final boolean multiDraw;
    private final boolean overdraw;
    private final boolean emission;
    private final boolean cover;
    private final boolean fold;
    /** What the program is compiled with: the pass's keywords, and the multi-draw's. */
    private final Set<String> compiled;
    @Nullable
    private CgPipeline multi;
    @Nullable
    private CgPipeline overdrawn;
    @Nullable
    private CgPipeline emitting;
    @Nullable
    private CgPipeline occluding;
    @Nullable
    private CgPipeline folding;
    /** {@link #slotWrites()}, for the shader revision {@link #slotsRevision}. */
    private int slots, slotsRevision = -1;

    @Nullable
    private CgShader program;
    private int programRevision = -1;
    private int instanceBaseLocation = UNRESOLVED, vertexBaseLocation = UNRESOLVED;

    /** {@link #withState} answers, by state identity. */
    private CgPipeline[] derived = new CgPipeline[2];
    private CgRenderState[] derivedStates = new CgRenderState[2];
    private int derivedCount;

    private CgPipeline(int id, Key key) {
        this.id = id;
        this.shader = key.shader;
        this.pass = key.pass;
        this.keywords = key.keywords;
        this.state = key.state;
        this.kind = key.kind;
        this.multiDraw = (key.flags & MULTI) != 0;
        this.overdraw = (key.flags & OVERDRAW) != 0;
        this.emission = (key.flags & EMISSION) != 0;
        this.cover = (key.flags & COVER) != 0;
        this.fold = (key.flags & FOLD) != 0;
        Set<String> passKeywords = takesKeywords(pass) ? keywords : Collections.emptySet();
        if (multiDraw || overdraw || emission || cover || fold) {
            Set<String> more = new TreeSet<>(passKeywords);
            if (multiDraw) more.add(CgMaterialShaderCompiler.MULTI_DRAW);
            if (overdraw) more.add(CgMaterialShaderCompiler.DEBUG_OVERDRAW);
            if (emission) more.add(CgMaterialShaderCompiler.EMISSION_TARGET);
            if (cover) more.add(CgMaterialShaderCompiler.EMISSION_COVER);
            if (fold) more.add(CgMaterialShaderCompiler.SCENE_FOLD);
            passKeywords = Collections.unmodifiableSet(more);
        }
        this.compiled = passKeywords;
    }

    /**
     * The pipeline for this variant, state and kind, made on first use.
     *
     * @param keywords the enabled {@code #pragma cg_feature} names; ignored for any pass but
     *                 {@link CgRenderPassVariant#FORWARD}, {@link CgRenderPassVariant#EMISSIVE} and
     *                 {@link CgRenderPassVariant#DISTORTION}
     */
    public static CgPipeline of(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords,
                                CgRenderState state, CgInstanceKind kind) {
        Set<String> variant = takesKeywords(pass) && !keywords.isEmpty()
                ? Collections.unmodifiableSet(new TreeSet<>(keywords))
                : Collections.emptySet();
        return INTERNED.computeIfAbsent(new Key(shader, pass, variant, state, kind, 0), CgPipeline::register);
    }

    private static final int MULTI = 1, OVERDRAW = 2, EMISSION = 4, COVER = 8, FOLD = 16;

    private int flags() {
        return (multiDraw ? MULTI : 0) | (overdraw ? OVERDRAW : 0) | (emission ? EMISSION : 0) | (cover ? COVER : 0)
                | (fold ? FOLD : 0);
    }

    /** The passes a material's keywords reach: the ones a material authors for the frame it draws into. */
    private static boolean takesKeywords(CgRenderPassVariant pass) {
        return pass == CgRenderPassVariant.FORWARD || pass == CgRenderPassVariant.EMISSIVE
                || pass == CgRenderPassVariant.DISTORTION;
    }

    /**
     * This pipeline as a multi-draw binds it: the same pass compiled with
     * {@link CgMaterialShaderCompiler#MULTI_DRAW}, each draw's bases taken from its command. What an executor binds
     * for a run of draws it makes one call, where {@code CgCapabilities.multiDraw()}. Render thread.
     *
     * <pre>{@code
     * CgPipeline multi = pipeline.multiDraw();
     * if (multi.bind()) CgGL.glMultiDrawElementsIndirect(mode, CgGL.GL_UNSIGNED_INT, offset, commands, 20);
     * }</pre>
     */
    public CgPipeline multiDraw() {
        if (multiDraw) return this;
        if (multi == null) {
            multi = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, state, kind, flags() | MULTI), CgPipeline::register);
        }
        return multi;
    }

    /**
     * This pipeline as the overdraw view draws it: the same vertex stage, depth test and discard, adding a count of 1
     * in red into a target with no depth ({@link CgMaterialShaderCompiler#DEBUG_OVERDRAW}). Any thread.
     *
     * <pre>{@code
     * chunks.draw(material.pipeline(CgInstanceKind.OBJECT).overdraw(), bindings, mesh);   // into an R16F count target
     * }</pre>
     */
    public CgPipeline overdraw() {
        if (overdraw) return this;
        CgPipeline made = overdrawn;
        if (made == null) {
            CgRenderState counting = CgRenderState.builder().blend(ADD).depth(CgDepthState.NONE).cull(state.getCull())
                    .colorMask(CgColorMask.ALL).build();
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, counting, kind, (flags() | OVERDRAW) & ~(EMISSION | COVER | FOLD)),
                    CgPipeline::register);
            overdrawn = made;
        }
        return made;
    }

    /**
     * This Forward pipeline drawing its Emissive pass too, in the same draw: its glow written at location 1, into the
     * second attachment of a pass that has one ({@code CgRasterPass.attachment}). Null where the shader's Emissive pass
     * stays a draw of its own ({@link CgMaterialShaderCompiler#emissionMerge}). Any thread.
     *
     * <pre>{@code
     * CgPipeline glowing = material.pipeline(CgInstanceKind.OBJECT).emissionTarget();
     * if (glowing != null) chunks.draw(glowing, bindings, mesh);       // into recording.raster(...).attachment(emission)
     * else { ... the Forward pipeline here, and its Emissive pass into the emission target later ... }
     * }</pre>
     */
    @Nullable
    public CgPipeline emissionTarget() {
        if (emission) return this;
        if (pass != CgRenderPassVariant.FORWARD || overdraw || fold) return null;
        CgPipeline made = emitting;
        if (made == null) {
            if (shader.emissionMerge() == CgMaterialShaderCompiler.EmissionMerge.NONE) return null;
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, state, kind, flags() | EMISSION), CgPipeline::register);
            emitting = made;
        }
        return made;
    }

    /**
     * This Forward pipeline drawing its Emissive pass too, into the HDR scene: its glow added to its colour in the same
     * draw, under its own blend ({@link CgMaterialShaderCompiler#SCENE_FOLD}). Null where the shader's Emissive pass
     * stays a draw of its own ({@link CgMaterialShaderCompiler#sceneFolds}). Any thread.
     *
     * <pre>{@code
     * CgPipeline folded = material.pipeline(CgInstanceKind.OBJECT).sceneFold();
     * if (folded != null) chunks.draw(folded, bindings, mesh);              // colour and glow, into the scene
     * else { ... the Forward pipeline, then its Emissive pass at the same key ... }
     * }</pre>
     */
    @Nullable
    public CgPipeline sceneFold() {
        if (fold) return this;
        if (pass != CgRenderPassVariant.FORWARD || overdraw || emission || cover) return null;
        CgPipeline made = folding;
        if (made == null) {
            CgParsedShader parsed = shader.ensureParsed();
            if (parsed == null || !CgMaterialShaderCompiler.sceneFolds(parsed)) return null;
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, state, kind, flags() | FOLD), CgPipeline::register);
            folding = made;
        }
        return made;
    }

    /**
     * This Forward pipeline as a pass with an emission attachment draws a surface glowing nothing: it writes black at
     * its colour's alpha at location 1, so its blend covers the glows drawn behind it as it covers their colour, and
     * bloom does not lay them back over it. Null where it covers nothing: it adds ({@code dst} factor ONE), or is not a
     * single-output Forward pass. A shader whose Emissive pass merges answers {@link #emissionTarget()}'s pipeline,
     * its glow scaled by the draw's emission. Any thread.
     *
     * <pre>{@code
     * CgPipeline covering = pipeline.emissionCover();
     * chunks.draw(covering != null ? covering : pipeline, bindings, mesh);   // into recording.raster(...).attachment(emission)
     * }</pre>
     */
    @Nullable
    public CgPipeline emissionCover() {
        if (emission) return this;
        if (pass != CgRenderPassVariant.FORWARD || overdraw) return null;
        CgBlendState blend = state.getBlend();
        // Unset is the transparent pass's alpha blend.
        if (blend != null && blend.enabled() && blend.dstRgb() == CgGL.GL_ONE) return null;
        CgPipeline made = emitting;
        if (made == null) {
            CgParsedShader parsed = shader.ensureParsed();
            CgParsedPass own = parsed == null ? null : parsed.getPassByLightMode(pass.lightModeName());
            if (own == null || own.fragOutput().isMrt()) return null;
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, state, kind, flags() | EMISSION), CgPipeline::register);
            emitting = made;
        }
        return made;
    }

    /**
     * {@link #emissionCover()} for an emission target drawn on its own, after the surfaces: the cover alone at location 0,
     * under this pipeline's blend, hidden by the scene's depth as an Emissive pass is, since that target has none. Drawn
     * in the same order as the glows, it covers those behind it. Null where it covers nothing. Any thread.
     *
     * <pre>{@code
     * CgPipeline occluder = forward.emissionOccluder();
     * if (occluder != null) chunks.draw(occluder, bindings, mesh).sortKey(key);   // into the emission pass, sorted with the glows
     * }</pre>
     */
    @Nullable
    public CgPipeline emissionOccluder() {
        if (cover) return this;
        if (pass != CgRenderPassVariant.FORWARD || overdraw || emission) return null;
        CgBlendState blend = state.getBlend();
        if (blend != null && blend.enabled() && blend.dstRgb() == CgGL.GL_ONE) return null;
        CgPipeline made = occluding;
        if (made == null) {
            CgParsedShader parsed = shader.ensureParsed();
            CgParsedPass own = parsed == null ? null : parsed.getPassByLightMode(pass.lightModeName());
            if (own == null || own.fragOutput().isMrt()) return null;
            // Unset is the transparent pass's alpha blend; the emission pass's own is ONE ONE.
            CgRenderState covering = CgRenderState.builder().blend(blend != null ? blend : CgBlendState.ALPHA)
                    .depth(CgDepthState.NONE).cull(state.getCull()).colorMask(CgColorMask.ALL).build();
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, covering, kind, flags() | COVER), CgPipeline::register);
            occluding = made;
        }
        return made;
    }

    /**
     * The colour attachments its program writes, a bit each: slot 0 always, slot 1 for {@link #emissionTarget()}, and
     * an MRT output's {@code : RTn} slots. A pass drawing into more than one masks every other slot off for it, so an
     * unwritten output never lands. Render thread.
     */
    public int slotWrites() {
        int revision = shader.getRevisionNumber();
        if (slotsRevision == revision) return slots;
        int bits = 1;
        if (emission) bits |= 2;
        CgParsedShader parsed = shader.ensureParsed();
        CgParsedPass own = parsed == null ? null : parsed.getPassByLightMode(pass.lightModeName());
        if (own != null && own.fragOutput().isMrt()) {
            bits = 0;
            for (int location : own.fragOutput().locations()) bits |= 1 << location;
        }
        slots = bits;
        slotsRevision = revision;
        return bits;
    }

    private static final CgBlendState ADD = new CgBlendState(true, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    private static CgPipeline register(Key key) {
        synchronized (REGISTERING) {
            CgPipeline[] table = byId;
            if (count == table.length) table = Arrays.copyOf(table, count * 2);
            CgPipeline pipeline = new CgPipeline(count, key);
            table[count++] = pipeline;
            byId = table;
            return pipeline;
        }
    }

    /**
     * This pipeline under another render state: what a caller that overrides a slot the shader declares asks
     * for — the text renderer's depth, which {@code text.shader} cannot express per draw.
     */
    public CgPipeline withState(CgRenderState other) {
        if (other == state) return this;
        synchronized (this) {
            for (int i = 0; i < derivedCount; i++) if (derivedStates[i] == other) return derived[i];
        }
        CgPipeline variant = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, other, kind, flags()),
                CgPipeline::register);
        synchronized (this) {
            if (derivedCount == derived.length) {
                derived = Arrays.copyOf(derived, derivedCount * 2);
                derivedStates = Arrays.copyOf(derivedStates, derivedCount * 2);
            }
            derived[derivedCount] = variant;
            derivedStates[derivedCount++] = other;
        }
        return variant;
    }

    /** The pipeline {@link #id()} names. */
    public static CgPipeline byId(int id) {
        return byId[id];
    }

    public int id() {
        return id;
    }

    public CgMaterialShader shader() {
        return shader;
    }

    /**
     * Whether its shader reads a recording's tables (palette, clips, shapes) through {@code cg_use quad}, {@code curve},
     * {@code clip} or {@code shape}: whatever kind draws it, its pass binds them.
     */
    public boolean readsTables() {
        CgParsedShader parsed = shader.ensureParsed();
        if (parsed == null) return false;
        if (parsed != tablesParse) {
            boolean reads = false;
            for (String token : parsed.engineBuffers()) {
                reads |= token.equals("quad") || token.equals("curve") || token.equals("clip") || token.equals("shape");
            }
            tables = reads;
            tablesParse = parsed;
        }
        return tables;
    }

    /** {@link #readsTables()}, for the parse it was read from. */
    @Nullable
    private volatile CgParsedShader tablesParse;
    private volatile boolean tables;

    /**
     * Whether its draws sample {@code cg_DepthBuffer}: its shader names it, or the compiler's discard behind the scene
     * does, which an Emissive, Distortion, cover or overdraw pipeline has whatever its source names.
     */
    public boolean readsSceneDepth() {
        return shader.readsSceneDepth() || pass == CgRenderPassVariant.EMISSIVE || pass == CgRenderPassVariant.DISTORTION
                || cover || overdraw;
    }

    public CgRenderPassVariant pass() {
        return pass;
    }

    public Set<String> keywords() {
        return keywords;
    }

    public CgRenderState state() {
        return state;
    }

    public CgInstanceKind kind() {
        return kind;
    }

    /**
     * Starts this pipeline's program compiling without waiting, the shader's first and then this pass, keyword set
     * and multi-draw form of it, and says whether {@link #program()} would now return without compiling or waiting
     * on the driver. False on a frame that starts one; poll it frame to frame.
     */
    public boolean prepare() {
        if (shader.isDirty()) {
            shader.submitRecompile(pass == CgRenderPassVariant.FORWARD);
            return false;
        }
        if (!shader.pollPending()) return false;
        return shader.prepareVariant(pass == CgRenderPassVariant.FORWARD ? null : pass.lightModeName(), compiled);
    }

    /**
     * The compiled program, compiling it on first use or after a reload; null when the shader does not compile or
     * has no such pass.
     */
    @Nullable
    public CgShader program() {
        shader.awaitPending();
        if (shader.isDirty()) shader.recompile();
        int revision = shader.getRevisionNumber();
        if (program != null && programRevision == revision) return program;
        program = pass == CgRenderPassVariant.FORWARD
                ? shader.getOrCompileForwardPass(compiled)
                : shader.getOrCompile(pass.lightModeName(), compiled);
        programRevision = revision;
        instanceBaseLocation = UNRESOLVED;
        vertexBaseLocation = UNRESOLVED;
        return program;
    }

    /**
     * Applies the declared render state and binds the program; false, binding nothing, when there is no program.
     * The pass's own state goes first.
     */
    public boolean bind() {
        CgShader bound = program();
        if (bound == null) return false;
        state.apply();
        bound.bind();
        return true;
    }

    /**
     * Where this batch's instances start in its kind's upload: what {@code CG_INSTANCE_ID} adds to
     * {@code gl_InstanceID}. After {@link #bind()}.
     */
    public void instanceBase(int base) {
        if (instanceBaseLocation == UNRESOLVED) instanceBaseLocation = program.getUniformLocation("cg_InstanceBase");
        if (instanceBaseLocation >= 0) CgGL.glUniform1i(instanceBaseLocation, base);
    }

    /**
     * The one record every instance of the next draw reads, at {@code record} in its kind's upload: an indirect
     * {@code INSTANCES} draw's, whose instance count only the GPU knows. After {@link #bind()}.
     */
    public void sharedInstance(int record) {
        instanceBase(-1 - record);
    }

    /**
     * Where the drawn mesh's vertices start in the buffer it is drawn from: what {@code CG_VERTEX_ID} takes from
     * {@code gl_VertexID}, so a shader sees the vertex's index in its own mesh. After {@link #bind()}.
     */
    public void vertexBase(int base) {
        if (vertexBaseLocation == UNRESOLVED) vertexBaseLocation = program.getUniformLocation("cg_VertexBase");
        if (vertexBaseLocation >= 0) CgGL.glUniform1i(vertexBaseLocation, base);
    }

    @Override
    public String toString() {
        return "CgPipeline#" + id + "(" + pass + " " + keywords + " " + kind + (multiDraw ? " multi-draw" : "")
                + (overdraw ? " overdraw" : "") + (emission ? " emission" : "") + ")";
    }

    /** A shader and a render state by identity, since neither has value equality worth trusting. */
    private record Key(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords, CgRenderState state,
                       CgInstanceKind kind, int flags) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.shader == shader && k.pass == pass && k.keywords.equals(keywords)
                    && k.state == state && k.kind == kind && k.flags == flags;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(shader);
            h = h * 31 + pass.ordinal();
            h = h * 31 + keywords.hashCode();
            h = h * 31 + System.identityHashCode(state);
            h = h * 31 + kind.ordinal();
            return h * 8 + flags;
        }
    }
}
