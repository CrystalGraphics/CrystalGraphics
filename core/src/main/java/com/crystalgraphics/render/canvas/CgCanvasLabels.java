package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.render.CgTextBatch;
import com.crystalgraphics.text.render.CgTextCapture;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Labels in the world kept on the GPU across frames: text at a point, facing the camera or turned as a sign, each
 * placed by a matrix of its own. Thousands of labels whose words rarely change while where they stand changes every
 * frame: a label's glyphs are captured once into buffers shared by every label and drawn from there by
 * {@code text.shader}'s {@code WORLD_LABEL} variant, and a frame that changed nothing but their places writes one
 * matrix a label. 10,000 outlined, shadowed labels draw in 3 draws at about 230 ns a label.
 *
 * <p>The world's own is {@code CgWorldRenderer.get().labels()}, drawn into every transparent world firing, depth-tested
 * as a name tag: {@code world.text(...)} queues on it.</p>
 *
 * <pre>{@code
 * CgCanvasLabels labels = CgWorldRenderer.get().labels();
 * labels.text().at(x, y + 2, z).height(0.5f).font(font).style(LABEL).text("Spawn").submit();   // each frame
 * labels.text().at(x, y, z).rotation(facingSouth).anchor(0f, 0f).family(family).targetPx(48).text(sign).submit();
 * }</pre>
 *
 * <p>Labels of your own, drawn into a canvas recording into a target under a host view:</p>
 *
 * <pre>{@code
 * CgCanvasLabels tags = new CgCanvasLabels("mymod.tags");      // once, kept
 * tags.clear();                                                // each frame, then queue them with text()
 * tags.prepare(frame.recording(), view, width, height);        // captures what changed, places, uploads
 * tags.drawDepth(canvas);                                      // a nearer label's text hides a farther one's
 * tags.draw(canvas);
 * }</pre>
 *
 * <ul>
 *   <li>{@code height} is a line's height in blocks: the draw's {@code basePx()} maps to it.</li>
 *   <li>Its draw takes colour and gradient fills, outlines, lines and shadows; an additive or material layer throws,
 *       since every record draws under one material.</li>
 *   <li>The {@code i}th label queued is the same label frame to frame: one queued in another order is captured
 *       again.</li>
 *   <li>A label is unlit and unfogged.</li>
 *   <li>A label gone for a frame keeps its records, hidden, for {@value #KEEP_HIDDEN} frames: one back with the same
 *       draw costs no capture.</li>
 *   <li>Into a float target (the HDR scene) its fills take the linear-blend correction in place of the draw's gamma
 *       ({@link CgTextBatch#blendsLinearly}), read from the canvas drawn into.</li>
 *   <li>{@link #release} at a context's teardown; nothing else frees it.</li>
 * </ul>
 */
public final class CgCanvasLabels {

    private static final int RECORD = CgQuadRecord.FLOATS;
    private static final int NODE = CgQuadRecord.NODE;
    private static final int OBJECT = CgPrimitiveTable.FLOATS;
    /** The ranks the depth pass draws, from 0: the lines under the text, its outline, its fill, the lines over it. */
    private static final int TEXT_RANKS = 3;
    /** Frames a label gone from the frame keeps its ranges for. */
    private static final int KEEP_HIDDEN = 300;
    /** A batch is compacted once this many records, and half of it, are holes. */
    private static final int COMPACT_AFTER = 4096;
    private static final Matrix4f NO_VIEW = new Matrix4f();
    /** Where a label's point sits on it: the middle of the block, its top or its bottom. */
    private static final float CENTRE = 0.5f;

    private final String name;
    private final List<Label> labels = new ArrayList<>();
    private int count;
    private final Consumer<CgTextDraw> queue = this::submitted;
    private final Matrix4f place = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    /** Captures the labels' glyphs, under the camera's projection: a label's tier is fixed, its pose its own. */
    private final CgTextBatch capturer = new CgTextBatch(null);
    private final List<Kept> kept = new ArrayList<>();
    private int keptCount;
    private final List<Batch> batches = new ArrayList<>();
    private float[] matrices = new float[0];
    private int matrixCount;
    @Nullable
    private CgGraphBuffer matrixBuffer;
    private int matrixCapacity;
    private final CgChunkBuilder chunks = new CgChunkBuilder(null);
    private long evictions;
    private int captured;

    /** @param name what its buffers are called in a trace */
    public CgCanvasLabels(String name) {
        this.name = name;
    }

    /**
     * The next label this frame: place it, then {@code font(...)}, {@code family(...)} or {@code draw()} gives its
     * {@link CgTextDraw}, whose {@code submit()} queues it. Render thread.
     */
    public Label text() {
        if (count == labels.size()) labels.add(new Label(CgTextDraw.queued(queue)));
        return labels.get(count).reset();
    }

    /** A label's draw submitted: kept if it is the one {@link #text} handed out, else ignored. */
    private void submitted(CgTextDraw draw) {
        if (count < labels.size() && labels.get(count).draw == draw) count++;
    }

    /** Drops the frame's labels: the next frame queues its own. Their records stay, hidden, for a label that returns. */
    public void clear() {
        for (int i = 0; i < count; i++) labels.get(i).draw.reset();
        count = 0;
    }

    /**
     * Captures what changed, places every label under {@code view} and uploads both into {@code recording}: once a
     * firing, before {@link #drawDepth} and {@link #draw}. {@code width} and {@code height} are the target's pixels.
     */
    public void prepare(CgRecording recording, CgHostView view, int width, int height) {
        capturer.camera(view.projection(), NO_VIEW, width, height);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "world.text.content")) {
            evictions = CgGlyphAtlas.evictions();
            captured = 0;
            for (int i = 0; i < count; i++) {
                Label label = labels.get(i);
                CgTextLayout layout = capture(i, label.draw);
                int px = label.draw.basePx();
                pose(i, layout == null || px <= 0 ? null : placement(label, view, layout, px));
            }
        }
        upload(count, recording);
    }

    /** Label to view space: its offset from the eye, turned or facing the eye, its size and anchor. */
    private Matrix4f placement(Label label, CgHostView view, CgTextLayout layout, int px) {
        float scale = label.height / px;
        float dx = (float) (label.x - view.x()), dy = (float) (label.y - view.y()), dz = (float) (label.z - view.z());
        if (label.rotation != null) {
            place.set(view.view()).translate(dx, dy, dz).rotate(label.rotation);
        } else {
            // A billboard undoes the view's turn, which leaves the eye-space point, the size and the anchor.
            view.view().transformPosition(eye.set(dx, dy, dz));
            place.translation(eye);
        }
        return place.scale(scale, -scale, scale)
                .translate(-layout.totalWidth() * label.anchorX, -layout.totalHeight() * (1f - label.anchorY), 0f);
    }

    /**
     * Label {@code i} draws {@code draw} this frame: captured again where it differs from what it drew last, the last
     * capture was incomplete or the atlas evicted since. The layout it draws; null where it has none.
     */
    @Nullable
    private CgTextLayout capture(int i, CgTextDraw draw) {
        while (i >= keptCount) {
            if (keptCount == kept.size()) kept.add(new Kept(CgTextDraw.detached()));
            keptCount++;
        }
        Kept k = kept.get(i);
        if (k.whole && k.capture.evictions() == evictions && k.drawn.sameAs(draw)) {
            // Back after a gap: its ranges were zeroed, its capture kept.
            if (k.hidden > 0) {
                write(i, k);
                k.hidden = 0;
            }
        } else {
            k.hidden = 0;
            k.drawn.set(draw);
            try (CgTrace.Zone capture = CgTrace.zone(CgChannels.WORLD, "world.text.capture")) {
                k.whole = capturer.capture(draw, k.capture);
            }
            write(i, k);
            captured++;
        }
        return k.capture.layout();
    }

    /** Label {@code i}'s matrix: null hides it this frame. */
    private void pose(int i, @Nullable Matrix4fc pose) {
        if (matrices.length < (i + 1) * OBJECT) matrices = Arrays.copyOf(matrices, Math.max(i + 1, 64) * 2 * OBJECT);
        if (pose == null) {
            Arrays.fill(matrices, i * OBJECT, i * OBJECT + 16, 0f);
        } else {
            pose.get(matrices, i * OBJECT);
        }
    }

    /**
     * Closes the frame at {@code count} labels: those from it on are hidden, and gone for good after
     * {@value #KEEP_HIDDEN} frames. Uploads what changed, and every label's place.
     */
    private void upload(int count, CgRecording recording) {
        CgTrace.add(CgChannels.WORLD, "world.text.captured", captured);
        for (int i = count; i < kept.size(); i++) {
            Kept k = kept.get(i);
            if (k.ranges == 0) continue;
            if (k.hidden == 0) {
                for (int r = 0; r < k.ranges; r++) k.batch[r].clear(k.offset[r], k.capacity[r], false);
            }
            if (++k.hidden > KEEP_HIDDEN) {
                vacate(k);
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

        matrixCount = count;
        if (count > matrixCapacity) {
            matrixCapacity = Math.max(count, 64) * 2;
            if (matrixBuffer != null) recording.release(matrixBuffer);
            matrixBuffer = CgGraphBuffer.persistent(name + ".labels",
                    CgBufferDesc.elements(matrixCapacity, OBJECT * Float.BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        }
        if (count > 0) recording.update(matrixBuffer, 0, matrices, 0, count * OBJECT);
        for (int b = 0; b < batches.size(); b++) batches.get(b).upload(recording, name);
    }

    /** Whether there is anything to draw: a label this frame, or one hidden and kept. */
    public boolean any() {
        return matrixCount > 0;
    }

    /**
     * The labels' text and lines into {@code canvas}'s depth alone, where half covered or more: before any colour, so a
     * nearer label's text hides a farther one's whatever their order.
     */
    public void drawDepth(CgCanvas canvas) {
        record(canvas, true);
    }

    /** The labels' colour into {@code canvas}, after {@link #drawDepth}. */
    public void draw(CgCanvas canvas) {
        record(canvas, false);
    }

    private void record(CgCanvas canvas, boolean depth) {
        if (matrixCount == 0) return;
        chunks.bindings(canvas.bindings()).begin(0, 0, 0);
        boolean linear = CgTextBatch.blendsLinearly(canvas.target());
        for (int b = 0; b < batches.size(); b++) {
            Batch batch = batches.get(b);
            if (batch.used <= batch.holes) continue;
            if (!depth) {
                CgTextBatch.drawCaptured(chunks, batch.key, batch.gpu, batch.used, matrixBuffer, linear);
            } else if (batch.rank >= 0 && batch.rank <= TEXT_RANKS) {
                CgTextBatch.drawCapturedDepth(chunks, batch.key, batch.gpu, batch.used, matrixBuffer);
            }
        }
        canvas.add(chunks.end());
    }

    /** Drops every label and its buffers: at context teardown, which frees their storage. */
    public void release() {
        clear();
        labels.clear();
        kept.clear();
        keptCount = 0;
        batches.clear();
        matrixBuffer = null;
        matrixCapacity = 0;
        matrixCount = 0;
    }

    /** Writes label {@code i}'s capture into the batches, in its old ranges where they fit. */
    private void write(int i, Kept k) {
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

    /** Clears a label's ranges into holes. */
    private static void vacate(Kept k) {
        for (int r = 0; r < k.ranges; r++) k.batch[r].clear(k.offset[r], k.capacity[r], true);
        k.ranges = 0;
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

    /** One label as last captured, and where its records are. */
    private static final class Kept {
        final CgTextDraw drawn;
        final CgTextCapture capture = new CgTextCapture();
        boolean whole;
        /** Frames it has been gone from the frame, 0 while drawn. */
        int hidden;
        int ranges;
        Batch[] batch = new Batch[4];
        int[] offset = new int[4];
        int[] capacity = new int[4];
        boolean[] taken = new boolean[4];

        Kept(CgTextDraw drawn) {
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

        void upload(CgRecording recording, String name) {
            if (used > capacity) {
                int grown = Math.max(used + used / 2, 1024);
                CgBufferDesc desc = CgBufferDesc.elements(grown, RECORD * Float.BYTES, CgBufferUsage.STORAGE, CgBufferUsage.COPY);
                gpu = gpu == null ? CgGraphBuffer.persistent(name + ".glyphs", desc) : recording.resize(gpu, desc);
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
     * draws is its {@link CgTextDraw}, from {@link #font}, {@link #family} or {@link #draw()}, submitted to
     * queue it. Build it from {@link #text}, or {@code CgWorldRenderer.text}.
     */
    public static final class Label {
        private final CgTextDraw draw;
        double x, y, z;
        float height = 0.25f, anchorX = CENTRE, anchorY = CENTRE;
        Quaternionfc rotation;

        Label(CgTextDraw draw) {
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

        /** Its draw, in {@code font}: every field a {@link CgTextDraw} has. Its {@code submit()} queues it. */
        public CgTextDraw font(CgFont font) {
            return draw.font(font);
        }

        /** Its draw, in {@code family}, falling back across its faces; size it with {@code targetPx}. */
        public CgTextDraw family(CgFontFamily family) {
            return draw.family(family);
        }

        /** Its draw as it stands: for a layout, whose fonts are its own. */
        public CgTextDraw draw() {
            return draw;
        }
    }
}
