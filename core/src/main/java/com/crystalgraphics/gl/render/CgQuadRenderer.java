package com.crystalgraphics.gl.render;

import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.api.buffer.CgGpuType;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;
import org.joml.Matrix4f;
import org.joml.Vector3f;


/**
 * Instanced quad renderer whose per-instance data lives in a single, class-wide
 * {@link CgShaderBuffer} (SSBO or TBO, chosen by the existing capability waterfall)
 * rather than GL vertex attributes — so per-instance data can grow arbitrarily
 * without ever hitting the ~16 GL vertex-attribute-slot ceiling.
 *
 * <h3>One shared mesh, format, and GPU buffer for every instance</h3>
 * <p>The unit quad mesh, the per-instance {@link CgBufferFormat}, and the backing
 * {@link CgShaderBuffer} are all class-wide static resources, not per-instance —
 * mirrors how {@link CgInstanceRenderer#flush()} already pools its instance stream
 * VBO by format via {@code CgVertexBufferRegistry}, not per renderer instance.
 * {@link #flush()} is atomic (upload → bind → draw, no yield points, GL is
 * single-threaded here), so multiple {@code CgQuadRenderer} instances sharing one
 * GPU buffer is safe: whoever calls {@code flush()} last owns the buffer's contents
 * until the next {@code flush()} overwrites it. Only the CPU-side accumulation
 * buffer is genuinely per-instance state, since different callers batch
 * independently across their own {@link #begin()}/{@link #end()} windows.</p>
 *
 * <h3>Fixed instance schema</h3>
 * <pre>
 * vec3 origin      // world-space quad origin
 * vec3 right       // world-space right edge vector
 * vec3 up          // world-space up edge vector
 * vec2 uv0         // texture UV top-left
 * vec2 uv1         // texture UV bottom-right
 * vec4 color       // unpacked float rgba
 * float atlasLayer // sampler2DArray layer index, as a float (0 for non-array-texture
 *                   // consumers — ignored by ordinary sampler2D shaders)
 * </pre>
 * <p>Not caller-extensible for now — a normal additive schema change to
 * {@link #INSTANCE_FORMAT} if a real consumer ever needs more fields, not a
 * speculative extension point built ahead of need.</p>
 *
 * <h3>Per-frame lifecycle</h3>
 * <p>{@code Quad.submit()} only queues one instance record on the CPU — it never uploads
 * to the GPU or draws anything by itself. {@link #flush()} is the only method that
 * actually uploads and issues the instanced draw call, once, for everything queued
 * since the last {@code flush()}/{@link #begin()}:</p>
 * <pre>{@code
 * // Every frame — not just once at setup:
 * renderer.useMaterial(material);  // (re)binds every call; flushes the old batch only on an actual switch
 * renderer.begin();
 * renderer.quad().at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();  // flat
 * renderer.quad().at(cx, cy).size(w, h).pose(myPose).color(argb).submit();      // transformed
 * renderer.flush(); // <- the only point at which anything is uploaded/drawn
 * renderer.end();
 * }</pre>
 *
 * <p>{@link #useMaterial(CgMaterial)} is required before any {@link Quad#submit()} call —
 * {@code submit()} throws {@link IllegalStateException} otherwise — and must be called again
 * every frame (or more often), even for the same material: it unbinds whatever was bound and
 * rebinds {@code material} on <em>every</em> call, not only when the material reference changes,
 * because other rendering code sharing the GL context can rebind a different program between
 * two frames of your own. Declaring {@code #pragma cg_use quad} wires the shader side but does not
 * satisfy this requirement, and does not bind anything. You never call
 * {@code material.bind()}/{@code unbind()} yourself — see {@link #useMaterial(CgMaterial)} for
 * the exact rules (bind/unbind is independent of {@link #begin()}/{@link #end()}).</p>
 *
 * <h3>Shader side: {@code CG_QUAD_WORLD_POS}/{@code CG_QUAD_UV}/{@code CG_QUAD_COLOR}</h3>
 * <p>{@code cg_env.glsl} (auto-included in every {@code .shader} material, no explicit
 * {@code #include} needed) declares three zero-argument macros for this exact schema —
 * no manual {@code origin + cg_Position.x*right + cg_Position.y*up} vector math, and no
 * {@code QuadInstance inst = QUAD_DATA(CG_INSTANCE_ID);} boilerplate, in the vertex shader:</p>
 * <pre>{@code
 * gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
 * o.uv = CG_QUAD_UV;
 * o.color = CG_QUAD_COLOR;
 * }</pre>
 * <p>These macros hardcode {@link #MACRO_NAME} ({@code "QUAD_DATA"}), which only resolves once this
 * renderer's buffer is attached. <b>Declare that in the shader:</b></p>
 * <pre>{@code
 * #type pos2_uv2_col4ub
 * #pragma cg_use quad
 * }</pre>
 * <p>The pragma attaches the buffer during parsing, before anything can compile, and the parser
 * <em>rejects</em> a shader that uses {@code CG_QUAD_*}/{@code QUAD_DATA} without it. There is
 * deliberately no Java-side attach helper: one existed, and attaching whenever the caller happened
 * to run lost to any path that compiled the shader earlier — which failed as a GLSL error about an
 * undefined {@code QUAD_DATA}, surfacing several layers up as an unrelated
 * {@code #pragma cg_feature} complaint.</p>
 */
