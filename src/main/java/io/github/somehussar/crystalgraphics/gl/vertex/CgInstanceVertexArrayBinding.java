package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.vertex.CgAttributeFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexAttribute;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import lombok.Getter;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/**
 * Instanced VAO binding keyed by (base source identity, instance source identity).
 *
 * <p>Owns only the VAO id. The base stream buffer and instance stream buffer
 * are borrowed — never deleted from here.</p>
 *
 * <h3>Two factory modes</h3>
 * <ul>
 *   <li>{@link #createStreaming} — streaming base VBO from a {@link CgVertexBuffer};
 *       base attribute pointers may change each frame as the sync-ring slot advances.
 *       No {@link CgVertexArrayBinding} is involved — the base stream is fetched
 *       directly, avoiding a wasted non-instanced VAO creation.</li>
 *   <li>{@link #createMeshInstanced} — static base VBO from a {@link CgMesh};
 *       base pointers are always at offset 0 and never need rebinding.</li>
 * </ul>
 *
 * <h3>Attribute slot layout</h3>
 * <p>Base vertex attributes occupy slots {@code 0..baseCount-1} with divisor {@code 0}.
 * Instance attributes occupy slots {@code baseCount..baseCount+instanceCount-1} with divisor {@code 1}.
 * This layout is set up in {@link #setupInstanceAttribs(int, CgInstanceVertexBuffer)}.</p>
 *
 * <h3>Cleanup</h3>
 * <p>{@link #delete()} removes only the owned VAO. It does NOT delete any VBOs.</p>
 *
 * <h3>Instancing support utilities</h3>
 * <p>This class also hosts static instancing capability helpers previously in
 * {@code CgInstancingSupport}: {@link #isSupported()}, {@link #requireSupported()},
 * {@link #validateAttributeSlots(CgVertexFormat, CgInstanceFormat)},
 * {@link #vertexAttribDivisor(int, int)}, and {@link #resetCoreCache()}.</p>
 */
public final class CgInstanceVertexArrayBinding {

    /** Raw GL VAO id owned by this binding. */
    @Getter
    private final int glVao;

    /**
     * Borrowed base stream VBO (streaming path only; {@code null} for mesh-based path).
     * Not owned — do NOT call delete() on this.
     */
    private final CgVertexBuffer baseStream;

    /**
     * Attribute layout of the base source (streaming or mesh).
     * Used for pointer rebinding and for computing the instance attribute start slot.
     */
    private final CgAttributeFormat baseLayout;

    /**
     * Borrowed instance stream VBO. Not owned — do NOT call delete() on this.
     */
    private final CgInstanceVertexBuffer instanceStream;

    /**
     * {@code true} for the mesh-instanced path; {@code false} for streaming.
     * When {@code true}, base pointers are fixed at offset 0 and
     * {@link #rebindBasePointersIfNeeded(int)} is a no-op.
     */
    private final boolean meshBased;

    /**
     * The base data offset currently encoded in the VAO's attribute pointers.
     * Initialized to {@code -1} to force a rebind on the first frame.
     * Updated by {@link #rebindBasePointersIfNeeded(int)}.
     */
    private int currentBaseOffset = -1;

    /**
     * The instance data offset currently encoded in the VAO's attribute pointers.
     * Initialized to {@code -1} to force a rebind on the first frame.
     * Updated by {@link #rebindInstancePointersIfNeeded(int)}.
     */
    private int currentInstanceOffset = -1;

    private CgInstanceVertexArrayBinding(int glVao,
                                   CgVertexBuffer baseStream,
                                   CgAttributeFormat baseLayout,
                                   CgInstanceVertexBuffer instanceStream,
                                   boolean meshBased) {
        this.glVao = glVao;
        this.baseStream = baseStream;
        this.baseLayout = baseLayout;
        this.instanceStream = instanceStream;
        this.meshBased = meshBased;
    }

    /**
     * Creates an instanced VAO for a streaming base vertex buffer.
     *
     * <p>Fetches the base VBO directly from {@code base} — no non-instanced
     * {@link CgVertexArrayBinding} is created or consulted.</p>
     *
     * @param base     the shared base stream (VBO only)
     * @param instance the shared instance stream
     * @return a new instanced VAO binding
     */
    public static CgInstanceVertexArrayBinding createStreaming(CgVertexBuffer base,
                                                         CgInstanceVertexBuffer instance) {
        requireSupported();

        int vao = CgVertexArray.createRawVaoId();
        CgVertexArray.bind(vao);

        CgAttributeFormat baseLayout = base.getFormat();
        base.getStreamBuffer().bind();
        for (int i = 0; i < baseLayout.getAttributeCount(); i++) {
            CgVertexAttribute attr = baseLayout.getAttribute(i);
            GL20.glVertexAttribPointer(i, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), baseLayout.getStride(), attr.getOffset());
            GL20.glEnableVertexAttribArray(i);
            vertexAttribDivisor(i, 0);
        }
        base.getStreamBuffer().unbind();

