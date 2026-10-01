package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.material.CgMaterialShader;
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
 *       {@link #prepare()} and {@link #instanceBase} are render thread only.</li>
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

    @Nullable
    private CgShader program;
    private int programRevision = -1;
    private int instanceBaseLocation = UNRESOLVED;

    private CgPipeline(int id, Key key) {
        this.id = id;
        this.shader = key.shader;
        this.pass = key.pass;
        this.keywords = key.keywords;
        this.state = key.state;
        this.kind = key.kind;
    }

    /**
     * The pipeline for this variant, state and kind, made on first use.
     *
     * @param keywords the enabled {@code #pragma cg_feature} names; ignored for any pass but
     *                 {@link CgRenderPassVariant#FORWARD}, which is the only one keywords apply to
     */
    public static CgPipeline of(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords,
                                CgRenderState state, CgInstanceKind kind) {
        Set<String> variant = pass == CgRenderPassVariant.FORWARD && !keywords.isEmpty()
                ? Collections.unmodifiableSet(new TreeSet<>(keywords))
                : Collections.emptySet();
        return INTERNED.computeIfAbsent(new Key(shader, pass, variant, state, kind), CgPipeline::register);
    }

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
     * Starts this pipeline's compile without waiting, and says whether {@link #program()} would now return without
     * waiting on the driver. False on the frame it starts one.
     */
    public boolean prepare() {
        if (shader.isDirty()) {
            shader.submitRecompile(pass == CgRenderPassVariant.FORWARD);
            return false;
        }
        return shader.pollPending();
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
                ? shader.getOrCompileForwardPass(keywords)
                : shader.getOrCompile(pass.lightModeName(), Collections.emptySet());
        programRevision = revision;
        instanceBaseLocation = UNRESOLVED;
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

    @Override
    public String toString() {
        return "CgPipeline#" + id + "(" + pass + " " + keywords + " " + kind + ")";
    }

    /** A shader and a render state by identity, since neither has value equality worth trusting. */
    private record Key(CgMaterialShader shader, CgRenderPassVariant pass, Set<String> keywords, CgRenderState state,
                       CgInstanceKind kind) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.shader == shader && k.pass == pass && k.keywords.equals(keywords)
                    && k.state == state && k.kind == kind;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(shader);
            h = h * 31 + pass.ordinal();
            h = h * 31 + keywords.hashCode();
            h = h * 31 + System.identityHashCode(state);
            return h * 31 + kind.ordinal();
        }
    }
}
