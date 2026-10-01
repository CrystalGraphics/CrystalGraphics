package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A frame graph built and ready to execute: its passes in the order they run, every batch packed, every instance of
 * each kind in one array, every binding snapshot in one table. Made by {@link CgFrameBuilder} on any thread and
 * executed by {@link CgExecutor} on the render thread; nothing in it needs the recordings any more.
 *
 * <pre>{@code
 * CgFrame frame = builder.build(graph);
 * CgExecutor.execute(frame);
 * builder.recycle(frame);        // its storage goes back to the builder; the frame is not used again
 * }</pre>
 *
 * <p>Its storage is reused frame after frame, so a steady frame allocates nothing; after {@link CgFrameBuilder#recycle}
 * the object is the builder's again.</p>
 */
public final class CgFrame {

    private static final int KINDS = CgInstanceKind.values().length;

    CgPass[] steps = new CgPass[8];
    Raster[] rasters = new Raster[8];
    int stepCount;
    final CgBindingTable bindings = new CgBindingTable();
    final float[][] instances = new float[KINDS][];
    final int[] instanceFloats = new int[KINDS];
    final List<CgGraphTexture> transients = new ArrayList<>();
    int[] acquireAt = new int[8];
    int[] releaseAfter = new int[8];
    int batches;
    int draws;

    CgFrame() {
        for (int k = 0; k < KINDS; k++) instances[k] = new float[CgInstanceKind.of(k).floats() * 256];
    }

    /** Empties it for the next build, keeping its storage. */
    void clear() {
        Arrays.fill(steps, 0, stepCount, null);
        stepCount = 0;
        bindings.reset();
        Arrays.fill(instanceFloats, 0);
        transients.clear();
        batches = 0;
        draws = 0;
    }

    /** Room for {@code count} steps. */
    void steps(int count) {
        if (steps.length < count) {
            steps = Arrays.copyOf(steps, Math.max(count, steps.length * 2));
            rasters = Arrays.copyOf(rasters, steps.length);
        }
        stepCount = count;
    }

    /** The packed form of step {@code s}, kept between frames. */
    Raster raster(int s) {
        Raster raster = rasters[s];
        if (raster == null) rasters[s] = raster = new Raster();
        return raster;
    }

    void reserve(int kind, int more) {
        int need = instanceFloats[kind] + more;
        if (need > instances[kind].length) {
            instances[kind] = Arrays.copyOf(instances[kind], Math.max(need, instances[kind].length * 2));
        }
    }

    void lifetimes(int count) {
        if (acquireAt.length < count) {
            acquireAt = new int[Math.max(count, acquireAt.length * 2)];
            releaseAfter = new int[acquireAt.length];
        }
    }

    /** How many passes run, after culling. */
    public int passes() {
        return stepCount;
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
        int constants;
        int count;
        int[] pipeline = new int[16];
        int[] binding = new int[16];
        int[] kind = new int[16];
        int[] first = new int[16];
        int[] instances = new int[16];
        CgMesh[] mesh = new CgMesh[16];
        /** Bits by kind ordinal: the kinds its batches draw, so their buffers are bound once per pass. */
        int kinds;
        /** Recorded draws its batches cover. */
        int draws;

        void size(int batches) {
            if (pipeline.length < batches) {
                int n = Math.max(batches, pipeline.length * 2);
                pipeline = new int[n];
                binding = new int[n];
                kind = new int[n];
                first = new int[n];
                instances = new int[n];
                mesh = new CgMesh[n];
            } else {
                Arrays.fill(mesh, 0, count, null);
            }
            count = batches;
            kinds = 0;
            Arrays.fill(instances, 0, batches, 0);
        }
    }
}
