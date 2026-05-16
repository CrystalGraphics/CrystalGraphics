package com.crystalgraphics.api.vertex;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Enumerates the primitive data types available for vertex attributes.
 *
 * <p>Each constant carries the corresponding OpenGL type constant and byte size,
 * so attribute layout computation can stay in pure Java without GL calls.</p>
 */
public enum CgAttribType {

    FLOAT(CgGL.GL_FLOAT, 4),
    UNSIGNED_BYTE(CgGL.GL_UNSIGNED_BYTE, 1),
    BYTE(CgGL.GL_BYTE, 1),
    SHORT(CgGL.GL_SHORT, 2),
    UNSIGNED_SHORT(CgGL.GL_UNSIGNED_SHORT, 2),
    INT(CgGL.GL_INT, 4),
    UNSIGNED_INT(CgGL.GL_UNSIGNED_INT, 4);

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