public final class CgQuadRenderer extends CgAbstractRenderer {

    /**
     * Vertex format of the shared unit quad mesh: local 2D position + local UV
     * (+ an unused per-vertex color channel, negligible waste on one shared 4-vertex
     * mesh). Per-instance data — including color — lives in {@link #GPU_BUFFER}.
     */
    private static final CgVertexFormat QUAD_MESH_FORMAT = CgVertexFormat.POS2_UV2_COL4UB;

    /** Fixed per-instance record format shared by every {@code CgQuadRenderer} instance. */
    private static final CgBufferFormat INSTANCE_FORMAT = CgBufferFormat
            .builder("QuadInstance", CgBufferFormat.MemoryLayout.STD430)
            .vec3("origin").vec3("right").vec3("up")
            .vec2("uv0").vec2("uv1")
            .vec4("color")
            .float_("atlasLayer")
            .build();

    private static final String GPU_BUFFER_NAME = "CgQuadRendererInstances";

    /**
     * Fixed {@code attach()} macro name every {@code CgQuadRenderer} consumer must use —
     * not caller-chosen, so there is exactly one string to get right, and {@code cg_env.glsl}'s
     * {@code CG_QUAD_*} macros (see below) can hardcode it rather than taking a parameter.
     * Shaders declare {@code #pragma cg_use quad} rather than naming this string, so it is never
     * spelled out at a call site at all.
     */
    public static final String MACRO_NAME = "QUAD_DATA";

    /** Initial CPU accumulation capacity, in instances. Purely a pre-sizing hint — auto-grows past this. */
    private static final int INITIAL_CAPACITY_INSTANCES = 64;

    /**
     * Shared static unit quad mesh, {@code [0,0]->[1,1]}, built once. Routed through
     * {@link CgMeshRegistry} (rather than a raw static field) so it still participates
     * in the registry's teardown sweep — relying on the JVM's lazy class-initialization
     * semantics means this only runs on first real use of this class, i.e. after a GL
     * context already exists.
     */
    private static final CgMesh QUAD_MESH = CgMeshRegistry.get().getOrCreate(
            "crystalgraphics:builtin/quad/" + QUAD_MESH_FORMAT.toString(),
            () -> CgMesh.upload(CgMeshBuilder.quad2D(QUAD_MESH_FORMAT, 0f, 0f, 1f, 1f)));

