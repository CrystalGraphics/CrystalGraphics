package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexAttribute;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexConsumer;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer;

import java.nio.ByteBuffer;

/**
 * Format-aware fluent vertex writer implementing {@link CgVertexConsumer}.
 *
 * <p>This is the V1 writer: it supports formats composed of POSITION (2D/3D),
 * UV0, COLOR0, and NORMAL0 in any attribute order. Each semantic must appear at
 * most once and must use semantic index 0. Formats with higher indices or
 * unsupported semantics (GENERIC) are rejected at construction time.</p>
 *
 * <h3>Step Machine</h3>
 * <p>A step counter enforces call ordering:
 * {@code vertex → uv → color → normal → endVertex}. Steps for absent
 * attributes are skipped automatically. Out-of-order calls throw
 * {@link IllegalStateException}.</p>
 *
 * <h3>Attribute Write Order</h3>
 * <p>The fluent API collects values into local fields. {@link #endVertex()} writes
 * them into the output in <em>format declaration order</em>
 * (iterating the format's attribute array), not in API call order. This ensures
 * the staging buffer layout matches the interleaved vertex format regardless of
 * which fluent methods the caller invokes first.</p>
 *
 * <p>Public constructor for staging-buffer path; static factory {@link #forBuffer(ByteBuffer, CgVertexFormat)}
 * for direct ByteBuffer output.</p>
 *
 * @see CgStagingBuffer
 * @see CgBatchRenderer
 */
public final class CgVertexWriter implements CgVertexConsumer {

    private final CgVertexOutput output;
    // Non-null only when the staging-buffer constructor is used; null for ByteBuffer path.
    private final CgStagingBuffer stagingBufferOrNull;
    private final CgVertexFormat format;
    private final CgVertexAttribute[] attributes;

    private final int positionComponents;
    private final boolean hasUv;
    private final boolean hasColor;
    private final boolean hasNormal;

    // Step machine: 0=position, 1=uv, 2=color, 3=normal, 4=ready for endVertex
    private int step;

    private float posX;
    private float posY;
    private float posZ;
    private float uvU;
    private float uvV;
    private int colorRGBA = 0xFFFFFFFF;
    private float normalX;
    private float normalY;
    private float normalZ;

    // ── Public constructor (staging-buffer path) ─────────────────────────

    public CgVertexWriter(CgStagingBuffer staging, CgVertexFormat format) {
        this((CgVertexOutput) staging, staging, format);
    }

    // ── Static factory (ByteBuffer path) ─────────────────────────────────

    /**
     * Creates a {@link CgVertexWriter} that packs vertex data directly into a
     * pre-allocated direct {@link ByteBuffer}.
     *
     * <p>The buffer must have capacity {@code vertexCount * format.getStride()} bytes
     * and must be ready for writing (position=0). Call {@code buf.flip()} after all
     * vertices are written before passing to GPU upload.</p>
     *
     * @param dest   writable direct ByteBuffer with sufficient capacity
     * @param format vertex format describing the attribute layout
     * @return a new writer backed by the ByteBuffer
     */
    public static CgVertexWriter forBuffer(ByteBuffer dest, CgVertexFormat format) {
        return new CgVertexWriter(new CgStagingByteBuffer(dest), null, format);
    }

    // ── Private canonical constructor ─────────────────────────────────────

