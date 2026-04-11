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
 * bind/unbind, and deletion across core and ARB VAO paths.
 */
public final class CgVertexArray {

    private static Boolean useCore;

    @Getter
    private final int vaoId;

    private CgVertexArray(int vaoId) {
        this.vaoId = vaoId;
    }

    public static CgVertexArray create() {
        if (!isCore()) throw new IllegalStateException("VAO support is required for CgVertexArray");

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

    private static int gen() {
        if (isCore()) return GL30.glGenVertexArrays();
        return ARBVertexArrayObject.glGenVertexArrays();
    }

    public static void bind(int vao) {
        if (isCore()) GL30.glBindVertexArray(vao);
        else ARBVertexArrayObject.glBindVertexArray(vao);
    }

    public static void delete(int vao) {
        if (isCore()) GL30.glDeleteVertexArrays(vao);
        else ARBVertexArrayObject.glDeleteVertexArrays(vao);
    }

    private static boolean isCore() {
        if (useCore == null) useCore = CgCapabilities.detect().isVaoSupported() && GLContext.getCapabilities().OpenGL30;
        return useCore;
    }
}
