package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexAttribute;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL20;

/**
 * Instanced vertex array binding that owns a new VAO and instance stream buffer,
 * while borrowing the base geometry VBO from a parent {@link CgVertexArrayBinding}.
 *
 * <p><strong>Ownership invariant</strong>: This binding creates exactly one new VAO
 * and one instance stream buffer. It must never bind, delete, or mutate the base
 * {@link CgVertexArrayBinding}'s VAO or stream buffer. The base VBO is borrowed
 * (its GL id is read) only during initial attribute pointer setup on the new VAO.</p>
 *
 * <h3>VAO attribute layout</h3>
 * <ul>
 *   <li>Slots {@code 0 .. baseCount-1}: base vertex attributes, divisor 0</li>
 *   <li>Slots {@code baseCount .. baseCount+instanceCount-1}: instance attributes, divisor 1</li>
 * </ul>
 *
 * <h3>Cleanup order</h3>
 * <p>{@link CgInstancedVertexArrayRegistry#deleteAll()} must be called
 * <em>before</em> {@link CgVertexArrayRegistry#deleteAll()} at context teardown.
 * Deleting the base binding while an instanced binding still references its VBO
 * produces a dangling GL reference.</p>
 */
public final class CgInstancedVertexArrayBinding {

    @Getter
    private final CgVertexFormat baseFormat;
    @Getter
    private final CgInstanceLayout instanceLayout;

    private final CgVertexArrayBinding baseBinding;
    private final int baseBindingGeneration;
    @Getter
    private final CgStreamBuffer instanceStreamBuffer;
    @Getter
    private final CgVertexArray vertexArray;

    private int currentBaseDataOffset = -1;
    private int currentInstanceDataOffset = -1;

    private static final int INITIAL_INSTANCES = 256;

    /**
     * Creates a new instanced VAO binding.
     *
     * @throws UnsupportedOperationException if instancing is not supported on this hardware
     * @throws IllegalArgumentException if the combined attribute count exceeds GL_MAX_VERTEX_ATTRIBS
     */
    public static CgInstancedVertexArrayBinding create(CgVertexFormat baseFormat,
                                                        CgInstanceLayout instanceLayout) {
        CgInstancingSupport.requireSupported();
        CgInstancingSupport.validateAttributeSlots(baseFormat, instanceLayout);

        CgVertexArrayBinding baseBinding = CgVertexArrayRegistry.get().getOrCreate(baseFormat);

        CgVertexArray newVao = CgVertexArray.create();
        newVao.bind();

        // Configure base attributes on the new VAO by temporarily binding the base VBO.
        // We are NOT touching the base VAO — we only read the base VBO's GL id.
        baseBinding.getStreamBuffer().bind();
        int baseCount = baseFormat.getAttributeCount();
        for (int i = 0; i < baseCount; i++) {
            CgVertexAttribute attr = baseFormat.getAttribute(i);
            GL20.glVertexAttribPointer(i, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), baseFormat.getStride(), attr.getOffset());
            GL20.glEnableVertexAttribArray(i);
            CgInstancingSupport.vertexAttribDivisor(i, 0);
        }
        baseBinding.getStreamBuffer().unbind();

        // Create and configure instance stream buffer on the new VAO.
        CgStreamBuffer instanceStreamBuffer = CgStreamBuffer.create(
                instanceLayout.getStride() * INITIAL_INSTANCES);
        instanceStreamBuffer.bind();
        int instanceCount = instanceLayout.getAttributeCount();
        for (int i = 0; i < instanceCount; i++) {
            int slot = baseCount + i;
            CgVertexAttribute attr = instanceLayout.getAttribute(i);
            GL20.glVertexAttribPointer(slot, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), instanceLayout.getStride(), attr.getOffset());
            GL20.glEnableVertexAttribArray(slot);
            CgInstancingSupport.vertexAttribDivisor(slot, instanceLayout.getDivisor());
        }
        instanceStreamBuffer.unbind();

        newVao.unbind();

        return new CgInstancedVertexArrayBinding(baseFormat, instanceLayout, baseBinding,
                instanceStreamBuffer, newVao);
    }

    private CgInstancedVertexArrayBinding(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout,
                                           CgVertexArrayBinding baseBinding,
                                           CgStreamBuffer instanceStreamBuffer,
                                           CgVertexArray vertexArray) {
        this.baseFormat = baseFormat;
        this.instanceLayout = instanceLayout;
        this.baseBinding = baseBinding;
        this.baseBindingGeneration = baseBinding.getGeneration();
        this.instanceStreamBuffer = instanceStreamBuffer;
        this.vertexArray = vertexArray;
    }

    /**
     * Returns the borrowed base-geometry stream buffer.
     *
     * <p>The instanced binding does not own this buffer; ownership remains with
     * {@link CgVertexArrayBinding}. This accessor exists so instanced renderers
     * do not need to independently resolve or store the parent binding.</p>
     */
    public CgStreamBuffer getBaseStreamBuffer() {
        return baseBinding.getStreamBuffer();
    }

    /**
     * Re-issues base attribute pointers on this VAO if the data offset changed.
     * Must be called while this instanced VAO is bound.
     */
    public void rebindBasePointersIfNeeded(int dataOffset) {
        if (dataOffset == currentBaseDataOffset) return;
        baseBinding.getStreamBuffer().bind();
        int baseCount = baseFormat.getAttributeCount();
        for (int i = 0; i < baseCount; i++) {
            CgVertexAttribute attr = baseFormat.getAttribute(i);
            GL20.glVertexAttribPointer(i, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), baseFormat.getStride(), dataOffset + attr.getOffset());
        }
        baseBinding.getStreamBuffer().unbind();
        currentBaseDataOffset = dataOffset;
    }

    /**
     * Re-issues instance attribute pointers on this VAO if the data offset changed.
     * Must be called while this instanced VAO is bound.
     */
    public void rebindInstancePointersIfNeeded(int dataOffset) {
        if (dataOffset == currentInstanceDataOffset) return;
        instanceStreamBuffer.bind();
        int baseCount = baseFormat.getAttributeCount();
        int instanceCount = instanceLayout.getAttributeCount();
        for (int i = 0; i < instanceCount; i++) {
            int slot = baseCount + i;
            CgVertexAttribute attr = instanceLayout.getAttribute(i);
            GL20.glVertexAttribPointer(slot, attr.getComponents(), attr.getType().getGlConstant(),
                    attr.isNormalized(), instanceLayout.getStride(), dataOffset + attr.getOffset());
        }
        instanceStreamBuffer.unbind();
        currentInstanceDataOffset = dataOffset;
    }

    /**
     * Throws {@link IllegalStateException} if the parent base binding has been deleted
     * and its generation no longer matches the snapshot taken at creation time.
     */
    public void validateParentGeneration() {
        if (baseBinding.getGeneration() != baseBindingGeneration) {
            throw new IllegalStateException(
                "Base binding for format '" + baseFormat.getDebugName()
                + "' was deleted after instanced binding was created. "
                + "Ensure CgInstancedVertexArrayRegistry.deleteAll() is called before CgVertexArrayRegistry.deleteAll().");
        }
    }

    /**
     * Deletes only the owned instanced VAO and instance stream buffer.
     * The base binding's VAO and stream buffer are NOT deleted here.
     */
    public void delete() {
        vertexArray.delete();
        instanceStreamBuffer.delete();
    }
}
