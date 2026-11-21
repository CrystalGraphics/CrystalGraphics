package com.crystalgraphics.render.ui;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.render.graph.CgDrawData;
import com.crystalgraphics.render.graph.CgPropertyValues;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.paint.CgGradientTable;
import com.crystalgraphics.render.ui.property.CgClipTree;
import com.crystalgraphics.render.ui.property.CgEffectTree;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.property.CgSpatialTree;

/**
 * The canvas's tables in one recording: its property trees (spatial, effect, clip), Chromium's {@code PropertyTrees},
 * and its gradients. Chunks name their entries; a frame built from the recording keeps a frozen copy and binds it on
 * each of the recording's raster passes whose pipelines read the {@code palette}, {@code clip} or {@code gradient}
 * engine buffer.
 *
 * <pre>{@code
 * CgCanvasTables tables = CgCanvasTables.of(recording);   // made on first ask, reset with the recording
 * int node = tables.spatial().add(parent, 1f, 0f, 0f, 1f, x, y, true);
 * int clip = tables.clips().add(0, pose, targetHeight, 0, 0, 200, 120, EIGHTS, EIGHTS, null);
 * }</pre>
 *
 * <ul>
 *   <li>An entry belongs to its recording's tables: an index means nothing in another recording, or after a reset.</li>
 *   <li>Filled on the recording's thread with no GL; uploaded and bound by the executor per raster pass.</li>
 * </ul>
 */
public final class CgCanvasTables implements CgDrawData {

    private final CgSpatialTree spatial = new CgSpatialTree();
    private final CgEffectTree effects = new CgEffectTree();
    private final CgClipTree clips = new CgClipTree();
    private final CgGradientTable gradients = new CgGradientTable();
    /** Frozen copies a recycled frame gave back. */
    private final List<Frozen> pool = new ArrayList<>();

    private CgCanvasTables() {
    }

    /** {@code recording}'s canvas tables, made and attached on first ask. */
    public static CgCanvasTables of(CgRecording recording) {
        CgDrawData d = recording.drawData();
        if (d == null) {
            CgCanvasTables tables = new CgCanvasTables();
            recording.drawData(tables);
            return tables;
        }
        if (!(d instanceof CgCanvasTables t)) throw new IllegalStateException(recording + " carries draw data of another kind: " + d);
        return t;
    }

    /** The spatial nodes its chunks are positioned in. */
    public CgSpatialTree spatial() {
        return spatial;
    }

    /** The opacity groups its chunks are drawn in. */
    public CgEffectTree effects() {
        return effects;
    }

    /** The rounded clips its chunks name. */
    public CgClipTree clips() {
        return clips;
    }

    /** The gradients its draws name. */
    public CgGradientTable gradients() {
        return gradients;
    }

    @Override
    public void reset() {
        spatial.reset();
        effects.reset();
        clips.reset();
        gradients.reset();
    }

    @Override
    public int domain(int node) {
        return spatial.domain(node);
    }

    @Override
    public void boundsInDomain(int node, float x0, float y0, float x1, float y1, float[] out) {
        spatial.boundsInDomain(node, x0, y0, x1, y1, out);
    }

    @Override
    public CgDrawData.Frozen freeze(@Nullable CgPropertyValues values) {
        Frozen f;
        synchronized (pool) {
            f = pool.isEmpty() ? new Frozen(this) : pool.remove(pool.size() - 1);
        }
        f.palette.copyFrom(spatial, effects, values);
        f.clips.copyFrom(clips);
        f.gradients.copyFrom(gradients);
        return f;
    }

    /** A frame's copy: the palette resolves its nodes against the values at each execution. */
    private static final class Frozen implements CgDrawData.Frozen {
        final CgCanvasTables owner;
        final CgPalette palette = new CgPalette();
        final CgClipTree clips = new CgClipTree();
        final CgGradientTable gradients = new CgGradientTable();

        Frozen(CgCanvasTables owner) {
            this.owner = owner;
        }

        @Override
        public void bind(Binder pass) {
            boolean clip = pass.reads(Index.CLIP), gradient = pass.reads(Index.GRADIENT);
            if (clip) pass.bind(clips.prepare());
            if (gradient) pass.bind(gradients.prepare());
            // Placed whether read or not: a scissor under a node maps through it
            palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), pass.targetHeight());
            if (clip || pass.reads(Index.PALETTE)) pass.bind(palette.prepare());
        }

        @Override
        public void scissorOf(int node, float[] boxes, int at, int[] out) {
            palette.scissorOf(node, boxes, at, out);
        }

        @Override
        public void release() {
            synchronized (owner.pool) {
                owner.pool.add(this);
            }
        }
    }

    /** The engine buffers it supplies, by registry index: looked up at the first bind, when the registry is full. */
    private static final class Index {
        static final int PALETTE = CgEngineBufferRegistry.indexOf("palette");
        static final int CLIP = CgEngineBufferRegistry.indexOf("clip");
        static final int GRADIENT = CgEngineBufferRegistry.indexOf("gradient");
    }
}
