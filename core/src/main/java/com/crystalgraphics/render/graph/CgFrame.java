package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.render.property.CgPropertyValues;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgInstanceKind;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
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
 *
 * <p>A frame may be executed again, until it is recycled: it draws once more with its {@code CgPropertyValues} as
 * they stand then, which is how a compositor moves a scroll or a window between two recorded frames. Its uploads and
 * compiles ran the first time and are skipped.</p>
 */
public final class CgFrame {

    private static final int KINDS = CgInstanceKind.values().length;

    CgPass[] steps = new CgPass[8];
    Raster[] rasters = new Raster[8];
    Compute[] computes = new Compute[8];
    int stepCount;
    final CgBindingTable bindings = new CgBindingTable();
    final float[][] instances = new float[KINDS][];
    final int[] instanceFloats = new int[KINDS];
    final List<CgGraphResource> transients = new ArrayList<>();
    /** Per step, from {@code accessFrom[s]} to {@code accessFrom[s + 1]}: what it accesses, once each, and how. */
    int[] accessFrom = new int[9];
    CgGraphResource[] accessView = new CgGraphResource[32];
    int[] accessBits = new int[32];
    int accessCount;
    /** Per step, whether it writes what outlives the frame: a step a frame executed again does not take twice. */
    boolean[] outlives = new boolean[8];
    /** Whether a step runs a kernel or works on a graph buffer: from then on the executor keeps every access. */
    boolean kernels;
    /**
     * Whether a pass samples the current target's depth from another target, or passes into the current target and
     * into others are mixed: the executor then notes what is bound when it begins, which is what current means.
     */
    boolean readsCurrentDepth, rastersCurrent, rastersOther;
    int[] acquireAt = new int[8];
    int[] releaseAfter = new int[8];
    int batches;
    int draws;
    /** How often it has executed since it was built: past the first, its one-shot passes are done. */
    int executions;
    /** Set for one {@link CgExecutor#executeAgain}: passes writing a requested texture are skipped. */
    boolean keepRequested;
    /** Executing again with values that may have moved what a damaged pass drew: every pass draws whole. */
    boolean wholePasses;
    /** Copies of the clip tables its passes' recordings filled, by recording: a frame refers back to no recording. */
    private final IdentityHashMap<CgClipTable, CgClipTable> clips = new IdentityHashMap<>();
    private final List<CgClipTable> clipPool = new ArrayList<>();
    private int clipsUsed;
    private final IdentityHashMap<CgShapeTable, CgShapeTable> shapes = new IdentityHashMap<>();
    private final List<CgShapeTable> shapePool = new ArrayList<>();
    private int shapesUsed;
    /** The same for property trees: a palette per recording, which the frame's values change at execution. */
    private final IdentityHashMap<CgRecording, CgPalette> palettes = new IdentityHashMap<>();
    private final List<CgPalette> palettePool = new ArrayList<>();
    private int palettesUsed;

    CgFrame() {
        for (int k = 0; k < KINDS; k++) instances[k] = new float[CgInstanceKind.of(k).floats() * 256];
    }

    /** Empties it for the next build, keeping its storage. */
    void clear() {
        Arrays.fill(steps, 0, stepCount, null);
        stepCount = 0;
        Arrays.fill(accessView, 0, accessCount, null);
        accessCount = 0;
        kernels = false;
        readsCurrentDepth = rastersCurrent = rastersOther = false;
        bindings.reset();
        Arrays.fill(instanceFloats, 0);
        transients.clear();
        batches = 0;
        draws = 0;
        executions = 0;
        clips.clear();
        clipsUsed = 0;
        shapes.clear();
        shapesUsed = 0;
        palettes.clear();
        palettesUsed = 0;
    }

    /** This frame's copy of {@code table}, made on first ask in a build. */
    CgClipTable clipsOf(CgClipTable table) {
        CgClipTable copy = clips.get(table);
        if (copy != null) return copy;
        if (clipsUsed == clipPool.size()) clipPool.add(new CgClipTable());
        copy = clipPool.get(clipsUsed++);
        copy.copyFrom(table);
        clips.put(table, copy);
        return copy;
    }

    /** This frame's copy of {@code table}, made on first ask in a build. */
    CgShapeTable shapesOf(CgShapeTable table) {
        CgShapeTable copy = shapes.get(table);
        if (copy != null) return copy;
        if (shapesUsed == shapePool.size()) shapePool.add(new CgShapeTable());
        copy = shapePool.get(shapesUsed++);
        copy.copyFrom(table);
        shapes.put(table, copy);
        return copy;
    }

    /** This frame's palette for {@code recording}, drawn with {@code values}, made on first ask in a build. */
    CgPalette paletteOf(CgRecording recording, @Nullable CgPropertyValues values) {
        CgPalette palette = palettes.get(recording);
        if (palette != null) return palette;
        if (palettesUsed == palettePool.size()) palettePool.add(new CgPalette());
        palette = palettePool.get(palettesUsed++);
        palette.copyFrom(recording.spatial(), recording.effects(), values);
        palettes.put(recording, palette);
        return palette;
    }

    /** Room for {@code count} steps. */
    void steps(int count) {
        if (steps.length < count) {
            steps = Arrays.copyOf(steps, Math.max(count, steps.length * 2));
            rasters = Arrays.copyOf(rasters, steps.length);
            computes = Arrays.copyOf(computes, steps.length);
            outlives = new boolean[steps.length];
        }
        stepCount = count;
    }

