package com.crystalgraphics.api.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.shader.CgLinkWorker;
import com.crystalgraphics.gl.shader.CgProgramBinaryCache;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlProgramNames;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.crystalgraphics.gl.shader.CgShaderFactory.JOML_BUFFER;

/**
 * A linked GLSL program: compiled from vertex and fragment source, bound for drawing, given its uniforms. Most
 * callers want a {@link CgShader} or a material, which own one.
 *
 * <pre>{@code
 * CgShaderProgram program = CgShaderProgram.compile(vertexSource, fragmentSource, CgVertexFormat.SPATIAL);
 * program.bind();
 * program.setUniformMatrix4f(program.getUniformLocation("u_mvp"), mvp);
 * ... draw ...
 * program.delete();
 * }</pre>
 *
 * <p>Linking without waiting, for a driver that compiles on threads of its own:</p>
 * <pre>{@code
 * CgShaderProgram program = CgShaderProgram.create();
 * program.submitLink(vertexSource, fragmentSource, format);
 * // later frames:
 * if (program.isLinkDone()) program.finishLink();   // throws as compile would
 * }</pre>
 *
 * <p>A compute program, for a kernel:</p>
 * <pre>{@code
 * CgShaderProgram kernel = CgShaderProgram.compileCompute(computeSource);
 * }</pre>
 *
 * <p>A capture program, for a kernel lowered below compute: its outputs written to a transform feedback buffer:</p>
 * <pre>{@code
 * CgShaderProgram capture = CgShaderProgram.compileCapture(vertex, geometry, null, new String[]{"_cg_c0", "_cg_c1"});
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, as every GL object.</li>
 *   <li>The uniform setters act on the <em>bound</em> program: bind it first.</li>
 *   <li>A failed compile or link throws {@link IllegalStateException} with the driver's log. A failed
 *       {@link #relink} keeps the program's id and its previous executable.</li>
 *   <li>Finish a {@link #submitLink} before binding: anything that queries the program waits for the driver.</li>
 *   <li>A link is kept across launches as the driver's binary where it gives one ({@link CgProgramBinaryCache}): a
 *       second launch loads it instead of compiling. Where the driver links on the calling thread (no
 *       {@code KHR_parallel_shader_compile}), every link not loaded is counted as {@code shader.blocking-links}.</li>
 * </ul>
 */
public final class CgShaderProgram {

    /** Links submitted where the driver links on the calling thread: each is a stall of that thread. */
    private static final int BLOCKING_LINKS = CgTrace.name("shader.blocking-links");

    private static final int LINK_WAIT = CgTrace.name("shader.linkWait"), LINK_WAITS = CgTrace.name("shader.link-waits"),
            STRIP = CgTrace.name("shader.strip");

    private static final ThreadLocal<String> LABEL = new ThreadLocal<>();

    /** A scope naming the links this thread submits, closed to restore the name before. */
    public interface Labelled extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Names every link this thread submits until the scope closes: the {@code shader.link.size} marker carries it, so a
     * trace says which program a slow link was.
     *
     * <pre>{@code
     * try (CgShaderProgram.Labelled ignored = CgShaderProgram.labelled(path + " " + pass + keywords)) {
     *     shader = CgShaderFactory.submit(vertex, fragment, format);
     * }
     * }</pre>
     */
    public static Labelled labelled(String label) {
        String before = LABEL.get();
        LABEL.set(label);
        return () -> LABEL.set(before);
    }

    /** 0 until a link on the {@link CgLinkWorker} lands: the worker makes the program. */
    private int programId;
    private boolean deleted;
    /** A link on the worker this program has not taken yet. */
    @Nullable private WorkerLink link;

    /** The shader objects of a link not yet finished; 0 when nothing is pending. */
    private int pendingVert;
    private int pendingFrag;
    private int pendingCompute;
    /** The binary cache's key for the pending link, kept once it links; null when none is kept. */
    @Nullable private String pendingKey;

    private CgShaderProgram(int programId) {
        this.programId = programId;
    }

