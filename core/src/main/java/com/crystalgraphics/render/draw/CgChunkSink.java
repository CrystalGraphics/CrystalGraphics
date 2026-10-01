package com.crystalgraphics.render.draw;

/**
 * Where a renderer's flush hands its chunk, instead of executing it: a recorder's current pass.
 *
 * <pre>{@code
 * CgPassRecorder recorder = new CgPassRecorder();
 * quadRenderer.sink(recorder);      // flushes become chunks in the recorder's passes
 * textRenderer.sink(recorder);
 * quadRenderer.sink(null);          // back to executing at once, through CgImmediate
 * }</pre>
 */
@FunctionalInterface
public interface CgChunkSink {

    /** Takes {@code chunk}, which is sealed: nothing the renderer does after this changes it. */
    void add(CgDrawChunk chunk);
}
