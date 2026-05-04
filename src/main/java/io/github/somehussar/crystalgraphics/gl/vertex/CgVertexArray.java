package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexAttribute;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;

import lombok.Getter;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

/**
 * VAO wrapper that owns vertex array creation, attribute pointer setup,
 * bind/unbind, and deletion across core GL30 and ARB VAO paths.
 *
 * <h3>Core vs ARB path selection</h3>
 * <p>{@link #useCore} is a lazy one-shot cache: on the first VAO operation it
 * detects whether the current GL context supports Core GL 3.0 VAOs
 * ({@code OpenGL30 == true}). If so, the {@link GL30} entry points are used;
 * otherwise the {@link ARBVertexArrayObject} entry points are used. Both
 * entry-point sets produce identical VAO semantics — the selection is purely
 * an API routing decision.</p>
 *
 * <p>ARB-only hardware (e.g. some older Intel / AMD GPUs that expose
 * {@code GL_ARB_vertex_array_object} but do not report {@code OpenGL30})
 * is fully supported because {@link CgCapabilities#isVaoSupported()} checks
 * for either extension, while {@code isCore()} only returns {@code true} when
 * the full GL 3.0 core profile is present.</p>
 *
 * <h3>Resetting the cache on context recreation</h3>
 * <p>Call {@link #resetCoreCache()} when the GL context is destroyed and
 * recreated so the next VAO operation re-probes the new context's capabilities.
 * This is called automatically by {@link io.github.somehussar.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle#destroyContext()}.</p>
 */
public final class CgVertexArray {

    /**
     * Lazy one-shot cache for the GL30-core VAO dispatch path.
     *
     * <ul>
     *   <li>{@code null} — not yet detected; next call to {@link #isCore()} will probe the context.</li>
     *   <li>{@code true} — GL 3.0 core profile VAOs available; use {@link GL30} entry points.</li>
     *   <li>{@code false} — GL 3.0 not available; use {@link ARBVertexArrayObject} entry points.</li>
     * </ul>
     *
     * <p>This field must be reset to {@code null} via {@link #resetCoreCache()} whenever
     * the GL context is destroyed and recreated.</p>
     */
    private static Boolean useCore;

    @Getter
    private final int vaoId;

    private CgVertexArray(int vaoId) {
        this.vaoId = vaoId;
    }

    /**
     * Creates a new {@link CgVertexArray}, using the core GL30 path if available or
     * the ARB path as a fallback.
     *
     * @return a new VAO wrapper
     * @throws IllegalStateException if neither Core GL30 nor ARB VAO support is available
     */
    public static CgVertexArray create() {
        if (!CgCapabilities.detect().isVaoSupported()) {
            throw new IllegalStateException("VAO support is required for CgVertexArray (neither GL30 nor ARB_vertex_array_object is available)");
        }
        return new CgVertexArray(gen());
    }

    public void bind() {
        bind(vaoId);
    }

    public void unbind() {
        bind(0);
    }

    public void delete() {
        delete(vaoId);
    }

    public void configure(CgVertexFormat format) {
        bind();
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute attr = format.getAttribute(i);
            GL20.glVertexAttribPointer(
                    i,
                    attr.getComponents(),
                    attr.getType().getGlConstant(),
                    attr.isNormalized(),
                    format.getStride(),
                    attr.getOffset()
            );
            GL20.glEnableVertexAttribArray(i);
        }
    }

    /**
     * Re-issues glVertexAttribPointer with a new base offset. Does NOT re-enable
     * attribute arrays (they're already enabled and stored in VAO state from configure()).
     * This is the fast path after each stream buffer commit in the sync ring-buffer.
     */
    public void reconfigureWithOffset(CgVertexFormat format, int dataOffset) {
        bind();
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute attr = format.getAttribute(i);
            GL20.glVertexAttribPointer(
                    i,
                    attr.getComponents(),
                    attr.getType().getGlConstant(),
                    attr.isNormalized(),
                    format.getStride(),
                    dataOffset + attr.getOffset()
            );
        }
    }

    /**
     * Generates a raw VAO id using the core or ARB path, whichever is available.
     *
     * <p>Uses {@link GL30#glGenVertexArrays()} when {@link #isCore()} is true,
     * otherwise falls back to {@link ARBVertexArrayObject#glGenVertexArrays()}.</p>
     */
    private static int gen() {
        if (isCore()) return GL30.glGenVertexArrays();
        return ARBVertexArrayObject.glGenVertexArrays();
    }

    /**
     * Generates and returns a raw VAO id without wrapping it in a {@link CgVertexArray} object.
     *
     * <p>Used by classes that manage their own VAO lifecycle (e.g. {@code CgMesh},
      * {@code CgInstanceVertexArrayBinding}) and need a raw id instead of an owned wrapper.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @return the generated VAO id
     * @throws IllegalStateException if neither GL30 nor ARB VAO support is available
     */
    public static int createRawVaoId() {
        if (!CgCapabilities.detect().isVaoSupported()) {
            throw new IllegalStateException("VAO support is required for CgVertexArray.createRawVaoId() (neither GL30 nor ARB_vertex_array_object is available)");
        }
        return gen();
    }

    /**
     * Deletes a raw VAO id. Counterpart to {@link #createRawVaoId()}.
     *
     * <p>Routes to the same GL30 or ARB path that was used to create the VAO.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param vaoId the VAO id to delete (from a prior {@link #createRawVaoId()} call)
     */
    public static void deleteRaw(int vaoId) {
        delete(vaoId);
    }

    /**
     * Binds a VAO id using the appropriate GL30 or ARB entry point.
     *
     * <p>Pass {@code 0} to unbind (restore default VAO state).</p>
     *
     * @param vao VAO id to bind, or {@code 0} to unbind
     */
    public static void bind(int vao) {
        if (isCore()) GL30.glBindVertexArray(vao);
        else ARBVertexArrayObject.glBindVertexArray(vao);
    }

    /**
     * Deletes a VAO id using the appropriate GL30 or ARB entry point.
     *
     * @param vao VAO id to delete
     */
    public static void delete(int vao) {
        if (isCore()) GL30.glDeleteVertexArrays(vao);
        else ARBVertexArrayObject.glDeleteVertexArrays(vao);
    }

    /**
     * Returns {@code true} if the Core GL 3.0 VAO path should be used.
     *
     * <p>When {@code true}, {@link GL30} entry points are used.
     * When {@code false}, {@link ARBVertexArrayObject} entry points are used.
     * The result is cached after the first call. Reset via {@link #resetCoreCache()}
     * on context recreation.</p>
     */
    private static boolean isCore() {
        if (useCore == null) useCore = GLContext.getCapabilities().OpenGL30;
        return useCore;
    }

    /**
     * Resets the cached core/ARB dispatch flag.
     *
     * <p>Must be called when the GL context is destroyed and recreated so that
     * the next VAO operation re-probes the new context's capabilities.
     * Called automatically by {@code CgGraphicsLifecycle.destroyContext()}.</p>
     */
    public static void resetCoreCache() {
        useCore = null;
    }
}