    /** Room for {@code steps} steps' access lists, emptied. */
    void accessFrom(int steps) {
        if (accessFrom.length < steps + 1) accessFrom = new int[Math.max(steps + 1, accessFrom.length * 2)];
        accessCount = 0;
    }

    /** {@code bits} on {@code view} in the step whose list starts at {@code start}, joined with an earlier entry. */
    void access(int start, CgGraphResource view, int bits) {
        for (int i = start; i < accessCount; i++) {
            if (accessView[i] == view) {
                accessBits[i] |= bits;
                return;
            }
        }
        if (accessCount == accessView.length) {
            accessView = Arrays.copyOf(accessView, accessCount * 2);
            accessBits = Arrays.copyOf(accessBits, accessCount * 2);
        }
        accessView[accessCount] = view;
        accessBits[accessCount++] = bits;
    }

    /** The packed form of compute step {@code s}, kept between frames. */
    Compute compute(int s) {
        Compute compute = computes[s];
        if (compute == null) computes[s] = compute = new Compute();
        return compute;
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

    /** The pass at {@code step}. */
    public CgPass pass(int step) {
        return steps[step];
    }

    /** How many resources step {@code step} accesses. */
    public int accesses(int step) {
        return accessFrom[step + 1] - accessFrom[step];
    }

    /** Step {@code step}'s {@code i}th resource, as named: a history's previous version stays itself. */
    public CgGraphResource accessed(int step, int i) {
        return accessView[accessFrom[step] + i];
    }

    /** How step {@code step} accesses its {@code i}th resource: {@code CgAccess} bits. */
    public int accessBits(int step, int i) {
        return accessBits[accessFrom[step] + i];
    }

    /** Transients, in the order the executor takes their storage. */
    public List<CgGraphResource> transients() {
        return Collections.unmodifiableList(transients);
    }

    /** The step transient {@code i} takes its storage before, and the one it gives it back after. */
    public int acquiredAt(int i) {
        return acquireAt[i];
    }

    public int releasedAfter(int i) {
        return releaseAfter[i];
    }

    /** A compute pass, packed: per dispatch, the frame's snapshot of its blocks and samplers. */
    static final class Compute {
        int[] bindings = new int[4];
        int count;

        void size(int dispatches) {
            if (bindings.length < dispatches) bindings = new int[Math.max(dispatches, bindings.length * 2)];
            count = dispatches;
        }
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
        /** Per batch, an index into its pass's scissor rects, or a {@code CgRasterPass} sentinel. */
        int[] scissor = new int[16];
        /** Per batch, its GPU group's label, or -1 for its material's. */
        int[] group = new int[16];
        CgMesh[] mesh = new CgMesh[16];
        /** Per batch, the range of its mesh: submesh (-1 for all, whole), first, count (-1 to the end). */
        int[] submesh = new int[16], rangeFirst = new int[16], rangeCount = new int[16];
        /**
         * Per batch: the buffer an indirect batch's count is in, else null; the count's byte offset; its mode's ordinal
         * with the factor above it. An indirect batch is one draw.
         */
        CgBufferHandle[] counts = new CgBufferHandle[16];
        long[] countOffsets = new long[16];
        int[] countModes = new int[16];
        /** Per batch, the buffer a batch of {@code objects()} reads its records from, else null. Such a batch is one draw. */
        CgBufferHandle[] objects = new CgBufferHandle[16];
        /** Per batch, the buffer a batch of {@code buffer()} reads in place of an engine buffer, and where; else null. */
        CgBufferHandle[] buffers = new CgBufferHandle[16];
        CgBindingPoints.Binding[] bufferAt = new CgBindingPoints.Binding[16];
        /** Per batch, the {@code CgTargetCopy} bits of what to copy from the target before drawing it; 0 for none. */
        int[] copyBefore = new int[16];
        /**
         * Per batch copying colour, the rect it copies in GL pixels from the bottom left, x0, y0, x1, y1; x1 below 0
         * for the whole target. Depth is always copied whole.
         */
        int[] copyRect = new int[16 * 4];
        /** Its indirect batches: the commands the executor builds before the pass begins. */
        int indirects;
        /** Bits by kind ordinal: the kinds its batches draw, so their buffers are bound once per pass. */
        int kinds;
        /** Recorded draws its batches cover. */
        int draws;
        /** The frame's copy of its recording's clip table. */
        CgClipTable clips;
        CgShapeTable shapes;
        /** The frame's palette of its recording's property trees. */
        CgPalette palette;

        void size(int batches) {
            if (pipeline.length < batches) {
                int n = Math.max(batches, pipeline.length * 2);
                pipeline = new int[n];
                binding = new int[n];
                kind = new int[n];
                first = new int[n];
                instances = new int[n];
                scissor = new int[n];
                group = new int[n];
                mesh = new CgMesh[n];
                submesh = new int[n];
                rangeFirst = new int[n];
                rangeCount = new int[n];
                counts = new CgBufferHandle[n];
                countOffsets = new long[n];
                countModes = new int[n];
                objects = new CgBufferHandle[n];
                buffers = new CgBufferHandle[n];
                bufferAt = new CgBindingPoints.Binding[n];
                copyBefore = new int[n];
                copyRect = new int[n * 4];
            } else {
                Arrays.fill(mesh, 0, count, null);
                Arrays.fill(counts, 0, count, null);
                Arrays.fill(objects, 0, count, null);
                Arrays.fill(buffers, 0, count, null);
                Arrays.fill(bufferAt, 0, count, null);
            }
            count = batches;
            kinds = 0;
            indirects = 0;
            Arrays.fill(instances, 0, batches, 0);
            Arrays.fill(copyBefore, 0, batches, 0);
        }
    }
}
