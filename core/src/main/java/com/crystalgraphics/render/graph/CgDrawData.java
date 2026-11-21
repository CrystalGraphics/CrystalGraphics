package com.crystalgraphics.render.graph;

import javax.annotation.Nullable;

import com.crystalgraphics.gpu.resource.CgShaderBuffer;

/**
 * What a recording's draws read beyond their own records, kept by whoever records them: the canvas's property trees,
 * clips and gradients. A recording carries one ({@link CgRecording#drawData}); a frame built from it keeps a frozen
 * copy, and binds that on each of the recording's raster passes whose pipelines declare a buffer it supplies. The
 * graph knows this interface and the slots, never the tables: Unreal's pass parameters, gathered per recording as
 * Graphite's draw passes gather theirs.
 *
 * <pre>{@code
 * final class MyTables implements CgDrawData {
 *     static MyTables of(CgRecording recording) {
 *         CgDrawData d = recording.drawData();
 *         if (d == null) recording.drawData(d = new MyTables());
 *         return (MyTables) d;
 *     }
 *     public Frozen freeze(CgPropertyValues values) { return new MyFrozen(copyOfMyTables, values); }
 *     public void reset() { ... }                   // with its recording
 * }
 *
 * final class MyFrozen implements CgDrawData.Frozen {
 *     public void bind(Binder pass) {
 *         if (pass.reads(MY_INDEX)) pass.bind(myBuffer.prepare());  // CgEngineBufferRegistry.indexOf("mine")
 *     }
 *     public void release() { ... }                  // back to a pool
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #freeze} runs once per build on the builder's thread; what it answers must not change afterwards: the
 *       recording is reset and refilled while the frame may still execute again.</li>
 *   <li>Values are read at each execution, not at freeze: a frame executed again draws with them as they stand.</li>
 *   <li>{@link #domain} and {@link #boundsInDomain} let the batcher reorder draws only where their places cannot
 *       change relative to one another; without data, every draw is in domain 0.</li>
 * </ul>
 */
public interface CgDrawData {

    /** What a frame built now keeps of it, drawn with {@code values} (null: as recorded). */
    Frozen freeze(@Nullable CgPropertyValues values);

    /** Drops what was recorded: called by its recording's {@code reset}. */
    void reset();

    /** The draws whose order among themselves cannot change their result: those under the same movable node. */
    default int domain(int node) {
        return 0;
    }

    /** {@code (x0, y0)-(x1, y1)} in {@code node}'s space, into its {@link #domain}'s space, as {@code x0 y0 x1 y1}. */
    default void boundsInDomain(int node, float x0, float y0, float x1, float y1, float[] out) {
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y1;
    }

    /** A built frame's copy: bound on each raster pass of its recording that reads it. Render thread. */
    interface Frozen {

        /** Binds what {@code pass}'s pipelines read of it. */
        void bind(Binder pass);

        /**
         * The box at {@code at} of {@code boxes} ({@code x0 y0 x1 y1}) in {@code node}'s space, as the last bound pass's
         * pixels ({@code x y w h}) into {@code out}: a scissor placed under a node.
         */
        void scissorOf(int node, float[] boxes, int at, int[] out);

        /** The frame is recycled: nothing reads it any more. */
        void release();
    }

    /** A raster pass at execution, as frozen data sees it. */
    interface Binder {

        /**
         * Whether a pipeline of this pass declares the engine buffer at {@code index} or needs it: its
         * {@code CgEngineBufferRegistry.indexOf(token)}, looked up once and kept.
         */
        boolean reads(int index);

        /** {@code buffer} at its slot for the pass's draws. */
        void bind(CgShaderBuffer buffer);

        /** The spatial node this pass's target moves with: a layer's; 0 for the root. */
        int viewOwner();

        /** Where this pass's target sits in its recording's root space. */
        float viewX();

        float viewY();

        /** The target's height in pixels. */
        float targetHeight();
    }
}