    /**
     * Shared static shader buffer — one SSBO/TBO backs every {@code CgQuadRenderer} instance.
     * Bound at an <strong>engine-reserved</strong> {@link CgBindingPoints.Binding}
     * ({@link CgBindingPoints#QUAD_RENDERER}), not a {@code USER_START_*}-relative user index —
     * a plain user index (e.g. {@code 0}) risks a real GL binding-point collision if some
     * unrelated user-attached SSBO/TBO happens to pick the same index (the registry's cache
     * dedups by name+format+binding, but two buffers with different names can still be assigned
     * the same numeric GL binding point, and only one can be bound there at a time).
     *
     * <p>Relies on the JVM's lazy class-initialization guarantee, same as {@link #QUAD_MESH}:
     * {@link CgBindingPoints#init} must have run (via {@code CgRenderPipeline.init()} ←
     * {@code CgGraphicsLifecycle.initContext()}) by the time this class is first touched, i.e. by
     * the first real {@link #create()} call. Note {@code CgEngineBufferRegistry} seeds the
     * {@code quad} pragma token with a <em>method reference</em> to {@link #instanceBuffer()}
     * precisely so that registering the token does not count as touching this class — see
     * {@code CgShaderBufferRegistry#getOrCreateInternal} for the fail-fast check if that is ever
     * violated (it has genuinely fired for {@code CgTextRenderer}'s analogous
     * {@code TEXT_DATA_UBO}).</p>
     */
    private static final CgShaderBuffer GPU_BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal(GPU_BUFFER_NAME, INSTANCE_FORMAT, CgBindingPoints.QUAD_RENDERER);

    /** Standalone CPU accumulation pair — bypasses {@link #GPU_BUFFER}'s own count-declared write session. */
    private final CgStagingBuffer accumStaging;
    private final CgBufferWriter accumWriter;

    /** Reused scratch {@link Quad} instance returned by {@link #quad()}. */
    private final Quad scratchQuad = new Quad();

    /** The material {@link #useMaterial(CgMaterial)} last switched to, or {@code null} if never called. */
    private CgMaterial currentMaterial;

    /**
     * Creates a new {@code CgQuadRenderer}. Each instance owns its own CPU accumulation
     * buffer (so independent callers can batch on their own schedule) but shares the
     * static mesh, format, and GPU buffer with every other instance.
     *
     * @return a new renderer ready for use
     */
    public static CgQuadRenderer create() {
        CgStagingBuffer staging = new CgStagingBuffer(INSTANCE_FORMAT.getFloatCount(),
                Math.max(1, INITIAL_CAPACITY_INSTANCES / 4));
        return new CgQuadRenderer(staging, new CgBufferWriter(staging, INSTANCE_FORMAT));
    }

    /**
     * Field offsets into {@link #INSTANCE_FORMAT}, resolved once — see {@link CgBufferWriter#offsetOf}.
     *
     * <p>A named write costs a string-keyed {@code getField} lookup plus a type check, and this record has
     * seven fields. That is nothing for a handful of quads and everything for <b>the path every glyph in
     * the engine draws through</b>: a page of text is thousands of quads a frame, each paying seven map
     * lookups for offsets that are a property of a compile-time-constant format.</p>
     */
    private final int offOrigin, offRight, offUp, offUv0, offUv1, offColor, offAtlasLayer;

    private CgQuadRenderer(CgStagingBuffer accumStaging, CgBufferWriter accumWriter) {
        this.accumStaging = accumStaging;
        this.accumWriter = accumWriter;
        this.offOrigin = accumWriter.offsetOf("origin", CgGpuType.VEC3);
        this.offRight = accumWriter.offsetOf("right", CgGpuType.VEC3);
        this.offUp = accumWriter.offsetOf("up", CgGpuType.VEC3);
        this.offUv0 = accumWriter.offsetOf("uv0", CgGpuType.VEC2);
        this.offUv1 = accumWriter.offsetOf("uv1", CgGpuType.VEC2);
        this.offColor = accumWriter.offsetOf("color", CgGpuType.VEC4);
        this.offAtlasLayer = accumWriter.offsetOf("atlasLayer", CgGpuType.FLOAT);
    }

    /**
     * The shared instance buffer, without needing a renderer instance.
     *
     * <p>Exists for {@code CgEngineBufferRegistry}, which seeds the {@code quad} {@code #pragma
     * cg_use} token with a reference to this method. Being a method reference rather than a direct
     * field read, it does not trigger this class's static initialization at registration time — which
     * matters, because {@link #GPU_BUFFER} allocates against {@code CgBindingPoints} and is only
     * valid after {@code CgRenderPipeline.init()}.</p>
     */
    public static CgShaderBuffer instanceBuffer() {
        return GPU_BUFFER;
    }

