package com.crystalgraphics.render.world;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPassRecorder;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.render.CgTextCapture;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.context.CgTextRenderContext;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Text in the world: labels {@link CgWorldRenderer#text} queues for the frame, recorded into each transparent world
 * stage after its transparent draws, depth-tested against the scene and never writing depth, as Minecraft's name tags.
 * Each label is a queued {@link CgTextRenderer.Draw} of one renderer, drawn at every firing under that firing's camera.
 *
 * <p>Labels are retained: a label's glyphs are captured once ({@link CgTextRenderer#capture}) into a buffer per batch
 * shared by every label, and drawn from there by {@code text.shader}'s {@code WORLD_LABEL} variant, each placed by its
 * label's matrix. A frame whose labels changed nothing but where they stand writes one matrix a label.</p>
 */
public final class CgWorldText {

    /** Where a label's point sits on it: the middle of the block, its top or its bottom. */
    private static final float CENTRE = 0.5f;
    /** Off draws every label every frame, as any text: the comparison for the retained path. */
    private static final boolean RETAINED = !"false".equals(System.getProperty("crystalgraphics.text.retainedLabels"));

    private static final int RECORD = CgQuadRenderer.Record.FLOATS;
    private static final int NODE = CgQuadRenderer.Record.NODE;
    private static final int OBJECT = CgInstanceKind.OBJECT.format().getFloatCount();
    /** The ranks a label's depth pass draws, from 0: the lines under the text, the text, the lines over it. */
    private static final int TEXT_RANKS = 2;
    /** Frames a label gone from the frame keeps its ranges for. */
    private static final int KEEP_HIDDEN = 300;
    /** A batch is compacted once this many records, and half of it, are holes. */
    private static final int COMPACT_AFTER = 4096;

    private final List<Label> labels = new ArrayList<>();
    private int count;
    private final Consumer<CgTextRenderer.Draw> queue = this::submitted;
    private final CgPassRecorder recorder = new CgPassRecorder();
    private final PoseStack pose = new PoseStack();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f place = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    /** The immediate path's draw order: squared distance bits over the label's index. */
    private long[] order = new long[0];
    private final Quaternionf facing = new Quaternionf();
    private CgTextRenderer renderer;

    // Retained: what each label drew last, the batches its records are in, and every label's matrix.
    private final List<Kept> kept = new ArrayList<>();
    private int keptCount;
    private final List<Batch> batches = new ArrayList<>();
    private float[] matrices = new float[0];
    private CgGraphBuffer matrixBuffer;
    private int matrixCapacity;
    private final CgChunkBuilder chunks = new CgChunkBuilder(null);

    CgWorldText() {
    }

    /** The next label to fill: one of the frame's, reused frame to frame. */
    Label next() {
        if (renderer == null) {
            renderer = CgTextRenderer.createManualSized();
            renderer.context(CgTextRenderContext.world(projection, 1, 1));
        }
        if (count == labels.size()) labels.add(new Label(renderer.queuedDraw(queue)));
        return labels.get(count).reset();
    }

    /** A label's draw submitted: kept if it is the one {@link #next} handed out, else ignored. */
    private void submitted(CgTextRenderer.Draw draw) {
        if (count < labels.size() && labels.get(count).draw == draw) count++;
    }

    void clear() {
        for (int i = 0; i < count; i++) labels.get(i).draw.reset();
        count = 0;
    }

    /** Records the frame's labels into {@code stage}'s target, under its view. Render thread. */
    void record(CgStageFrame stage, CgHostView view) {
        if (count == 0 && keptCount == 0) return;
        int w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        projection.set(view.projection());
        renderer.context().updateProjection(projection, w, h);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "world.text")) {
            if (RETAINED) {
                recordRetained(stage, view);
                return;
            }
            recorder.recordInto(stage.recording(), stage.target(), CgLoad.load(), stage.constants());
            renderer.sink(recorder);
            try {
                // Far to near, so a nearer label's text covers a farther one's: labels write no depth here.
                if (order.length < count) order = new long[count * 2];
                for (int i = 0; i < count; i++) {
                    Label l = labels.get(i);
                    double dx = l.x - view.x(), dy = l.y - view.y(), dz = l.z - view.z();
                    order[i] = (long) Float.floatToIntBits((float) (dx * dx + dy * dy + dz * dz)) << 32 | i;
                }
                Arrays.sort(order, 0, count);
                renderer.beginBatch();
                for (int i = count - 1; i >= 0; i--) draw(labels.get((int) order[i]), view);
                renderer.endBatch();
            } finally {
                renderer.sink(null);
                recorder.stop();
            }
        }
    }

    private void draw(Label label, CgHostView view) {
        CgTextRenderer.Draw d = label.draw.pose(null);
        int px = d.basePx();
        if (px <= 0) return;
        CgTextLayout layout = d.measure();
        Matrix4f m = pose.last().pose();
        placement(m, label, view, layout, px);
        renderer.context().updateProjectedSize(m, projection, px);
        renderer.drawQueued(d.pose(pose));
    }

    /** Into {@code m}: view, the label's offset from the eye, its turn or the view's undone, its size and anchor. */
    private void placement(Matrix4f m, Label label, CgHostView view, CgTextLayout layout, int px) {
        float scale = label.height / px;
        m.set(view.view()).translate((float) (label.x - view.x()), (float) (label.y - view.y()), (float) (label.z - view.z()));
        if (label.rotation != null) {
            m.rotate(label.rotation);
        } else {
            // Undo the view's turn, so the label lies in the eye's plane: a billboard.
            view.view().getNormalizedRotation(facing).conjugate();
            m.rotate(facing);
        }
        m.scale(scale, -scale, scale)
                .translate(-layout.totalWidth() * label.anchorX, -layout.totalHeight() * (1f - label.anchorY), 0f);
    }

    // ── Retained ────────────────────────────────────────────────────────────

    private void recordRetained(CgStageFrame stage, CgHostView view) {
        CgRecording recording = stage.recording();
        long evictions = CgGlyphAtlas.evictions();
        int captured = 0;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "world.text.content")) {
            for (int i = 0; i < count; i++) {
                Label label = labels.get(i);
                if (i == keptCount) {
                    if (keptCount == kept.size()) kept.add(new Kept(renderer.retainedDraw()));
                    keptCount++;
                }
                Kept k = kept.get(i);
                if (k.whole && k.capture.evictions() == evictions && k.drawn.sameAs(label.draw)) {
                    // Back after a gap: its ranges were zeroed, its capture kept.
                    if (k.hidden > 0) {
                        place(i, k);
                        k.hidden = 0;
                    }
                    continue;
                }
                k.hidden = 0;
                k.drawn.set(label.draw);
                try (CgTrace.Zone capture = CgTrace.zone(CgChannels.WORLD, "world.text.capture")) {
                    k.whole = renderer.capture(label.draw, k.capture);
                }
                place(i, k);
                captured++;
            }
        }
        CgTrace.add(CgChannels.WORLD, "world.text.captured", captured);
        // A label gone this frame keeps its ranges, zeroed, for a while: one back with the same draw rewrites them
        // without a capture. Gone longer, its ranges become holes.
        for (int i = count; i < kept.size(); i++) {
            Kept k = kept.get(i);
            if (k.ranges == 0) continue;
            if (k.hidden == 0) {
                for (int r = 0; r < k.ranges; r++) k.batch[r].clear(k.offset[r], k.capacity[r], false);
            }
            if (++k.hidden > KEEP_HIDDEN) {
                vacate(k, 0);
                k.drawn.reset();
                k.whole = false;
                k.hidden = 0;
            }
        }
        keptCount = count;
        for (int b = 0; b < batches.size(); b++) {
            Batch batch = batches.get(b);
            if (batch.holes > COMPACT_AFTER && batch.holes * 2 > batch.used) compact(batch);
        }

        if (matrices.length < count * OBJECT) matrices = Arrays.copyOf(matrices, Math.max(count, 64) * 2 * OBJECT);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "world.text.place")) {
            Matrix4fc v = view.view();
            for (int i = 0; i < count; i++) {
                Label label = labels.get(i);
                CgTextLayout layout = kept.get(i).capture.layout();
                int px = label.draw.basePx();
                if (layout == null || px <= 0) {
                    Arrays.fill(matrices, i * OBJECT, i * OBJECT + 16, 0f);
                    continue;
                }
                if (label.rotation != null) {
                    placement(place, label, view, layout, px);
                } else {
                    // A billboard undoes the view's turn, which leaves the eye-space point, the size and the anchor.
                    float s = label.height / px;
                    eye.set((float) (label.x - view.x()), (float) (label.y - view.y()), (float) (label.z - view.z()));
                    v.transformPosition(eye);
                    place.translation(eye).scale(s, -s, s)
                            .translate(-layout.totalWidth() * label.anchorX, -layout.totalHeight() * (1f - label.anchorY), 0f);
                }
                place.get(matrices, i * OBJECT);
            }
        }
        if (count > matrixCapacity) {
            matrixCapacity = Math.max(count, 64) * 2;
            if (matrixBuffer != null) recording.release(matrixBuffer);
            matrixBuffer = CgGraphBuffer.persistent("world.text.labels",
                    CgBufferDesc.elements(matrixCapacity, OBJECT * Float.BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        if (count > 0) recording.update(matrixBuffer, 0, matrices, 0, count * OBJECT);

        for (int b = 0; b < batches.size(); b++) batches.get(b).upload(recording);

        recorder.recordInto(recording, stage.target(), CgLoad.load(), stage.constants());
        try {
            chunks.bindings(recorder.bindings()).begin(0, 0, 0);
            // Text and its lines into depth first, so a nearer label's text hides a farther one's.
            for (int b = 0; b < batches.size(); b++) {
                Batch batch = batches.get(b);
                if (batch.rank >= 0 && batch.rank <= TEXT_RANKS && batch.used > batch.holes) {
                    renderer.drawCapturedDepth(chunks, batch.key, batch.gpu, batch.used, matrixBuffer);
                }
            }
            for (int b = 0; b < batches.size(); b++) {
                Batch batch = batches.get(b);
                if (batch.used > batch.holes) renderer.drawCaptured(chunks, batch.key, batch.gpu, batch.used, matrixBuffer);
            }
            recorder.add(chunks.end());
        } finally {
            recorder.stop();
        }
    }

    /** Writes label {@code i}'s new capture into the batches, in its old ranges where they fit. */
    private void place(int i, Kept k) {
        CgTextCapture c = k.capture;
        int old = k.ranges;
        for (int b = 0; b < c.batches(); b++) {
            Batch batch = batch(c.rank(b), c.batch(b));
            int n = c.count(b);
            int r = k.find(batch, old);
            int at;
            if (r >= 0 && k.capacity[r] >= n) {
                at = k.offset[r];
                k.taken[r] = true;
            } else {
                at = batch.append(n);
                k.add(batch, at, n);
            }
            batch.write(at, c.records(b), n, i);
            int capacity = k.capacityAt(batch, at);
            if (capacity > n) batch.clear(at + n, capacity - n, false);
        }
        // Old ranges the new capture does not reuse become holes.
        int keptRanges = 0;
        for (int r = 0; r < k.ranges; r++) {
            if (r < old && !k.taken[r]) {
                k.batch[r].clear(k.offset[r], k.capacity[r], true);
                continue;
            }
            k.batch[keptRanges] = k.batch[r];
            k.offset[keptRanges] = k.offset[r];
            k.capacity[keptRanges] = k.capacity[r];
            keptRanges++;
        }
        k.ranges = keptRanges;
        Arrays.fill(k.taken, false);
    }

    /** Clears a label's ranges from {@code from} on into holes. */
    private void vacate(Kept k, int from) {
        for (int r = from; r < k.ranges; r++) k.batch[r].clear(k.offset[r], k.capacity[r], true);
        k.ranges = from;
    }

    /** The batch of {@code (rank, key)}, made in rank order, after every batch of its rank already made. */
    private Batch batch(int rank, long key) {
        int insert = batches.size();
        for (int b = 0; b < batches.size(); b++) {
            Batch batch = batches.get(b);
            if (batch.rank == rank && batch.key == key) return batch;
            if (batch.rank > rank && insert == batches.size()) insert = b;
        }
        Batch batch = new Batch(rank, key);
        batches.add(insert, batch);
        return batch;
    }

    /** Packs {@code batch}'s ranges from 0, in label order, gone labels' too, and uploads it whole. */
    private void compact(Batch batch) {
        float[] from = batch.mirror;
        float[] into = batch.spare != null && batch.spare.length >= from.length ? batch.spare : new float[from.length];
        batch.spare = from;
        int at = 0;
        for (int i = 0; i < kept.size(); i++) {
            Kept k = kept.get(i);
            for (int r = 0; r < k.ranges; r++) {
                if (k.batch[r] != batch) continue;
                System.arraycopy(from, k.offset[r] * RECORD, into, at * RECORD, k.capacity[r] * RECORD);
                k.offset[r] = at;
                at += k.capacity[r];
            }
        }
        batch.mirror = into;
        batch.used = at;
        batch.holes = 0;
        batch.dirty(0, at);
    }

    /** Drops the frame's labels, the renderer and the retained buffers: at context teardown, which frees their storage. */
    void release() {
        clear();
        labels.clear();
        kept.clear();
        keptCount = 0;
        batches.clear();
        matrixBuffer = null;
        matrixCapacity = 0;
        renderer = null;
    }

    /** One label as last captured, and where its records are. */
    private static final class Kept {
        final CgTextRenderer.Draw drawn;
        final CgTextCapture capture = new CgTextCapture();
        boolean whole;
        /** Frames it has been gone from the frame, 0 while drawn. */
        int hidden;
        int ranges;
        Batch[] batch = new Batch[4];
        int[] offset = new int[4];
        int[] capacity = new int[4];
        boolean[] taken = new boolean[4];

        Kept(CgTextRenderer.Draw drawn) {
            this.drawn = drawn;
        }

        int find(Batch b, int within) {
            for (int r = 0; r < within; r++) if (batch[r] == b && !taken[r]) return r;
            return -1;
        }

        int capacityAt(Batch b, int at) {
            for (int r = 0; r < ranges; r++) if (batch[r] == b && offset[r] == at) return capacity[r];
            throw new IllegalStateException("no range at " + at);
        }

        void add(Batch b, int at, int n) {
            if (ranges == batch.length) {
                int grown = ranges * 2;
                batch = Arrays.copyOf(batch, grown);
                offset = Arrays.copyOf(offset, grown);
                capacity = Arrays.copyOf(capacity, grown);
                taken = Arrays.copyOf(taken, grown);
            }
            batch[ranges] = b;
            offset[ranges] = at;
            capacity[ranges] = n;
            taken[ranges] = true;
            ranges++;
        }
    }

    /**
     * Every label's records of one (rank, batch), the CPU's copy and the GPU's: labels in ranges, holes zeroed (a quad
     * with no edges draws nothing), the dirty span uploaded each frame.
     */
    private static final class Batch {
        final int rank;
        final long key;
        float[] mirror = new float[RECORD * 256];
        /** The array the last compaction packed from, reused by the next. */
        float[] spare;
        /** Records up to the end of the last range, holes included: how many instances the draw has. */
        int used;
        int holes;
        CgGraphBuffer gpu;
        int capacity;
        int dirtyFrom = Integer.MAX_VALUE, dirtyTo;

        Batch(int rank, long key) {
            this.rank = rank;
            this.key = key;
        }

        int append(int n) {
            int at = used;
            used += n;
            if (mirror.length < used * RECORD) mirror = Arrays.copyOf(mirror, Math.max(used * 2, 256) * RECORD);
            return at;
        }

        /** Copies {@code n} records to {@code at}, each naming label {@code label}. */
        void write(int at, float[] records, int n, int label) {
            System.arraycopy(records, 0, mirror, at * RECORD, n * RECORD);
            float node = label;
            for (int o = at * RECORD + NODE, end = (at + n) * RECORD; o < end; o += RECORD) mirror[o] = node;
            dirty(at, at + n);
        }

        void clear(int at, int n, boolean hole) {
            Arrays.fill(mirror, at * RECORD, (at + n) * RECORD, 0f);
            if (hole) holes += n;
            dirty(at, at + n);
        }

        void dirty(int from, int to) {
            dirtyFrom = Math.min(dirtyFrom, from);
            dirtyTo = Math.max(dirtyTo, to);
        }

        void upload(CgRecording recording) {
            if (used > capacity) {
                int grown = Math.max(used + used / 2, 1024);
                CgBufferDesc desc = CgBufferDesc.elements(grown, RECORD * Float.BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
                if (gpu == null) {
                    gpu = CgGraphBuffer.persistent("world.text.glyphs", desc);
                } else {
                    gpu = recording.resize(gpu, desc);
                }
                capacity = grown;
            }
            if (dirtyFrom < dirtyTo) {
                recording.update(gpu, (long) dirtyFrom * RECORD * Float.BYTES, mirror, dirtyFrom * RECORD,
                        (dirtyTo - dirtyFrom) * RECORD);
            }
            dirtyFrom = Integer.MAX_VALUE;
            dirtyTo = 0;
        }
    }

    /**
     * One label's place in the world: a point, a height, which point of the text stands there, and a turn. How it
     * draws is its {@link CgTextRenderer.Draw}, from {@link #font}, {@link #family} or {@link #draw()}, submitted to
     * queue it. Build it from {@link CgWorldRenderer#text}.
     */
    public static final class Label {
        private final CgTextRenderer.Draw draw;
        double x, y, z;
        float height = 0.25f, anchorX = CENTRE, anchorY = CENTRE;
        Quaternionfc rotation;

        Label(CgTextRenderer.Draw draw) {
            this.draw = draw;
        }

        private Label reset() {
            draw.reset();
            x = y = z = 0.0;
            height = 0.25f;
            anchorX = anchorY = CENTRE;
            rotation = null;
            return this;
        }

        /** Where its anchor stands, absolute, in doubles. The origin unless set. */
        public Label at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** How tall a line of it stands, in blocks: its draw's {@code basePx()} maps to this. 0.25 unless set. */
        public Label height(float blocks) {
            this.height = blocks;
            return this;
        }

        /**
         * Which point of the text its position names: {@code x} 0 its left edge to 1 its right, {@code y} 0 its bottom
         * to 1 its top. The middle unless set.
         */
        public Label anchor(float x, float y) {
            this.anchorX = x;
            this.anchorY = y;
            return this;
        }

        /** Turned by {@code rotation} in the world instead of facing the camera; the text reads along +x, up +y. */
        public Label rotation(Quaternionfc rotation) {
            this.rotation = rotation;
            return this;
        }

        /** Its draw, in {@code font}: every field a {@link CgTextRenderer.Draw} has. Its {@code submit()} queues it. */
        public CgTextRenderer.Draw font(CgFont font) {
            return draw.font(font);
        }

        /** Its draw, in {@code family}, falling back across its faces; size it with {@code targetPx}. */
        public CgTextRenderer.Draw family(CgFontFamily family) {
            return draw.family(family);
        }

        /** Its draw as it stands: for a layout, whose fonts are its own. */
        public CgTextRenderer.Draw draw() {
            return draw;
        }
    }
}
