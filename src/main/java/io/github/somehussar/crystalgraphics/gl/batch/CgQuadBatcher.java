package io.github.somehussar.crystalgraphics.gl.batch;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.vertex.CgTextureBinding;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArray;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayBinding;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry;

import lombok.Getter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * General-purpose 2D quad batcher modeled on LibGDX's SpriteBatch.
 *
 * <p>Accumulates quads into a CPU-side {@code float[]} staging buffer and
 * flushes to the GPU when texture/shader state changes, the buffer is full,
 * or {@link #end()} is called.</p>
 *
 * <p>All 2D rendering (text, UI panels, sprites) should use this batcher.
 * The batch does not own VAOs, VBOs, or IBOs — it borrows a shared binding
 * from {@link CgVertexArrayRegistry}.</p>
 */
public class CgQuadBatcher {

    private final CgVertexFormat format;
    private final CgVertexArrayBinding binding;
    private final CgQuadIndexBuffer quadIbo;
    private final int maxQuads;

    // CPU staging — float[] for JIT-friendly writes (per plan §6)
    private final float[] vertices;
    private int idx;
    private int quadCount;

    // Tracked state — flush on change
    @Getter
    private CgTextureBinding boundTexture;
    @Getter
    private CgShader boundShader;
    @Getter
    private boolean drawing;

    // Statistics
    @Getter
    private int flushCount;
    @Getter
    private int totalQuads;

    /**
     * @param format   the vertex format (typically {@code POS2_UV2_COL4UB})
     * @param maxQuads maximum quads before auto-flush
     */
    public CgQuadBatcher(CgVertexFormat format, int maxQuads) {
        this.format = format;
        this.maxQuads = maxQuads;
        this.binding = CgVertexArrayRegistry.INSTANCE.getOrCreate(format);
        this.quadIbo = CgQuadIndexBuffer.get();
        
        // 4 vertices per quad, each vertex = stride/4 floats
        this.vertices = new float[maxQuads * format.getFloatsPerVertex() * 4];
    }

    public void begin() {
        if (drawing) throw new IllegalStateException("Already drawing");
        drawing = true;
        idx = 0;
        quadCount = 0;
        flushCount = 0;
        totalQuads = 0;
    }

    /**
     * Sets the active texture. If different from current, triggers flush.
     */
    public void setTexture(CgTextureBinding texture) {
        if (!equal(boundTexture, texture)) {
            flush();
            boundTexture = texture;
        }
    }

    /**
     * Sets the active shader. If different from current, triggers flush.
     */
    public void setShader(CgShader shader) {
        if (shader != boundShader) {
            flush();
            boundShader = shader;
        }
    }

    /**
     * Adds a textured quad for format POS2_UV2_COL4UB. The color is packed
     * as int bits reinterpreted as float.
     */
    public void addQuad(float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1,
                        int packedColor) {
        if (!drawing) throw new IllegalStateException("Not drawing");
        if (quadCount >= maxQuads) flush();

        float colorBits = Float.intBitsToFloat(packedColor);

        // v0: top-left
        vertices[idx++] = x;
        vertices[idx++] = y;
        vertices[idx++] = u0;
        vertices[idx++] = v0;
        vertices[idx++] = colorBits;

        // v1: top-right
        vertices[idx++] = x + w;
        vertices[idx++] = y;
        vertices[idx++] = u1;
        vertices[idx++] = v0;
        vertices[idx++] = colorBits;

        // v2: bottom-right
        vertices[idx++] = x + w;
        vertices[idx++] = y + h;
        vertices[idx++] = u1;
        vertices[idx++] = v1;
        vertices[idx++] = colorBits;

        // v3: bottom-left
        vertices[idx++] = x;
        vertices[idx++] = y + h;
        vertices[idx++] = u0;
        vertices[idx++] = v1;
        vertices[idx++] = colorBits;

        quadCount++;
    }

    public void addColorQuad(float x, float y, float w, float h, int packedColor) {
        addQuad(x, y, w, h, 0, 0, 1, 1, packedColor);
    }

    public void flush() {
        if (quadCount == 0) return;

        // 1. Upload vertex data via stream buffer
        int uploadBytes = quadCount * 4 * format.getStride();
        ByteBuffer mapped = binding.getStreamBuffer().map(uploadBytes);
        if (mapped != null) {
            mapped.asFloatBuffer().put(vertices, 0, idx);
            int dataOffset = binding.getStreamBuffer().commit(uploadBytes);
            binding.rebindPointersIfNeeded(dataOffset);
        }

        // 2. Bind VAO + IBO
        binding.getVertexArray().bind();
        quadIbo.bindAndEnsureCapacity(quadCount);

        // 3. Bind shader + texture (caller is responsible for uniform setup)
        if (boundShader != null) boundShader.bind();
        if (boundTexture != null) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            boundTexture.bind();
        }

        // 4. Draw
        GL11.glDrawElements(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_SHORT, 0);
        quadIbo.unbind();

        // 5. Reset staging
        flushCount++;
        totalQuads += quadCount;
        idx = 0;
        quadCount = 0;
    }

    public void end() {
        if (!drawing) throw new IllegalStateException("Not drawing");
        flush();
        drawing = false;
        CgVertexArray.bind(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * The batch does not own shared stream buffers or VAO bindings.
     * Cleanup of those resources belongs to {@link CgVertexArrayRegistry#deleteAll()}.
     */
    public void delete() {
        // Intentionally empty — batch owns only CPU staging.
    }

    private static boolean equal(CgTextureBinding a, CgTextureBinding b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
