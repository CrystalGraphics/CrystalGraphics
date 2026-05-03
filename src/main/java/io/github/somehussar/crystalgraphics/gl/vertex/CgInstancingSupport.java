package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

/**
 * Utility class for instancing capability detection, validation, and GL dispatch.
 *
 * <p>All methods are static. The public {@link #isSupported()} variant probes the
 * current GL context; the overload {@link #isSupported(CgCapabilities)} accepts a
 * pre-built capabilities snapshot and is safe to call without a GL context (tests).</p>
 *
 * <p>Both instanced draw calls AND per-attribute divisors must be available for
 * instancing to be considered supported. Partial support is explicitly rejected.</p>
 */
public final class CgInstancingSupport {

    private CgInstancingSupport() {
        throw new AssertionError();
    }

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
     * {@code instanceLayout} does not exceed the detected {@code GL_MAX_VERTEX_ATTRIBS}.
     *
     * @throws IllegalArgumentException if the combined count exceeds the limit
     */
    public static void validateAttributeSlots(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout) {
        validateAttributeSlots(baseFormat, instanceLayout, CgCapabilities.detect().getMaxVertexAttribs());
    }

    /**
     * Validates combined attribute slots against an explicit maximum (test-friendly overload).
     *
     * @throws IllegalArgumentException if the combined count exceeds maxVertexAttribs
     */
    public static void validateAttributeSlots(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout,
                                               int maxVertexAttribs) {
        int total = baseFormat.getAttributeCount() + instanceLayout.getAttributeCount();
        if (total > maxVertexAttribs) {
            throw new IllegalArgumentException(
                "Combined attribute count " + total + " (base=" + baseFormat.getAttributeCount()
                + " + instance=" + instanceLayout.getAttributeCount()
                + ") exceeds GL_MAX_VERTEX_ATTRIBS=" + maxVertexAttribs);
        }
    }

    /**
     * Issues {@code glVertexAttribDivisor} via GL 3.3 core or ARB_instanced_arrays path.
     * Must be called while the target VAO is bound.
     */
    public static void vertexAttribDivisor(int slot, int divisor) {
        if (GLContext.getCapabilities().OpenGL33) {
            GL33.glVertexAttribDivisor(slot, divisor);
        } else {
            ARBInstancedArrays.glVertexAttribDivisorARB(slot, divisor);
        }
    }
}
