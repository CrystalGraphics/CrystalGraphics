package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.render.property.CgPropertyValues;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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
    int stepCount;
    final CgBindingTable bindings = new CgBindingTable();
    final float[][] instances = new float[KINDS][];
    final int[] instanceFloats = new int[KINDS];
    final List<CgGraphTexture> transients = new ArrayList<>();
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
        /** Per batch, an index into its pass's scissor rects, or a {@code CgRasterPass} sentinel. */
        int[] scissor = new int[16];
        CgMesh[] mesh = new CgMesh[16];
        /** Per batch, the range of its mesh: submesh (-1 for all, whole), first, count (-1 to the end). */
        int[] submesh = new int[16], rangeFirst = new int[16], rangeCount = new int[16];
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
                mesh = new CgMesh[n];
                submesh = new int[n];
                rangeFirst = new int[n];
                rangeCount = new int[n];
            } else {
                Arrays.fill(mesh, 0, count, null);
            }
            count = batches;
            kinds = 0;
            Arrays.fill(instances, 0, batches, 0);
        }
    }
}