    /**
     * Marks {@code material} as the one this renderer's next {@code quad()...submit()} calls
     * belong to. This is the required entry point before any {@link Quad#submit()} call —
     * declaring {@code #pragma cg_use quad} wires the shader side but {@code submit()} still throws,
     * since it has no other way to confirm this renderer is the one owning the queued instances:
     *
     * <ul>
     *   <li><b>Auto-flush on material change.</b> If {@code material} differs (by reference)
     *       from whatever was active before, {@link #flush()} is called first. Instances
     *       already queued were computed for the *previous* material's shader/state; drawing
     *       them under a newly-bound, different program would silently misrender everything
     *       queued so far, not just fail to draw the new material's quads. Passing the same
     *       instance again does not flush.</li>
     *   <li><b>Bind/unbind is handled for you, on <em>every</em> call — not just on a material
     *       change.</b> The currently-bound material (if any) is unbound and {@code material} is
     *       (re)bound every time this method runs, even if it's the same instance as last time.
     *       This is deliberate, not redundant: other rendering code (a different scene's shaders,
     *       a HUD/overlay pass, anything else sharing the GL context) can rebind a completely
     *       different program between two frames of <em>your</em> code, so "I bound this material
     *       once, a while ago" does not mean it is still the active program now. Call
     *       {@code useMaterial(material)} again every time you're about to submit quads for it —
     *       once per frame at minimum — not just once ever. You never need to call
     *       {@code material.bind()}/{@code unbind()} yourself.</li>
     * </ul>
     *
     * @param material the material about to receive {@code quad()...submit()} calls
     * @return this renderer, for chaining into {@code begin()}/{@code quad()}
     */
    public CgQuadRenderer useMaterial(CgMaterial material) {
        if (material != currentMaterial) {
            flush();
            if (currentMaterial != null) currentMaterial.unbind();
            currentMaterial = material;
        }
        material.bind();
        return this;
    }

    @Override
    protected void onBegin() {
        accumStaging.reset();
    }

    @Override
    protected boolean hasPendingWork() {
        return !accumStaging.isEmpty();
    }

    /**
     * Starts a fluent quad submission using this renderer's single reused scratch
     * instance — zero allocation per call. Build it and call {@link Quad#submit()} in
     * the same expression; do not hold the returned reference past that, since any
     * other {@code quad()} call on this renderer resets and reuses the same instance.
     * For a quad descriptor you want to hold across frames, use {@link #retainedQuad()}.
     *
     * <p><strong>{@link Quad#submit()} only queues one instance record on the CPU</strong> —
     * it does not upload to the GPU or draw anything. Call as many
     * {@code quad()...submit()} as you want batched together, then call {@link #flush()}
     * once to actually upload and draw them.</p>
     *
     * <pre>{@code
     * renderer.quad().at(x, y).size(w, h).uv(0, 0, 1, 1).color(argb).submit();
     * renderer.quad().at(cx, cy).size(w, h).pose(myPose).color(argb).submit();
     * renderer.flush(); // <- the actual upload + draw call happens here, not in submit()
     * }</pre>
     */
    public Quad quad() {
        return scratchQuad.reset();
    }

    /**
     * Allocates a standalone, retained-mode {@link Quad} instance the caller owns and
     * may hold across frames — e.g. build once, then call {@link Quad#submit()} every
     * tick, mutating only whatever field changed. Independent of {@link #quad()}'s
     * shared immediate-mode scratch instance.
     */
    public Quad retainedQuad() {
        return new Quad();
    }

    /**
     * Fluent, mutable quad submission request — every field the fixed instance schema
     * needs (position, size, UV rect, color, and an optional per-quad transform),
     * without the caller ever touching a raw {@link CgBufferWriter}.
     *
     * <h3>Defaults</h3>
     * <p>{@link #at(float, float)}/{@link #at(float, float, float)} default to {@code (0, 0, 0)};
     * {@link #uv(float, float, float, float)} defaults to the full texture ({@code 0,0 - 1,1});
     * {@link #color(int)} defaults to opaque white; {@link #pose(Matrix4f)} defaults to
     * {@code null} (no transform — the quad is submitted in local/flat space, exactly
     * {@code origin=(x,y,z)}, {@code right=(width,0,0)}, {@code up=(0,height,0)}).
     * {@link #size(float, float)} has no default — {@link #submit()} throws
     * {@link IllegalStateException} if it was never called.</p>
     *
     * <h3>Allocation-free</h3>
     * <p>{@link #submit()} never allocates: the {@link Matrix4f#transformPosition(Vector3f)}/
     * {@link Matrix4f#transformDirection(Vector3f)} calls used to bake {@link #pose(Matrix4f)}
     * write into this instance's own reused {@link Vector3f} scratch fields, not new objects —
     * safe for hot per-frame submission loops.</p>
     */
    public final class Quad {

