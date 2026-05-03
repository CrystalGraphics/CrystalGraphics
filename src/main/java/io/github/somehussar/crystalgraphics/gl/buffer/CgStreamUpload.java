package io.github.somehussar.crystalgraphics.gl.buffer;

import java.nio.ByteBuffer;

/**
 * Stateless utility for uploading float arrays to a {@link CgStreamBuffer}.
 *
 * <p>Extracts the {@code map → asFloatBuffer().put() → commit} sequence
 * that was previously duplicated in {@link io.github.somehussar.crystalgraphics.gl.render.CgBatchRenderer}
 * and {@link io.github.somehussar.crystalgraphics.gl.render.CgInstancedBatchRenderer}.
 * All new batch renderers should use this utility instead of inlining the sequence.</p>
 *
 * <p>Does <strong>not</strong> call {@link CgStreamBuffer#afterSubmit()} — that must
 * be called by the renderer after the draw command has been submitted to GL.</p>
 */
public final class CgStreamUpload {

    private CgStreamUpload() {}

    /**
     * Maps the stream buffer, copies {@code floatCount} floats from {@code data}
     * starting at index 0, commits, and returns the byte offset where the data starts.
     *
     * <p>The caller is responsible for calling {@link CgStreamBuffer#afterSubmit()}
     * after the draw call that consumes this upload.</p>
     *
     * @param buffer     the stream buffer to upload into
     * @param data       source float array
     * @param floatCount number of floats to copy from {@code data[0..floatCount-1]}
     * @return byte offset in the GL buffer where the uploaded data begins
     */
    public static int uploadFloats(CgStreamBuffer buffer, float[] data, int floatCount) {
        int byteCount = floatCount * Float.BYTES;
        ByteBuffer mapped = buffer.map(byteCount);
        mapped.asFloatBuffer().put(data, 0, floatCount);
        return buffer.commit(byteCount);
    }
}
