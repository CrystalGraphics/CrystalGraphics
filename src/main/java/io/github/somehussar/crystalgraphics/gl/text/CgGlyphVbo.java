package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Manages a VAO + VBO (dynamic vertex data) + IBO (static quad indices) for
 * batched glyph quad rendering.
 *
 * <p>Each glyph quad consists of 4 vertices with the following interleaved
 * layout (stride = 20 bytes):</p>
 * <pre>
 *   offset  0: float x       — position X (pixels, relative to text origin)
 *   offset  4: float y       — position Y (pixels, relative to text origin)
 *   offset  8: float u       — atlas UV X [0,1]
 *   offset 12: float v       — atlas UV Y [0,1]
 *   offset 16: ubyte4 color  — packed RGBA color (normalized by vertex attrib pointer)
 * </pre>
 *
 * <p>The IBO uses {@code GL_UNSIGNED_SHORT} with the repeating pattern
 * {@code [0,1,2, 2,3,0]} per quad (offset by 4 per quad index).</p>
 *
 * <h3>Usage Pattern</h3>
 * <ol>
 *   <li>{@link #create(int)} — allocate GL objects with initial capacity</li>
 *   <li>{@link #setupAttributes(CgShaderProgram)} — bind attribute pointers
 *       to a compiled shader (call once per shader, after link)</li>
 *   <li>Per frame:
 *     <ol>
 *       <li>{@link #begin()} — reset write cursor</li>
 *       <li>{@link #addGlyph(float, float, float, float, float, float, float, float, int)}
 *           — append glyph quads</li>
 *       <li>{@link #uploadAndBind()} — upload to GPU and bind VAO</li>
 *       <li>Issue draw calls ({@code glDrawElements})</li>
 *       <li>{@link #unbind()} — unbind VAO</li>
 *     </ol>
 *   </li>
 *   <li>{@link #delete()} — release all GL resources</li>
 * </ol>
 *
 * <h3>Ownership Model</h3>
 * <p>Instances created via {@link #create(int)} are always owned.  All owned
 * instances are tracked in a static set for bulk cleanup via
 * {@link #freeAll()}.  Calling {@link #delete()} on a non-owned instance
 * throws {@link IllegalStateException}.</p>
 *
 * <h3>Capacity Growth</h3>
 * <p>If {@link #addGlyph} is called when the internal buffer is full, the
 * VBO and IBO are re-allocated with 1.5x the previous capacity.  The IBO
 * is re-uploaded as {@code GL_STATIC_DRAW}; the VBO is re-allocated as
 * {@code GL_STREAM_DRAW}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must be used only from the render thread with an
 * active GL context.</p>
 *
 * @see CgShaderProgram
 */
public class CgGlyphVbo {

    // ── Constants ──────────────────────────────────────────────────────

    static final int FLOATS_PER_VERTEX = 5;

    /** Bytes per vertex (5 floats * 4 bytes). */
    static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;

    /** Vertices per glyph quad. */
    private static final int VERTICES_PER_QUAD = 4;

    /** Indices per glyph quad (two triangles). */
    private static final int INDICES_PER_QUAD = 6;

    /** Floats per glyph quad. */
    static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * VERTICES_PER_QUAD;

    /** Growth factor when capacity is exceeded. */
    private static final float GROWTH_FACTOR = 1.5f;

    // ── GL bit flags (avoid importing GL30 constants for map flags) ────

    /** {@code GL_MAP_WRITE_BIT} */
    private static final int GL_MAP_WRITE_BIT = 0x0002;

    /** {@code GL_MAP_INVALIDATE_BUFFER_BIT} */
    private static final int GL_MAP_INVALIDATE_BUFFER_BIT = 0x0008;

    // ── Static tracking ────────────────────────────────────────────────

    /**
     * Set of all owned CgGlyphVbo instances for bulk cleanup.
     */
    private static final Set<CgGlyphVbo> ALL_OWNED =
            new CopyOnWriteArraySet<CgGlyphVbo>();

    // ── Instance fields ────────────────────────────────────────────────

    /** OpenGL Vertex Array Object ID. */
    private int vao;

    /** OpenGL Vertex Buffer Object ID (dynamic vertex data). */
    private int vbo;

    /** OpenGL Index Buffer Object ID (static quad indices). */
    private int ibo;

    /** Current capacity in number of glyph quads. */
    private int capacityGlyphs;

    /**
     * CPU-side staging buffer for vertex data.
     * Holds {@code capacityGlyphs * FLOATS_PER_QUAD} floats.
     */
    private FloatBuffer vertexData;

    /** Number of glyph quads added since the last {@link #begin()} call. */
    private int glyphCount;

    /** Whether this VBO is owned by CrystalGraphics. */
    private final boolean owned;

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /** Whether {@link #setupAttributes(CgShaderProgram)} has been called. */
    private boolean attributesConfigured;

    // ── Constructor (private) ──────────────────────────────────────────

    private CgGlyphVbo(int vao, int vbo, int ibo, int capacityGlyphs, boolean owned) {
        this.vao = vao;
        this.vbo = vbo;
        this.ibo = ibo;
        this.capacityGlyphs = capacityGlyphs;
        this.vertexData = BufferUtils.createFloatBuffer(capacityGlyphs * FLOATS_PER_QUAD);
        this.glyphCount = 0;
        this.owned = owned;
        this.deleted = false;
        this.attributesConfigured = false;

        if (owned) {
            ALL_OWNED.add(this);
        }
    }

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a new VAO + VBO + IBO for glyph quad rendering.
     *
     * <p>Must be called from the render thread with an active GL context.
     * The VBO is allocated as {@code GL_STREAM_DRAW} (updated every frame).
     * The IBO is allocated as {@code GL_STATIC_DRAW} with the pre-computed
     * quad index pattern.</p>
     *
     * @param initialCapacityGlyphs the initial number of glyph quads the
     *                              buffer can hold before needing to grow
     * @return a new owned {@code CgGlyphVbo}
     * @throws IllegalArgumentException if {@code initialCapacityGlyphs} is
     *                                  less than 1
     */
    public static CgGlyphVbo create(int initialCapacityGlyphs) {
        if (initialCapacityGlyphs < 1) {
            throw new IllegalArgumentException(
                    "initialCapacityGlyphs must be >= 1, got " + initialCapacityGlyphs);
        }

        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();
        int ibo = GL15.glGenBuffers();

        // Allocate VBO (empty, GL_STREAM_DRAW)
        int vboSizeBytes = initialCapacityGlyphs * VERTICES_PER_QUAD * STRIDE_BYTES;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboSizeBytes, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Allocate and upload IBO (static quad index pattern)
        ShortBuffer indexData = buildIndexBuffer(initialCapacityGlyphs);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexData, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        return new CgGlyphVbo(vao, vbo, ibo, initialCapacityGlyphs, true);
    }

    // ── Attribute Setup ────────────────────────────────────────────────

    /**
     * Sets up VAO vertex attribute pointers for the given compiled shader.
     *
     * <p>Queries attribute locations by name from the shader program via
     * {@link GL20#glGetAttribLocation(int, CharSequence)}.  Must be called
     * <em>after</em> the shader is compiled/linked and <em>before</em> the
     * first draw call.</p>
     *
     * <p>Required attribute names in the shader:</p>
     * <ul>
     *   <li>{@code a_pos}   — {@code vec2}, byte offset 0</li>
     *   <li>{@code a_uv}    — {@code vec2}, byte offset 8</li>
     *   <li>{@code a_color} — {@code vec4} from 4 normalized unsigned bytes, byte offset 16</li>
     * </ul>
     *
     * @param program the compiled {@link CgShaderProgram} to query locations from
     * @throws IllegalStateException if already deleted
     */
    public void setupAttributes(CgShaderProgram program) {
        checkNotDeleted();

        int programId = program.getId();

        int posLoc = GL20.glGetAttribLocation(programId, "a_pos");
        int uvLoc = GL20.glGetAttribLocation(programId, "a_uv");
        int colorLoc = GL20.glGetAttribLocation(programId, "a_color");

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }
        if (uvLoc >= 0) {
            GL20.glVertexAttribPointer(uvLoc, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 8);
            GL20.glEnableVertexAttribArray(uvLoc);
        }
        if (colorLoc >= 0) {
            GL20.glVertexAttribPointer(colorLoc, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE_BYTES, 16);
            GL20.glEnableVertexAttribArray(colorLoc);
        }

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);

        GL30.glBindVertexArray(0);

        attributesConfigured = true;
    }

    // ── Per-Frame API ──────────────────────────────────────────────────

    /**
     * Resets the write cursor for a new frame.
     *
     * <p>Does not reallocate or modify GL buffers.  Simply resets the
     * internal glyph count so that subsequent {@link #addGlyph} calls
     * overwrite from the beginning of the staging buffer.</p>
     *
     * @throws IllegalStateException if already deleted
     */
    public void begin() {
        checkNotDeleted();
        glyphCount = 0;
        vertexData.clear();
    }

    /**
     * Adds one glyph quad to the staging buffer.
     *
     * <p>The quad is defined by a top-left corner ({@code x}, {@code y})
     * and dimensions ({@code w}, {@code h}) in pixel coordinates relative
     * to the text origin.  UV coordinates map the quad to the atlas texture.
     * Color is provided as {@code 0xRRGGBBAA} and repacked into byte order for
     * the normalized {@code ubyte4} vertex attribute.</p>
     *
     * <p>Vertex winding is counter-clockwise:
     * <pre>
     *   v0 (TL) ── v1 (TR)
     *    |            |
     *   v3 (BL) ── v2 (BR)
     * </pre>
     * Index pattern per quad: {@code [0,1,2, 2,3,0]}.</p>
     *
     * <p>If the staging buffer is full, capacity is grown by
     * {@value #GROWTH_FACTOR}x and the GL IBO is re-uploaded.</p>
     *
     * @param x    top-left X in pixels
     * @param y    top-left Y in pixels
     * @param w    width in pixels
     * @param h    height in pixels
     * @param u0   atlas UV left
     * @param v0   atlas UV top
     * @param u1   atlas UV right
     * @param v1   atlas UV bottom
     * @param rgba packed color (0xRRGGBBAA)
     * @throws IllegalStateException if already deleted
     */
    public void addGlyph(float x, float y, float w, float h,
                         float u0, float v0, float u1, float v1,
                         int rgba) {
        checkNotDeleted();

        if (glyphCount >= capacityGlyphs) {
            grow();
        }

        float packedColorBits = Float.intBitsToFloat(packColorBytes(rgba));

        // v0: top-left
        vertexData.put(x);
        vertexData.put(y);
        vertexData.put(u0);
        vertexData.put(v0);
        vertexData.put(packedColorBits);

        // v1: top-right
        vertexData.put(x + w);
        vertexData.put(y);
        vertexData.put(u1);
        vertexData.put(v0);
        vertexData.put(packedColorBits);

        // v2: bottom-right
        vertexData.put(x + w);
        vertexData.put(y + h);
        vertexData.put(u1);
        vertexData.put(v1);
        vertexData.put(packedColorBits);

        // v3: bottom-left
        vertexData.put(x);
        vertexData.put(y + h);
        vertexData.put(u0);
        vertexData.put(v1);
        vertexData.put(packedColorBits);

        glyphCount++;
    }

    /**
     * Uploads vertex data to the GPU via {@code glMapBufferRange} and binds
     * the VAO for drawing.
     *
     * <p>Uses {@code GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT} for
     * buffer orphaning, which avoids CPU-GPU synchronization stalls.</p>
     *
     * @return the number of glyph quads staged since the last {@link #begin()}
     * @throws IllegalStateException if already deleted
     */
    public int uploadAndBind() {
        checkNotDeleted();

        if (glyphCount == 0) {
            GL30.glBindVertexArray(vao);
            return 0;
        }

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        int uploadBytes = glyphCount * VERTICES_PER_QUAD * STRIDE_BYTES;

        // Map the VBO for writing (orphan the old data)
        ByteBuffer mapped = GL30.glMapBufferRange(
                GL15.GL_ARRAY_BUFFER,
                0,
                uploadBytes,
                GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT,
                null);

        if (mapped != null) {
            // Copy from CPU staging buffer into the mapped GPU buffer
            vertexData.flip();
            mapped.asFloatBuffer().put(vertexData);
            GL15.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
        }

        return glyphCount;
    }

    /**
     * Unbinds the VAO and array buffer, restoring GL state to what vanilla
     * expects (no VBO/VAO active).
     *
     * <p>Simply unbinding the VAO is <em>not</em> sufficient: the
     * {@code GL_ARRAY_BUFFER} binding is <em>not</em> part of VAO state in
     * core OpenGL.  If it is left pointing at this VBO, vanilla
     * {@code Tessellator.draw()} will fail with
     * {@code "Cannot use Buffers when Array Buffer Object is enabled"}
     * because it passes client-side {@link java.nio.Buffer Buffers} to
     * {@code glVertexPointer}/{@code glTexCoordPointer} while a VBO is
     * still bound.</p>
     *
     * @throws IllegalStateException if already deleted
     */
    public void unbind() {
        checkNotDeleted();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // ── Queries ────────────────────────────────────────────────────────

    /**
     * Returns the number of glyph quads staged since the last
     * {@link #begin()} call.
     *
     * @return the current glyph count
     */
    public int getGlyphCount() {
        return glyphCount;
    }

    /**
     * Returns the current glyph capacity (before growth is triggered).
     *
     * @return the capacity in number of glyph quads
     */
    public int getCapacity() {
        return capacityGlyphs;
    }

    /**
     * Returns whether this VBO is owned by CrystalGraphics.
     *
     * @return always {@code true} for instances created via {@link #create(int)}
     */
    public boolean isOwned() {
        return owned;
    }

    /**
     * Returns whether {@link #delete()} has been called.
     *
     * @return {@code true} if deleted
     */
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Returns whether attribute pointers have been configured via
     * {@link #setupAttributes(CgShaderProgram)}.
     *
     * @return {@code true} if attributes are set up
     */
    public boolean isAttributesConfigured() {
        return attributesConfigured;
    }

    // ── Deletion ───────────────────────────────────────────────────────

    /**
     * Deletes the VAO, VBO, and IBO, releasing all GL resources.
     *
     * <p>Subsequent calls after the first deletion are no-ops.</p>
     *
     * @throws IllegalStateException if this VBO is not owned
     */
    public void delete() {
        if (!owned) {
            throw new IllegalStateException(
                    "Cannot delete a non-owned CgGlyphVbo");
        }
        if (deleted) {
            return;
        }
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL15.glDeleteBuffers(ibo);

        vao = 0;
        vbo = 0;
        ibo = 0;
        deleted = true;
        ALL_OWNED.remove(this);
    }

    /**
     * Deletes all owned {@code CgGlyphVbo} instances.
     *
     * <p>Intended to be called during shutdown or context destruction.</p>
     */
    public static void freeAll() {
        for (CgGlyphVbo instance : ALL_OWNED) {
            if (!instance.deleted) {
                GL30.glDeleteVertexArrays(instance.vao);
                GL15.glDeleteBuffers(instance.vbo);
                GL15.glDeleteBuffers(instance.ibo);
                instance.vao = 0;
                instance.vbo = 0;
                instance.ibo = 0;
                instance.deleted = true;
            }
        }
        ALL_OWNED.clear();
    }

    // ── Internals ──────────────────────────────────────────────────────

    /**
     * Provides read-only access to the internal staging buffer for testing.
     *
     * <p>The returned buffer is a slice from position 0 to the current
     * write position.  Callers must not modify it.</p>
     *
     * @return the staging {@link FloatBuffer} (read-only view for testing)
     */
    FloatBuffer getVertexDataForTest() {
        FloatBuffer copy = vertexData.duplicate();
        copy.flip();
        return copy;
    }

    /**
     * Provides read-only access to the IBO index pattern for a given
     * capacity.  Used for testing.
     *
     * @param numQuads number of quads
     * @return a {@link ShortBuffer} with the index pattern
     */
    static ShortBuffer buildIndexBufferForTest(int numQuads) {
        return buildIndexBuffer(numQuads);
    }

    static int packColorBytesForTest(int rgba) {
        return packColorBytes(rgba);
    }

    private void grow() {
        int newCapacity = Math.max(capacityGlyphs + 1,
                (int) (capacityGlyphs * GROWTH_FACTOR));
        // Clamp to max short index range: 65536 / 4 vertices per quad
        int maxQuads = 65536 / VERTICES_PER_QUAD;
        if (newCapacity > maxQuads) {
            newCapacity = maxQuads;
            if (capacityGlyphs >= maxQuads) {
                throw new IllegalStateException(
                        "CgGlyphVbo capacity cannot exceed " + maxQuads
                        + " quads (GL_UNSIGNED_SHORT index limit)");
            }
        }

        // Re-allocate CPU staging buffer, preserving existing data
        FloatBuffer oldData = vertexData;
        oldData.flip();
        vertexData = BufferUtils.createFloatBuffer(newCapacity * FLOATS_PER_QUAD);
        vertexData.put(oldData);

        // Re-allocate VBO on GPU
        int vboSizeBytes = newCapacity * VERTICES_PER_QUAD * STRIDE_BYTES;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboSizeBytes, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Re-allocate and re-upload IBO
        ShortBuffer newIndices = buildIndexBuffer(newCapacity);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, newIndices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        capacityGlyphs = newCapacity;
    }

    /**
     * Builds the IBO index pattern for the given number of quads.
     *
     * <p>Pattern per quad: {@code [base+0, base+1, base+2, base+2, base+3, base+0]}
     * where {@code base = i * 4}.</p>
     *
     * @param numQuads the number of quads
     * @return a flipped {@link ShortBuffer} containing the index data
     */
    private static ShortBuffer buildIndexBuffer(int numQuads) {
        ShortBuffer buf = BufferUtils.createShortBuffer(numQuads * INDICES_PER_QUAD);
        for (int i = 0; i < numQuads; i++) {
            short base = (short) (i * VERTICES_PER_QUAD);
            buf.put(base);
            buf.put((short) (base + 1));
            buf.put((short) (base + 2));
            buf.put((short) (base + 2));
            buf.put((short) (base + 3));
            buf.put(base);
        }
        buf.flip();
        return buf;
    }

    private static int packColorBytes(int rgba) {
        return ((rgba & 0x000000FF) << 24)
                | ((rgba & 0x0000FF00) << 8)
                | ((rgba & 0x00FF0000) >>> 8)
                | ((rgba & 0xFF000000) >>> 24);
    }

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("CgGlyphVbo has been deleted");
        }
    }
}