    private CgVertexWriter(CgVertexOutput output, CgStagingBuffer stagingBufferOrNull, CgVertexFormat format) {
        this.output = output;
        this.stagingBufferOrNull = stagingBufferOrNull;
        this.format = format;
        this.attributes = new CgVertexAttribute[format.getAttributeCount()];

        int posComponents = 0;
        boolean uv = false;
        boolean color = false;
        boolean normal = false;

        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute attr = format.getAttribute(i);
            attributes[i] = attr;
            switch (attr.getSemantic()) {
                case POSITION:
                    if (attr.getSemanticIndex() != 0) {
                        throw new IllegalArgumentException("V1 writer does not support POSITION semantic index " + attr.getSemanticIndex());
                    }
                    if (attr.getComponents() != 2 && attr.getComponents() != 3) {
                        throw new IllegalArgumentException("POSITION must have 2 or 3 components, got " + attr.getComponents());
                    }
                    posComponents = attr.getComponents();
                    break;
                case UV:
                    if (attr.getSemanticIndex() != 0 || attr.getComponents() != 2) {
                        throw new IllegalArgumentException("V1 writer only supports UV0 with 2 components");
                    }
                    uv = true;
                    break;
                case COLOR:
                    if (attr.getSemanticIndex() != 0 || attr.getByteSize() != 4) {
                        throw new IllegalArgumentException("V1 writer only supports COLOR0 with packed 4-byte storage");
                    }
                    color = true;
                    break;
                case NORMAL:
                    if (attr.getSemanticIndex() != 0 || attr.getComponents() != 3) {
                        throw new IllegalArgumentException("V1 writer only supports NORMAL0 with 3 components");
                    }
                    normal = true;
                    break;
                case GENERIC:
                default:
                    throw new IllegalArgumentException("V1 writer does not support semantic " + attr.getSemantic());
            }
        }

        if (posComponents == 0) {
            throw new IllegalArgumentException("Format must contain a POSITION attribute");
        }

        this.positionComponents = posComponents;
        this.hasUv = uv;
        this.hasColor = color;
        this.hasNormal = normal;
    }

    @Override
    public CgVertexFormat format() { return format; }

    @Override
    public CgVertexConsumer vertex(float x, float y) {
        if (step != 0) throw new IllegalStateException("vertex(x,y) out of order");
        if (positionComponents != 2) throw new IllegalStateException("Format is not 2D-position");
        posX = x;
        posY = y;
        posZ = 0.0f;
        step = hasUv ? 1 : hasColor ? 2 : hasNormal ? 3 : 4;
        return this;
    }

    @Override
    public CgVertexConsumer vertex(float x, float y, float z) {
        if (step != 0) throw new IllegalStateException("vertex(x,y,z) out of order");
        if (positionComponents != 3) throw new IllegalStateException("Format is not 3D-position");
        posX = x;
        posY = y;
        posZ = z;
        step = hasUv ? 1 : hasColor ? 2 : hasNormal ? 3 : 4;
        return this;
    }

    @Override
    public CgVertexConsumer uv(float u, float v) {
        if (step != 1) throw new IllegalStateException("uv() out of order");
        uvU = u;
        uvV = v;
        step = hasColor ? 2 : hasNormal ? 3 : 4;
        return this;
    }

    @Override
    public CgVertexConsumer color(int r, int g, int b, int a) {
        if (step != 2) throw new IllegalStateException("color() out of order");
        colorRGBA = CgColorPacking.packNativeOrder(r, g, b, a);
        step = hasNormal ? 3 : 4;
        return this;
    }

    @Override
    public CgVertexConsumer normal(float nx, float ny, float nz) {
        if (!hasNormal) return this;
        if (step != 3) throw new IllegalStateException("normal() out of order");
        normalX = nx;
        normalY = ny;
        normalZ = nz;
        step = 4;
        return this;
    }

    @Override
    public void endVertex() {
        if (step != 4) throw new IllegalStateException("Incomplete vertex");
        for (CgVertexAttribute attr : attributes) {
            switch (attr.getSemantic()) {
                case POSITION:
                    output.putFloat(posX);
                    output.putFloat(posY);
                    if (attr.getComponents() == 3) {
                        output.putFloat(posZ);
                    }
                    break;
                case UV:
                    output.putFloat(uvU);
                    output.putFloat(uvV);
                    break;
                case COLOR:
                    output.putColorPacked(colorRGBA);
                    break;
                case NORMAL:
                    output.putFloat(normalX);
                    output.putFloat(normalY);
                    output.putFloat(normalZ);
                    break;
                case GENERIC:
                default:
                    throw new IllegalStateException("Unsupported semantic at write time: " + attr.getSemantic());
            }
        }
        step = 0;
        if (stagingBufferOrNull != null) {
            stagingBufferOrNull.ensureRoomForNextVertex();
        }
    }

   public void reset() { step = 0; }
}
