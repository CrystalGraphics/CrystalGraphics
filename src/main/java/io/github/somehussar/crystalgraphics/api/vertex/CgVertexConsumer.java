package io.github.somehussar.crystalgraphics.api.vertex;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Format-agnostic vertex attribute writer inspired by 1.20.1's
 * {@code VertexConsumer} but adapted for CrystalGraphics' architecture.
 *
 * <p>Provides a fluent chaining API for writing interleaved vertex
 * attributes one at a time. Each attribute setter returns {@code this}
 * for chaining. Call {@link #endVertex()} after all attributes for a
 * single vertex have been written.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * consumer.vertex(x, y).uv(u, v).color(r, g, b, a).endVertex();
 * consumer.vertex(x2, y2).uv(u2, v2).color(r, g, b, a).endVertex();
 * }</pre>
 *
 * <p>Implementations are free to ignore attribute calls that don't match
 * their backing vertex format. For example, calling {@link #normal(float, float, float)}
 * on a consumer backed by {@link CgVertexFormat#POS2_UV2_COL4UB} may
 * silently no-op.</p>
 *
 * <p>This interface lives in the public API ({@code api/vertex/}) because
 * it is the shared contract consumed by all renderers and batchers. Concrete
 * implementations live in {@code gl/batch/} or similar internal packages.</p>
 *
 * @see CgVertexFormat
 */
public interface CgVertexConsumer {

    // ── Format ──────────────────────────────────────────────────────────

    /**
     * Returns the vertex format this consumer writes.
     *
     * @return the backing vertex format (never null)
     */
    CgVertexFormat format();

    // ── Position ────────────────────────────────────────────────────────

    /**
     * Sets the position for the current vertex (2D).
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @return this consumer for chaining
     */
    CgVertexConsumer vertex(float x, float y);

    /**
     * Sets the position for the current vertex (3D).
     *
     * <p>Default implementation delegates to {@link #vertex(float, float)}
     * and discards the Z component, which is correct for 2D-only formats.</p>
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return this consumer for chaining
     */
    CgVertexConsumer vertex(float x, float y, float z);

    // ── Texture coordinates ─────────────────────────────────────────────

    /**
     * Sets the texture coordinates for the current vertex.
     *
     * @param u U texture coordinate
     * @param v V texture coordinate
     * @return this consumer for chaining
     */
    CgVertexConsumer uv(float u, float v);

    // ── Color ───────────────────────────────────────────────────────────

    /**
     * Sets the color for the current vertex using byte-range components.
     *
     * @param r red   (0-255)
     * @param g green (0-255)
     * @param b blue  (0-255)
     * @param a alpha (0-255)
     * @return this consumer for chaining
     */
    CgVertexConsumer color(int r, int g, int b, int a);

    /**
     * Sets the color for the current vertex using float-range components.
     *
     * @param r red   (0.0-1.0)
     * @param g green (0.0-1.0)
     * @param b blue  (0.0-1.0)
     * @param a alpha (0.0-1.0)
     * @return this consumer for chaining
     */
    default CgVertexConsumer color(float r, float g, float b, float a) {
        return color((int) (r * 255.0f), (int) (g * 255.0f), (int) (b * 255.0f), (int) (a * 255.0f));
    }

    /**
     * Sets the color from a packed RGBA integer (0xRRGGBBAA).
     *
     * @param rgba packed color
     * @return this consumer for chaining
     */
    default CgVertexConsumer colorRgba(int rgba) {
        return color(
                (rgba >>> 24) & 0xFF,
                (rgba >>> 16) & 0xFF,
                (rgba >>> 8) & 0xFF,
                rgba & 0xFF
        );
    }

    /**
     * Sets the color from a packed ARGB integer (0xAARRGGBB).
     *
     * @param argb packed color
     * @return this consumer for chaining
     */
    default CgVertexConsumer colorArgb(int argb) {
        return color(
                (argb >>> 16) & 0xFF,
                (argb >>> 8) & 0xFF,
                argb & 0xFF,
                (argb >>> 24) & 0xFF
        );
    }

    // ── Normal ──────────────────────────────────────────────────────────

    /**
     * Sets the normal for the current vertex.
     *
     * <p>Default implementation is a no-op, since many 2D formats
     * do not include normals.</p>
     *
     * @param nx normal X component
     * @param ny normal Y component
     * @param nz normal Z component
     * @return this consumer for chaining
     */
    default CgVertexConsumer normal(float nx, float ny, float nz) {
        return this;
    }
    
    // ── Matrix operations for CPU processing ───────────────────────────────────────────────
    
   default CgVertexConsumer vertex(Matrix4f pMatrix, float x, float y, float z) {
      Vector4f vector4f = pMatrix.transform(new Vector4f(x, y, z, 1.0F));
      return vertex(vector4f.x(), vector4f.y(), vector4f.z());
   }

   default CgVertexConsumer normal(Matrix3f pMatrix, float x, float y, float z) {
      Vector3f vector3f = pMatrix.transform(new Vector3f(x, y, z));
      return normal(vector3f.x(), vector3f.y(), vector3f.z());
   }

    // ── Vertex completion ───────────────────────────────────────────────

    /**
     * Signals that all attributes for the current vertex have been written.
     * Advances internal state to the next vertex position.
     *
     * <p>Must be called after all attribute setters for each vertex.
     * Behavior is undefined if called before setting the required attributes
     * for the backing format.</p>
     */
    void endVertex();
}