        setupInstanceAttribs(baseLayout.getAttributeCount(), instance);

        CgVertexArray.bind(0);

        return new CgInstanceVertexArrayBinding(vao, base, baseLayout, instance, false);
    }

    /**
     * Creates an instanced VAO for a static mesh base VBO.
     *
     * <p>Base pointers are set at offset 0 and never need rebinding.</p>
     *
     * @param mesh     the static mesh (VBO/IBO are borrowed, not owned)
     * @param instance the shared instance stream
     * @return a new instanced VAO binding
     */
    public static CgInstanceVertexArrayBinding createMeshInstanced(CgMesh mesh,
                                                              CgInstanceVertexBuffer instance) {
        requireSupported();

        int vao = CgVertexArray.createRawVaoId();
        CgVertexArray.bind(vao);

        CgAttributeFormat baseLayout = mesh.getFormat();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.getGlVertexBuffer());
        for (int i = 0; i < baseLayout.getAttributeCount(); i++) {
            CgVertexAttribute attr = baseLayout.getAttribute(i);
            GL20.glVertexAttribPointer(i, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), baseLayout.getStride(), attr.getOffset());
            GL20.glEnableVertexAttribArray(i);
            vertexAttribDivisor(i, 0);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        if (mesh.getGlIndexBuffer() != 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.getGlIndexBuffer());
        }

        setupInstanceAttribs(baseLayout.getAttributeCount(), instance);

        CgVertexArray.bind(0);
        if (mesh.getGlIndexBuffer() != 0) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        return new CgInstanceVertexArrayBinding(vao, null, baseLayout, instance, true);
    }

    /**
     * Sets up instance attribute pointers starting at slot {@code baseCount}.
     *
     * <p>For each attribute in the instance layout, assigns the attribute pointer
     * at slot {@code baseCount + i} with divisor {@code instanceFormat.getDivisor()} (always 1 in v1).
     * The VBO must already be bound (done by this method via {@code instance.getStreamBuffer().bind()}).
     * Called during VAO construction only — not per-frame.</p>
     *
     * @param baseCount number of base vertex attributes (determines where instance slots start)
     * @param instance  the instance stream VBO providing the buffer and layout
     */
    private static void setupInstanceAttribs(int baseCount, CgInstanceVertexBuffer instance) {
        CgAttributeFormat instLayout = instance.getLayout();
        instance.getStreamBuffer().bind();
        for (int i = 0; i < instLayout.getAttributeCount(); i++) {
            int slot = baseCount + i;
            CgVertexAttribute attr = instLayout.getAttribute(i);
            GL20.glVertexAttribPointer(slot, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), instLayout.getStride(), attr.getOffset());
            GL20.glEnableVertexAttribArray(slot);
            vertexAttribDivisor(slot, instance.getLayout().getDivisor());
        }
        instance.getStreamBuffer().unbind();
    }

    /**
     * Re-issues base attribute pointers at the new byte offset, if changed.
     *
     * <p><strong>Requires the target VAO to be already bound</strong> before calling —
     * {@code glVertexAttribPointer} writes into the currently-bound VAO state. Binds
     * the base stream VBO temporarily to capture the new offset, then unbinds it.</p>
     *
     * <p>No-op when {@code meshBased == true} (mesh base pointers are fixed at offset 0)
     * or when {@code offset == currentBaseOffset} (lazy skip to avoid redundant GL calls).</p>
     *
     * @param offset byte offset into the base stream VBO where vertex data starts
     */
    public void rebindBasePointersIfNeeded(int offset) {
        if (meshBased || offset == currentBaseOffset) return;
        baseStream.getStreamBuffer().bind();
        for (int i = 0; i < baseLayout.getAttributeCount(); i++) {
            CgVertexAttribute attr = baseLayout.getAttribute(i);
            GL20.glVertexAttribPointer(i, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), baseLayout.getStride(), offset + attr.getOffset());
        }
        baseStream.getStreamBuffer().unbind();
        currentBaseOffset = offset;
    }

    /**
     * Re-issues instance attribute pointers at the new byte offset, if changed.
     *
     * <p><strong>Requires the target VAO to be already bound</strong> before calling —
     * {@code glVertexAttribPointer} writes into the currently-bound VAO state. Binds
     * the instance stream VBO temporarily to capture the new offset, then unbinds it.</p>
     *
     * <p>No-op when {@code offset == currentInstanceOffset} (lazy skip to avoid redundant GL calls).</p>
     *
     * @param offset byte offset into the instance stream VBO where per-instance data starts
     */
    public void rebindInstancePointersIfNeeded(int offset) {
        if (offset == currentInstanceOffset) return;
        CgAttributeFormat instLayout = instanceStream.getLayout();
        int baseCount = baseLayout.getAttributeCount();
        instanceStream.getStreamBuffer().bind();
        for (int i = 0; i < instLayout.getAttributeCount(); i++) {
            int slot = baseCount + i;
            CgVertexAttribute attr = instLayout.getAttribute(i);
            GL20.glVertexAttribPointer(slot, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), instLayout.getStride(), offset + attr.getOffset());
        }
        instanceStream.getStreamBuffer().unbind();
        currentInstanceOffset = offset;
    }

    /**
     * Deletes the owned VAO only.
     *
     * <p>Does NOT delete any VBOs — those are owned by their respective stream registries.</p>
     */
    public void delete() {
        CgVertexArray.deleteRaw(glVao);
    }

    // ── Instancing support (absorbed from CgInstancingSupport) ────────────────

    /**
     * Lazy one-shot cache for the GL33 core divisor path.
     * {@code null} = not yet detected; {@code true/false} = cached result.
     */
    private static Boolean useGL33 = null;

    /** Returns true if both draw-instanced and vertex-attrib-divisor are available. */
    public static boolean isSupported() {
        return isSupported(CgCapabilities.detect());
    }

    /**
     * Returns true if both draw-instanced and vertex-attrib-divisor are available
     * in the given capabilities snapshot.
     */
    public static boolean isSupported(CgCapabilities caps) {
        return caps.isDrawInstancedSupported() && caps.isVertexAttribDivisorSupported();
    }

    /**
     * Throws {@link UnsupportedOperationException} if instancing is not fully supported,
     * listing both missing capabilities in the message.
     */
    public static void requireSupported() {
        CgCapabilities caps = CgCapabilities.detect();
        if (!caps.isDrawInstancedSupported() || !caps.isVertexAttribDivisorSupported()) {
            throw new UnsupportedOperationException(
                "Instancing not fully supported: drawInstanced=" + caps.isDrawInstancedSupported()
                + ", vertexAttribDivisor=" + caps.isVertexAttribDivisorSupported());
        }
    }

    /** Returns GL_MAX_VERTEX_ATTRIBS from the detected capabilities. */
    public static int getMaxVertexAttribs() {
        return CgCapabilities.detect().getMaxVertexAttribs();
    }

    /**
     * Validates that the combined attribute slot count of {@code baseFormat} and
     * {@code instanceFormat} does not exceed the detected {@code GL_MAX_VERTEX_ATTRIBS}.
     *
     * @throws IllegalArgumentException if the combined count exceeds the limit
     */
    public static void validateAttributeSlots(CgVertexFormat baseFormat, CgInstanceFormat instanceFormat) {
        validateAttributeSlots(baseFormat, instanceFormat, CgCapabilities.detect().getMaxVertexAttribs());
    }

    /**
     * Validates combined attribute slots against an explicit maximum (test-friendly overload).
     *
     * @throws IllegalArgumentException if the combined count exceeds maxVertexAttribs
     */
    public static void validateAttributeSlots(CgVertexFormat baseFormat, CgInstanceFormat instanceFormat,
                                               int maxVertexAttribs) {
        int total = baseFormat.getAttributeCount() + instanceFormat.getAttributeCount();
        if (total > maxVertexAttribs) {
            throw new IllegalArgumentException(
                "Combined attribute count " + total + " (base=" + baseFormat.getAttributeCount()
                + " + instance=" + instanceFormat.getAttributeCount()
                + ") exceeds GL_MAX_VERTEX_ATTRIBS=" + maxVertexAttribs);
        }
    }

    /**
     * Resets the cached GL33 core/ARB dispatch flag.
     *
     * <p>Must be called when the GL context is destroyed and recreated so that
     * the next {@link #vertexAttribDivisor} call re-probes the new context's capabilities.
     * Called automatically by {@code CgGraphicsLifecycle.destroyContext()}.</p>
     */
    public static void resetCoreCache() {
        useGL33 = null;
    }

    /**
     * Issues {@code glVertexAttribDivisor} via GL 3.3 core or ARB_instanced_arrays path.
     * Must be called while the target VAO is bound.
     *
     * <p>The GL33 vs ARB decision is cached on the first call (one-shot lazy detection).</p>
     */
    public static void vertexAttribDivisor(int slot, int divisor) {
        if (useGL33 == null) {
            useGL33 = GLContext.getCapabilities().OpenGL33;
        }
        if (useGL33) {
            GL33.glVertexAttribDivisor(slot, divisor);
        } else {
            ARBInstancedArrays.glVertexAttribDivisorARB(slot, divisor);
        }
    }
}