    /**
     * Compiles a vertex and a fragment shader and links them, binding {@code format}'s attributes to sequential
     * locations.
     *
     * @param format the vertex format of the geometry it draws, or null to bind no attribute locations
     * @throws IllegalStateException if a stage fails to compile or the program to link, with the driver's log
     */
    public static CgShaderProgram compile(String vertexSource, String fragmentSource, CgVertexFormat format) {
        CgShaderProgram program = create();
        try {
            program.relink(vertexSource, fragmentSource, format);
        } catch (IllegalStateException e) {
            program.delete();
            throw e;
        }
        return program;
    }

    /**
     * Compiles a compute shader and links it alone.
     *
     * @throws IllegalStateException if it fails to compile or link, with the driver's log
     */
    public static CgShaderProgram compileCompute(String computeSource) {
        CgShaderProgram program = create();
        try {
            program.submitComputeLink(computeSource);
            program.finishLink();
        } catch (IllegalStateException e) {
            program.delete();
            throw e;
        }
        return program;
    }

    /**
     * Compiles a vertex shader, with a geometry and a fragment shader where given, and links them; {@code varyings},
     * outputs of the last stage before rasterising, are captured interleaved into transform feedback buffer 0.
     * Synchronous.
     *
     * @param varyings the outputs to capture, or null for none
     * @throws IllegalStateException if a stage fails to compile or the program to link, with the driver's log
     */
    public static CgShaderProgram compileCapture(String vertex, @Nullable String geometry, @Nullable String fragment,
                                                 @Nullable String[] varyings) {
        CgShaderProgram program = new CgShaderProgram(CgGL.glCreateProgram());
        int id = program.programId;
        int[] stages = new int[3];
        try {
            stages[0] = stage(CgGL.GL_VERTEX_SHADER, vertex, "Vertex");
            if (geometry != null) stages[1] = stage(CgGL.GL_GEOMETRY_SHADER, geometry, "Geometry");
            if (fragment != null) stages[2] = stage(CgGL.GL_FRAGMENT_SHADER, fragment, "Fragment");
            for (int s : stages) if (s != 0) CgGL.glAttachShader(id, s);
            if (varyings != null) CgGL.glTransformFeedbackVaryings(id, varyings, CgGL.GL_INTERLEAVED_ATTRIBS);
            CgGL.glLinkProgram(id);
            if (CgGL.glGetProgrami(id, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Capture program link failed: " + CgGL.glGetProgramInfoLog(id, 4096));
            }
        } catch (IllegalStateException e) {
            program.delete();
            throw e;
        } finally {
            for (int s : stages) {
                if (s == 0) continue;
                if (!program.deleted) CgGL.glDetachShader(id, s);
                CgGL.glDeleteShader(s);
            }
        }
        return program;
    }

    private static int stage(int type, String source, String name) {
        int shader = CgGL.glCreateShader(type);
        CgGL.glShaderSource(shader, CgGlslStrip.forDriver(source));
        CgGL.glCompileShader(shader);
        if (CgGL.glGetShaderi(shader, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
            String log = CgGL.glGetShaderInfoLog(shader, 4096);
            CgGL.glDeleteShader(shader);
            throw new IllegalStateException(name + " shader compile failed: " + log);
        }
        return shader;
    }

    /**
     * A program with nothing linked yet, for a {@link #submitLink} to fill. Where the {@link CgLinkWorker} runs, it has
     * no id until that link is finished: the worker makes it.
     */
    public static CgShaderProgram create() {
        return new CgShaderProgram(CgLinkWorker.on() ? 0 : CgGL.glCreateProgram());
    }

    // ── Binding and lifetime ───────────────────────────────────────────────

    /** Makes this the program draws use. */
    public void bind() {
        CgGL.glUseProgram(programId);
    }

    /** Binds program 0. */
    public void unbind() {
        CgGL.glUseProgram(0);
    }

    /** The GL program name. */
    public int getId() {
        return programId;
    }

    /** Releases the GL program, and any shader objects a pending link still holds. A second call does nothing. */
    public void delete() {
        if (deleted) return;
        if (link != null) {
            if (link.discard()) programId = link.program;   // landed already: ours to delete
            link = null;
        }
        if (pendingVert != 0) {
            CgGL.glDeleteShader(pendingVert);
            CgGL.glDeleteShader(pendingFrag);
            pendingVert = 0;
            pendingFrag = 0;
        }
        if (pendingCompute != 0) {
            CgGL.glDeleteShader(pendingCompute);
            pendingCompute = 0;
        }
        if (programId != 0) CgGL.glDeleteProgram(programId);
        deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    // ── Linking ────────────────────────────────────────────────────────────

    /**
     * Relinks this program from new sources, keeping its id: the old shader objects are detached and deleted, the
     * new ones compiled, attached, given {@code format}'s attribute locations and linked.
     *
     * @param format the vertex format for {@code glBindAttribLocation}, or null to bind none
     * @throws IllegalStateException if a stage fails to compile or the program to link. GL keeps the previous
     *         executable, so a later successful relink recovers it
     */
    public void relink(String vertexSource, String fragmentSource, CgVertexFormat format) {
        submitLink(vertexSource, fragmentSource, format);
        finishLink();
    }

    /**
     * {@link #relink}, returning before the driver has finished: no status is queried in between, which is what
     * lets the driver compile on its own threads. The shader objects stay attached until {@link #finishLink}, which
     * reads their logs if the link failed.
     */
    public void submitLink(String vertexSource, String fragmentSource, CgVertexFormat format) {
        finishPendingQuietly();
        String[] parts = new String[2 + (format != null ? format.getAttributeCount() : 0)];
        parts[0] = vertexSource;
        parts[1] = fragmentSource;
        for (int i = 2; i < parts.length; i++) parts[i] = format.getAttribute(i - 2).getName();
        String key = CgProgramBinaryCache.key(parts);
        if (onWorker(new WorkerLink(vertexSource, fragmentSource, null, parts, key))) return;
        detachAll();
        if (key != null && CgProgramBinaryCache.load(programId, key)) return;
        pendingKey = key;

        pendingVert = CgGL.glCreateShader(CgGL.GL_VERTEX_SHADER);
        CgGL.glShaderSource(pendingVert, CgGlslStrip.forDriver(vertexSource));
        CgGL.glCompileShader(pendingVert);
        pendingFrag = CgGL.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        CgGL.glShaderSource(pendingFrag, CgGlslStrip.forDriver(fragmentSource));
        CgGL.glCompileShader(pendingFrag);

        CgGL.glAttachShader(programId, pendingVert);
        CgGL.glAttachShader(programId, pendingFrag);
        if (format != null) {
            for (int i = 0; i < format.getAttributeCount(); i++)
                CgGL.glBindAttribLocation(programId, i, format.getAttribute(i).getName());
        }
        CgProgramBinaryCache.retrievable(programId, key);
        CgGL.glLinkProgram(programId);
        countBlocking();
    }

    /** {@link #submitLink} for a compute program: one compute shader, linked alone. */
    public void submitComputeLink(String computeSource) {
        submitComputeLink(computeSource, null);
    }

    /**
     * {@link #submitComputeLink(String)} with the source as the driver gets it made already ({@link #forDriver}), on a
     * worker: stripping a large kernel took 30-40 ms of the render thread. Null strips it here.
     */
    public void submitComputeLink(String computeSource, @Nullable String driverSource) {
        finishPendingQuietly();
        String key = CgProgramBinaryCache.key(computeSource);
        if (onWorker(new WorkerLink(null, null, computeSource, null, key))) return;
        detachAll();
        if (key != null && CgProgramBinaryCache.load(programId, key)) return;
        pendingKey = key;
        pendingCompute = CgGL.glCreateShader(CgGL.GL_COMPUTE_SHADER);
        CgGL.glShaderSource(pendingCompute, driverSource != null ? driverSource : CgGlslStrip.forDriver(computeSource));
        CgGL.glCompileShader(pendingCompute);
        CgGL.glAttachShader(programId, pendingCompute);
        CgProgramBinaryCache.retrievable(programId, key);
        CgGL.glLinkProgram(programId);
        countBlocking();
    }

    /**
     * {@code source} as the driver is handed it: comments, decided branches and unreached code stripped. CPU only, any
     * thread: what a worker making a kernel's sources ahead runs, for {@link #submitComputeLink(String, String)}.
     */
    public static String forDriver(String source) {
        return CgGlslStrip.forDriver(source);
    }

    private static void countBlocking() {
        if (!CgCapabilities.detect().isParallelShaderCompile()) CgTrace.add(CgChannels.GL, BLOCKING_LINKS, 1);
    }

    private void detachAll() {
        IntBuffer countBuf = CgBufferUtils.createIntBuffer(1);
        IntBuffer shadersBuf = CgBufferUtils.createIntBuffer(16);
        CgGL.glGetAttachedShaders(programId, countBuf, shadersBuf);
        int attached = countBuf.get(0);
        for (int i = 0; i < attached; i++) {
            int id = shadersBuf.get(i);
            CgGL.glDetachShader(programId, id);
            CgGL.glDeleteShader(id);
        }
    }

    /** Whether {@link #finishLink} would return without waiting. True wherever the driver cannot say. */
    public boolean isLinkDone() {
        if (link != null) return link.isDone();
        return pendingVert == 0 && pendingCompute == 0 || !CgCapabilities.detect().isParallelShaderCompile()
                || CgGL.glGetProgrami(programId, CgGL.GL_COMPLETION_STATUS_KHR) == CgGL.GL_TRUE;
    }

    /**
     * Ends a {@link #submitLink}: waits if the driver has not finished, then throws as {@link #relink} would. A no-op
     * when nothing is pending.
     */
    public void finishLink() {
        if (link != null) {
            takeLink();
            return;
        }
        if (pendingCompute != 0) {
            finishComputeLink();
            return;
        }
        if (pendingVert == 0) return;
        int vertId = pendingVert, fragId = pendingFrag;
        String key = pendingKey;
        pendingVert = 0;
        pendingFrag = 0;
        pendingKey = null;
        try {
            // The compile statuses first: a failed stage makes the link fail too, and its log names the line.
            if (CgGL.glGetShaderi(vertId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Vertex shader compile failed: " + CgGL.glGetShaderInfoLog(vertId, 4096));
            }
            if (CgGL.glGetShaderi(fragId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Fragment shader compile failed: " + CgGL.glGetShaderInfoLog(fragId, 4096));
            }
            if (CgGL.glGetProgrami(programId, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Shader program link failed: " + CgGL.glGetProgramInfoLog(programId, 4096));
            }
            CgProgramBinaryCache.store(programId, key);
        } finally {
            CgGL.glDetachShader(programId, vertId);
            CgGL.glDetachShader(programId, fragId);
            CgGL.glDeleteShader(vertId);
            CgGL.glDeleteShader(fragId);
        }
    }

    private void finishComputeLink() {
        int computeId = pendingCompute;
        String key = pendingKey;
        pendingCompute = 0;
        pendingKey = null;
        try {
            if (CgGL.glGetShaderi(computeId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Compute shader compile failed: " + CgGL.glGetShaderInfoLog(computeId, 4096));
            }
            if (CgGL.glGetProgrami(programId, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Compute program link failed: " + CgGL.glGetProgramInfoLog(programId, 4096));
            }
            CgProgramBinaryCache.store(programId, key);
        } finally {
            CgGL.glDetachShader(programId, computeId);
            CgGL.glDeleteShader(computeId);
        }
    }

    /**
     * Hands a link of a program never linked to the {@link CgLinkWorker}; false where it runs on this thread instead.
     * A program already linked keeps its id and relinks here: draws may be reading it.
     */
    private boolean onWorker(WorkerLink work) {
        if (programId != 0) return false;
        if (CgLinkWorker.submit(work)) {
            link = work;
            return true;
        }
        programId = CgGL.glCreateProgram();
        return false;
    }

    /** The worker's link, waited for if it has not landed: its program becomes this one's, or its failure is thrown. */
    private void takeLink() {
        WorkerLink taken = link;
        link = null;
        if (!taken.isDone()) {
            CgLinkWorker.boost(taken);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, LINK_WAIT)) {
                taken.await();
            }
            CgTrace.add(CgChannels.GL, LINK_WAITS, 1);
        }
        programId = taken.program;
        String error = taken.error != null ? taken.error : taken.failure();
        if (error != null) {
            if (programId != 0) CgGL.glDeleteProgram(programId);
            programId = 0;
            throw new IllegalStateException(error);
        }
    }

    /**
     * One link on the {@link CgLinkWorker}: the program made, compiled and linked there, its binary loaded or kept, its
     * status and log read there. Vertex and fragment sources, or a compute source.
     */
    private static final class WorkerLink extends CgLinkWorker.Job {
        /** A uniform or storage block's declaration: "uniform Name {", "readonly buffer Name {". */
        private static final Pattern BLOCK = Pattern.compile("\\b(uniform|buffer)\\s+([A-Za-z_]\\w*)\\s*\\{");
        @Nullable private final String vertex, fragment, compute;
        @Nullable private final String[] attributes;
        @Nullable private final String key;
        /** {@link #labelled}'s name when it was submitted, or null. */
        @Nullable private final String label = LABEL.get();
        private int vert, frag, comp;
        /** The stripped sources' length, for the {@code shader.link.size} marker. */
        private int chars;
        /** Read by the render thread once {@link #isDone()}. */
        private int program;
        @Nullable private String error;

        WorkerLink(@Nullable String vertex, @Nullable String fragment, @Nullable String compute,
                   @Nullable String[] attributes, @Nullable String key) {
            this.vertex = vertex;
            this.fragment = fragment;
            this.compute = compute;
            this.attributes = attributes;
            this.key = key;
        }

        /** Isolated: what {@link #finish} read of the program it compiled apart, for {@link #land}; else null. */
        @Nullable private ByteBuffer binary;
        private int binaryFormat;
        /** Isolated: the program was compiled on the worker's own context, and {@link #land} loads it on the shared one. */
        private boolean isolated;
        /** Whether the context has storage blocks to look up: asked when made, on the render thread. */
        private final boolean storage = CgCapabilities.detect().shaderBufferPath() != CgCapabilities.ShaderBufferPath.NONE;

        @Override
        protected boolean start() {
            isolated = CgLinkWorker.isolated();
            // Isolated, a kept binary loads on the shared context in land(), which needs no compile here
            if (isolated && key != null && CgProgramBinaryCache.has(key)) return true;
            program = CgGL.glCreateProgram();
            if (!isolated && key != null && CgProgramBinaryCache.load(program, key)) {
                names();
                return true;
            }
            compileAndLink();
            return false;
        }

        /** Compiles the sources into {@link #program} and starts its link. */
        private void compileAndLink() {
            if (compute != null) {
                comp = shader(CgGL.GL_COMPUTE_SHADER, compute);
            } else {
                vert = shader(CgGL.GL_VERTEX_SHADER, vertex);
                frag = shader(CgGL.GL_FRAGMENT_SHADER, fragment);
                // The parts after the two sources are the attribute names, in location order.
                for (int i = 2; i < attributes.length; i++) CgGL.glBindAttribLocation(program, i - 2, attributes[i]);
            }
            if (isolated) {
                CgGL.glProgramParameteri(program, CgGL.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, CgGL.GL_TRUE);
                CgGL.cgLinkIsolatedProgram(program);
            } else {
                CgProgramBinaryCache.retrievable(program, key);
                CgGL.glLinkProgram(program);
            }
        }

        /**
         * Isolated: the program into the shared context, from the binary {@link #finish} read or the one kept under its
         * key. A binary the driver refuses is linked from source here, as the shared worker would have.
         */
        @Override
        protected void land() {
            if (!isolated || error != null) return;
            program = CgGL.glCreateProgram();
            if (binary == null) {
                if (CgProgramBinaryCache.load(program, key)) {
                    names();
                    return;
                }
                // Refused (a driver update): compiled here into the same program, as the shared worker would have
                isolated = false;
                compileAndLink();
                finish();
                return;
            }
            ByteBuffer moved = binary;
            binary = null;
            CgGL.glProgramBinary(program, binaryFormat, moved);
            if (CgGL.glGetProgrami(program, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                // NVIDIA refuses some binaries its own worker context just gave, with an empty log (a depth-only
                // opaque program): linked from source here, as a refused cached binary is above
                CgGL.glDeleteProgram(program);
                program = CgGL.glCreateProgram();
                isolated = false;
                compileAndLink();
                finish();
                return;
            }
            CgProgramBinaryCache.store(key, binaryFormat, moved);
            names();
        }

        /**
         * Every active uniform's location, each array element's included, and each block the sources declare, read here
         * so the render thread's lookups never wait on the driver. The program as the render thread will hold it. Worker.
         */
        private void names() {
            CgGlProgramNames.Names names = new CgGlProgramNames.Names();
            int count = CgGL.glGetProgrami(program, CgGL.GL_ACTIVE_UNIFORMS);
            int maxLength = Math.max(CgGL.glGetProgrami(program, CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH), 256);
            IntBuffer sizeType = CgBufferUtils.createIntBuffer(2);
            for (int i = 0; i < count; i++) {
                sizeType.clear();
                String name = CgGL.glGetActiveUniform(program, i, maxLength, sizeType);
                if (name == null) continue;
                names.uniform(name, CgGL.glGetUniformLocation(program, name));
                int size = sizeType.get(0);
                if (size <= 1 || !name.endsWith("[0]")) continue;
                String base = name.substring(0, name.length() - 3);
                for (int k = 1; k < size; k++) {
                    String element = base + "[" + k + "]";
                    names.uniform(element, CgGL.glGetUniformLocation(program, element));
                }
            }
            for (String source : new String[]{vertex, fragment, compute}) {
                if (source == null) continue;
                for (Matcher m = BLOCK.matcher(source); m.find(); ) {
                    String block = m.group(2);
                    if (m.group(1).equals("uniform")) names.uniformBlock(block, CgGL.glGetUniformBlockIndex(program, block));
                    else if (storage) names.storageBlock(block, CgGL.glGetProgramResourceIndex(program, CgGL.GL_SHADER_STORAGE_BLOCK, block));
                }
            }
            CgGL.cgProgramNames(program, names.complete(storage));
        }

        private int shader(int type, String source) {
            int shader = CgGL.glCreateShader(type);
            String stripped;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STRIP)) {
                stripped = CgGlslStrip.forDriver(source);
            }
            chars += stripped.length();
            CgGL.glShaderSource(shader, stripped);
            CgGL.glCompileShader(shader);
            CgGL.glAttachShader(program, shader);
            return shader;
        }

        @Override
        protected boolean linked() {
            return CgGL.glGetProgrami(program, CgGL.GL_COMPLETION_STATUS_KHR) == CgGL.GL_TRUE;
        }

        @Override
        protected void finish() {
            long began = System.nanoTime();
            try {
                if (comp != 0 && CgGL.glGetShaderi(comp, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                    error = "Compute shader compile failed: " + CgGL.glGetShaderInfoLog(comp, 4096);
                } else if (vert != 0 && CgGL.glGetShaderi(vert, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                    error = "Vertex shader compile failed: " + CgGL.glGetShaderInfoLog(vert, 4096);
                } else if (frag != 0 && CgGL.glGetShaderi(frag, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                    error = "Fragment shader compile failed: " + CgGL.glGetShaderInfoLog(frag, 4096);
                } else if (CgGL.glGetProgrami(program, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                    error = (comp != 0 ? "Compute program" : "Shader program") + " link failed: "
                            + CgGL.glGetProgramInfoLog(program, 4096);
                } else if (isolated) {
                    int length = CgGL.glGetProgrami(program, CgGL.GL_PROGRAM_BINARY_LENGTH);
                    binary = ByteBuffer.allocateDirect(Math.max(length, 0)).order(ByteOrder.nativeOrder());
                    binaryFormat = length > 0 ? CgGL.glGetProgramBinary(program, binary) : 0;
                    if (length <= 0) error = "Shader program gave no binary to move to the render thread's context";
                } else {
                    CgProgramBinaryCache.store(program, key);
                    names();
                }
            } finally {
                for (int shader : new int[]{vert, frag, comp}) {
                    if (shader == 0) continue;
                    CgGL.glDetachShader(program, shader);
                    CgGL.glDeleteShader(shader);
                }
                if (isolated) {
                    CgGL.cgDeleteIsolatedProgram(program);   // this context's name; land() makes the shared one
                    program = 0;
                    vert = frag = comp = 0;
                }
                CgTrace.marker(CgChannels.GL, "shader.link.size", (compute != null ? "c " : "vf ") + chars
                        + " ms " + (System.nanoTime() - began) / 1_000_000 + (label != null ? " " + label : ""));
            }
        }

        @Override
        protected int program() {
            return program;
        }
    }

    /** A submit over one still pending: the earlier one is superseded, and its failure is nobody's to report. */
    private void finishPendingQuietly() {
        try {
            finishLink();
        } catch (IllegalStateException superseded) {
            // Replaced by the submit that called this.
        }
    }

    // ── Uniforms: on the bound program; a location of -1 (absent or optimised out) is skipped ─────────────

    /**
     * The location of a uniform, or -1 when the program has none of that name (or the compiler removed it). Needs
     * a linked program, not a bound one.
     *
     * @throws IllegalArgumentException if {@code name} is null
     */
    public int getUniformLocation(String name) {
        if (name == null) throw new IllegalArgumentException("Uniform name must not be null");
        return CgGL.glGetUniformLocation(programId, name);
    }

    /** Every active uniform after a successful link, {@code gl_*} built-ins excluded. Unmodifiable, never null. */
    public List<CgActiveUniform> getActiveUniforms() {
        int count = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORMS);
        if (count <= 0) return Collections.emptyList();
        int maxLen = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH);
        if (maxLen <= 0) maxLen = 256;

        List<CgActiveUniform> result = new ArrayList<>(count);
        // The LWJGL 2 form: fills sizeTypeBuf[0] = size, [1] = type, and returns the name.
        IntBuffer sizeTypeBuf = CgBufferUtils.createIntBuffer(2);
        for (int i = 0; i < count; i++) {
            sizeTypeBuf.clear();
            String name = CgGL.glGetActiveUniform(programId, i, maxLen, sizeTypeBuf);
            if (name == null || name.startsWith("gl_")) continue;
            result.add(new CgActiveUniform(name, sizeTypeBuf.get(1), sizeTypeBuf.get(0), CgGL.glGetUniformLocation(programId, name)));
        }
        return Collections.unmodifiableList(result);
    }

    public void setUniform1i(int location, int value) {
        CgGL.glUniform1i(location, value);
    }

    public void setUniform1f(int location, float value) {
        CgGL.glUniform1f(location, value);
    }

    public void setUniform2f(int location, float x, float y) {
        CgGL.glUniform2f(location, x, y);
    }

    public void setUniform2f(int location, Vector2f vec) {
        setUniform2f(location, vec.x, vec.y);
    }

    public void setUniform3f(int location, float x, float y, float z) {
        CgGL.glUniform3f(location, x, y, z);
    }

    public void setUniform3f(int location, Vector3f vec) {
        setUniform3f(location, vec.x, vec.y, vec.z);
    }

    public void setUniform4f(int location, float x, float y, float z, float w) {
        CgGL.glUniform4f(location, x, y, z, w);
    }

    public void setUniform4f(int location, Vector4f vec) {
        setUniform4f(location, vec.x, vec.y, vec.z, vec.w);
    }

    /** A sampler uniform set to texture unit {@code textureUnit} (0 is {@code GL_TEXTURE0}); bind the texture there yourself. */
    public void setSampler(int location, int textureUnit) {
        CgGL.glUniform1i(location, textureUnit);
    }

    /** A {@code float[]} uniform, one element per buffer element; not a {@code vec} or {@code mat} array. */
    public void setUniformFloatBuffer(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    /** An {@code int[]} uniform, one element per buffer element; not an {@code ivec} array. */
    public void setUniformIntBuffer(int location, IntBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    /** A {@code mat3} from 9 column-major floats in a direct buffer. */
    public void setUniformMatrix3f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix3(location, false, buffer);
    }

    public void setUniformMatrix3f(int location, Matrix3f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix3(location, false, buf);
    }

    /** A {@code mat4} from 16 column-major floats in a direct buffer. */
    public void setUniformMatrix4f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix4(location, false, buffer);
    }

    public void setUniformMatrix4f(int location, Matrix4f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix4(location, false, buf);
    }
}
