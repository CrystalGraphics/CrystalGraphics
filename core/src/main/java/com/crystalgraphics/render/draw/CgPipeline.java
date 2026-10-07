package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
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
    /** What the program is compiled with: the pass's keywords, and the multi-draw's. */
    private final Set<String> compiled;
    @Nullable
    private CgPipeline multi;
    @Nullable
    private CgPipeline overdrawn;

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
        this.multiDraw = key.multiDraw;
        this.overdraw = key.overdraw;
        Set<String> passKeywords = takesKeywords(pass) ? keywords : Collections.emptySet();
        if (multiDraw || overdraw) {
            Set<String> more = new TreeSet<>(passKeywords);
            if (multiDraw) more.add(CgMaterialShaderCompiler.MULTI_DRAW);
            if (overdraw) more.add(CgMaterialShaderCompiler.DEBUG_OVERDRAW);
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
        return INTERNED.computeIfAbsent(new Key(shader, pass, variant, state, kind, false, false), CgPipeline::register);
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
            multi = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, state, kind, true, overdraw), CgPipeline::register);
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
            made = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, counting, kind, multiDraw, true), CgPipeline::register);
            overdrawn = made;
        }
        return made;
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
        CgPipeline variant = INTERNED.computeIfAbsent(new Key(shader, pass, keywords, other, kind, multiDraw, overdraw),
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
                + (overdraw ? " overdraw" : "") + ")";
    }

    /** A shader and a render state by identity, since neither has value equality worth trusting. */
    private record Key(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords, CgRenderState state,
                       CgInstanceKind kind, boolean multiDraw, boolean overdraw) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.shader == shader && k.pass == pass && k.keywords.equals(keywords)
                    && k.state == state && k.kind == kind && k.multiDraw == multiDraw && k.overdraw == overdraw;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(shader);
            h = h * 31 + pass.ordinal();
            h = h * 31 + keywords.hashCode();
            h = h * 31 + System.identityHashCode(state);
            h = h * 31 + kind.ordinal();
            return h * 4 + (multiDraw ? 1 : 0) + (overdraw ? 2 : 0);
        }
    }
}