        private float x, y, z;
        private float width, height;
        private boolean sizeSet;
        private float u0, v0, u1, v1;
        private int argb;
        private float atlasLayer;
        private Matrix4f pose;

        // Reused across every submit() call on this Quad instance — never reallocated.
        private final Vector3f scratchOrigin = new Vector3f();
        private final Vector3f scratchRight = new Vector3f();
        private final Vector3f scratchUp = new Vector3f();

        private Quad() {
            reset();
        }

        Quad reset() {
            x = 0f;
            y = 0f;
            z = 0f;
            width = 0f;
            height = 0f;
            sizeSet = false;
            u0 = 0f;
            v0 = 0f;
            u1 = 1f;
            v1 = 1f;
            argb = 0xFFFFFFFF;
            atlasLayer = 0f;
            pose = null;
            return this;
        }

        /** Local-space quad origin, Z defaults to {@code 0}. See {@link #at(float, float, float)}. */
        public Quad at(float x, float y) {
            return at(x, y, 0f);
        }

        /**
         * Local-space quad origin, including depth. This is the ONLY way to give a quad a
         * non-zero Z without a full {@link #pose(Matrix4f)} — e.g. depth-sorting flat,
         * axis-aligned quads against each other at different depths. It's an honest part of
         * this API specifically because {@code origin} is a full {@code vec3} in the instance
         * schema (zero extra cost — that field was already there), so silently dropping a Z
         * component here would be misleading, not a real limitation.
         *
         * <p>Not to be confused with real 3D orientation: {@code z} only translates the quad's
         * origin along Z — it does not tilt/rotate the quad's own plane. For that, use
         * {@link #pose(Matrix4f)} (this is how 3D-world text positioning already works: via the
         * transform, not via per-vertex/per-origin Z).</p>
         *
         * @param z local-space depth. Baked through {@link #pose(Matrix4f)} if set (as the Z
         *          component of the point handed to {@code transformPosition}), or used directly
         *          otherwise. Defaults to {@code 0}.
         */
        public Quad at(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Quad extents. Required — {@link #submit()} throws if never called. */
        public Quad size(float width, float height) {
            this.width = width;
            this.height = height;
            this.sizeSet = true;
            return this;
        }

        /** Texture UV rect (top-left, bottom-right). Defaults to {@code (0,0) - (1,1)}. */
        public Quad uv(float u0, float v0, float u1, float v1) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            return this;
        }

        /** Packed ARGB color (alpha in the top byte). Defaults to opaque white. */
        public Quad color(int argb) {
            this.argb = argb;
            return this;
        }

        /**
         * Array-texture layer index for consumers whose material samples a
         * {@code sampler2DArray} (e.g. {@code text.shader}'s atlas). Defaults to
         * {@code 0} — harmless for ordinary {@code sampler2D} consumers, which
         * never read {@code CG_QUAD_ATLAS_LAYER} at all.
         */
        public Quad atlasLayer(int layer) {
            this.atlasLayer = layer;
            return this;
        }

        /**
         * Optional per-quad transform, baked on the CPU at {@link #submit()} time into
         * {@code origin}/{@code right}/{@code up} (point + two edge vectors — see
         * {@code CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md} Decision 2 for why 3 vectors
         * instead of a full {@code mat4} per instance). {@code null} (the default) submits
         * the quad untransformed, in local/flat space.
         */
        public Quad pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Writes this quad as one instance record into the owning {@link CgQuadRenderer}'s
         * CPU-side accumulation buffer — <strong>nothing more</strong>. This does
         * <strong>not</strong> upload to the GPU and does <strong>not</strong> issue a draw
         * call; it only queues one instance. Call {@link CgQuadRenderer#flush()}
         * (directly, or via a {@link CgRenderLayer}) once, after however many
         * {@code quad()...submit()} calls you want batched together, to actually draw them.
         *
         * @return the owning {@code CgQuadRenderer}, so the last call in a manually-batched
         *         sequence can chain into {@link #flush()}/{@link #end()} — chaining into
         *         {@code flush()} here does not mean {@code submit()} itself flushed anything
         * @throws IllegalStateException if {@link #begin()} hasn't been called, if
         *                               {@link #size(float, float)} was never set, or if
         *                               {@link #useMaterial(CgMaterial)} was never called
         */
        public CgQuadRenderer submit() {
            if (!begun) throw new IllegalStateException("CgQuadRenderer not begun");
            if (!sizeSet) throw new IllegalStateException("CgQuadRenderer.Quad requires size(width, height) before submit()");
            if (currentMaterial == null) throw new IllegalStateException(
                    "CgQuadRenderer.Quad requires useMaterial(material) before submit() — "
                            + "without it, this renderer's buffer may not be attached to whatever material is bound");

            float ox = x, oy = y, oz = z;
            float rx = width, ry = 0f, rz = 0f;
            float ux = 0f, uy = height, uz = 0f;

            if (pose != null) {
                scratchOrigin.set(ox, oy, oz);
                scratchRight.set(rx, ry, rz);
                scratchUp.set(ux, uy, uz);
                pose.transformPosition(scratchOrigin);
                pose.transformDirection(scratchRight);
                pose.transformDirection(scratchUp);
                ox = scratchOrigin.x(); oy = scratchOrigin.y(); oz = scratchOrigin.z();
                rx = scratchRight.x(); ry = scratchRight.y(); rz = scratchRight.z();
                ux = scratchUp.x(); uy = scratchUp.y(); uz = scratchUp.z();
            }

            accumWriter.beginRecord()
                    .vec3At(offOrigin, ox, oy, oz)
                    .vec3At(offRight, rx, ry, rz)
                    .vec3At(offUp, ux, uy, uz)
                    .vec2At(offUv0, u0, v0)
                    .vec2At(offUv1, u1, v1)
                    .colorAt(offColor, argb)
                    .floatAt(offAtlasLayer, atlasLayer)
                    .endRecord();

            return CgQuadRenderer.this;
        }
    }

    /**
     * Uploads the accumulated instance data and issues one instanced draw call.
     *
     * <p>State-blind per {@link CgAbstractRenderer}'s contract: never touches
     * shader/texture/blend/depth/cull state. Binding the shader buffer itself is
     * "data wiring," not render state, the same way {@link CgInstanceRenderer#flush()}
     * already uploads and rebinds its own instance VBO internally.</p>
     */
    @Override
    public void flush() {
        if (!begun || accumStaging.isEmpty()) {
            accumStaging.reset();
            return;
        }
        // Instrumented per stage because this is where text actually reaches the GPU. The text draw
        // path runs through CgQuadRenderer (instanced quads), not CgBatchRenderer, so this method —
        // not that one — is the tail of every glyph draw.
        try (CgProfiler.Scope ignored = CgProfiler.scope("quadRenderer.flush")) {
            int instanceCount = accumStaging.vertexCount();
            CgProfiler.count("quadRenderer.flush.count");
            CgProfiler.sample("quadRenderer.instances", instanceCount);

            try (CgProfiler.Scope ignored2 = CgProfiler.scope("quadRenderer.upload")) {
                GPU_BUFFER.uploadRaw(accumStaging.rawData(), accumStaging.rawCursor());
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("quadRenderer.bindBuffer")) {
                GPU_BUFFER.bind();
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("quadRenderer.drawInstanced")) {
                QUAD_MESH.drawInstanced(instanceCount);
            }
            accumStaging.reset();
        }
    }

    /**
     * Unbinds {@link #currentMaterial}, if {@link #useMaterial(CgMaterial)} left one bound —
     * otherwise a no-op. The mesh and shader buffer are static/registry-owned and are not
     * released here (they outlive any single renderer instance).
     */
    @Override
    public void delete() {
        if (currentMaterial != null) {
            currentMaterial.unbind();
            currentMaterial = null;
        }
    }
}
