package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A frame graph built and ready to execute: its passes in the order they run, every batch packed, every instance of
 * each kind in one array, every binding snapshot in one table. Made by {@link CgFrameBuilder} on any thread and
 * executed by {@link CgExecutor} on the render thread; nothing in it needs the recordings any more.
 *
 * <pre>{@code
 * CgFrame frame = builder.build(graph);
 * executor.execute(frame);
 * builder.recycle(frame);        // its arrays go back to the builder; the frame is not used again
 * }</pre>
 */
public final class CgFrame {

    final CgPass[] steps;
    @Nullable
    final Raster[] rasters;
    final CgBindingTable bindings;
    final float[][] instances;
    final int[] instanceFloats;
    final List<CgGraphTexture> transients;
    final int[] acquireAt;
    final int[] releaseAfter;
    final int batches;
    final int draws;
    final CgFrameBuilder.Body body;

    CgFrame(CgPass[] steps, Raster[] rasters, CgBindingTable bindings, float[][] instances, int[] instanceFloats,
            List<CgGraphTexture> transients, int[] acquireAt, int[] releaseAfter, int batches, int draws,
            CgFrameBuilder.Body body) {
        this.steps = steps;
        this.rasters = rasters;
        this.bindings = bindings;
        this.instances = instances;
        this.instanceFloats = instanceFloats;
        this.transients = transients;
        this.acquireAt = acquireAt;
        this.releaseAfter = releaseAfter;
        this.batches = batches;
        this.draws = draws;
        this.body = body;
    }

    /** How many passes run, after culling. */
    public int passes() {
        return steps.length;
    }

    /** How many draw calls its raster passes make. */
    public int batches() {
        return batches;
    }

    /** How many recorded draws those calls cover. */
    public int draws() {
        return draws;
    }

    /** Instances of {@code kind} the frame uploads. */
    public int instances(CgInstanceKind kind) {
        return instanceFloats[kind.ordinal()] / kind.floats();
    }

    /** The name of the pass at {@code step}, for tests and traces. */
    public String passName(int step) {
        return steps[step].name();
    }

    /** A raster pass, packed: the snapshot of its constants, and per batch what to bind and which instances to draw. */
    static final class Raster {
        final int constants;
        final int count;
        final int[] pipeline;
        final int[] binding;
        final int[] kind;
        final int[] first;
        final int[] instances;
        final CgMesh[] mesh;
        /** Bits by kind ordinal: the kinds its batches draw, so their buffers are bound once per pass. */
        final int kinds;
        /** Recorded draws its batches cover. */
        final int draws;

        Raster(int constants, int count, int[] pipeline, int[] binding, int[] kind, int[] first, int[] instances,
               CgMesh[] mesh, int kinds, int draws) {
            this.constants = constants;
            this.count = count;
            this.pipeline = pipeline;
            this.binding = binding;
            this.kind = kind;
            this.first = first;
            this.instances = instances;
            this.mesh = mesh;
            this.kinds = kinds;
            this.draws = draws;
        }
    }
}
