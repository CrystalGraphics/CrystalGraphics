package io.github.somehussar.crystalgraphics.api.vertex;

import org.lwjgl.opengl.GL11;

/**
 * Enumerates the primitive data types available for vertex attributes.
 *
 * <p>Each constant carries the corresponding OpenGL type constant and byte size,
 * so attribute layout computation can stay in pure Java without GL calls.</p>
 */
public enum CgAttribType {

    FLOAT(GL11.GL_FLOAT, 4),
    UNSIGNED_BYTE(GL11.GL_UNSIGNED_BYTE, 1),
    BYTE(GL11.GL_BYTE, 1),
    SHORT(GL11.GL_SHORT, 2),
    UNSIGNED_SHORT(GL11.GL_UNSIGNED_SHORT, 2),
    INT(GL11.GL_INT, 4),
    UNSIGNED_INT(GL11.GL_UNSIGNED_INT, 4);

    private final int glConstant;
    private final int byteSize;

    CgAttribType(int glConstant, int byteSize) {
        this.glConstant = glConstant;
        this.byteSize = byteSize;
    }

    /** Returns the OpenGL type constant (e.g. {@code GL_FLOAT}). */
    public int getGlConstant() {
        return glConstant;
    }

    /** Returns the byte size of one component of this type. */
    public int getByteSize() {
        return byteSize;
    }
}
