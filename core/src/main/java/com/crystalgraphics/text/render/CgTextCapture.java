package com.crystalgraphics.text.render;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.render.ui.element.CgQuadRecord;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * One draw's quads kept rather than drawn, as {@link CgTextBatch#capture} records them: batch by batch, each a run of
 * records in {@link CgQuadRecord}'s layout, in the draw's own space. For text drawn frame after frame under a
 * pose that moves while its content stays: keep the records in a buffer and draw them with
 * {@link CgTextBatch#drawCaptured}, which costs nothing per glyph. {@code CgSceneTextRenderer}'s texts are these.
 *
 * <pre>{@code
 * CgTextCapture kept = new CgTextCapture();
 * boolean whole = text.capture(draw, kept);        // false: a glyph or shadow is still building, capture again later
 * for (int b = 0; b < kept.batches(); b++) {
 *     float[] records = kept.records(b);            // kept.count(b) records, Record.FLOATS floats each
 *     long batch = kept.batch(b);                   // what drawCaptured draws them under, with kept.look(b)
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Batches are in paint order. Captures merged into one buffer per batch keep their layering if their batches
 *       are drawn by {@link #rank} first: every capture's shadows, then every capture's text.</li>
 *   <li>A record's {@code node} is the caller's: {@code drawCaptured} reads it as the record's label.</li>
 *   <li>Records name atlas places: capture again once {@code CgGlyphAtlas.evictions()} moves past
 *       {@link #evictions()}.</li>
 * </ul>
 */
public final class CgTextCapture {

    /**
     * How a batch's glyphs are painted: laid over the scene or added to it, in the text material or a layer's own
     * ({@code CgTextPaint.material}). What {@link CgTextBatch#drawCaptured} draws a batch under, with its key.
     */
    public record Look(boolean additive, @Nullable CgMaterialDefinition material) {
        /** A colour or gradient layer laid over. */
        public static final Look OVER = new Look(false, null);
        /** A colour or gradient layer added: a glow. */
        public static final Look ADDED = new Look(true, null);

        static Look of(boolean additive, @Nullable CgMaterialDefinition material) {
            if (material == null) return additive ? ADDED : OVER;
            return new Look(additive, material);
        }
    }

    private int batches;
    private int[] ranks = new int[4];
    private long[] keys = new long[4];
    private Look[] looks = new Look[4];
    private float[][] records = new float[4][];
    private int[] counts = new int[4];
    @Nullable
    private CgTextLayout layout;
    private long evictions;

    /** How many batches it holds. */
    public int batches() {
        return batches;
    }

    /** Batch {@code b}'s paint rank: shadows below 0, the text from 0. Draw lower ranks first. */
    public int rank(int b) {
        return ranks[b];
    }

    /** Batch {@code b}'s material state, opaque: what {@link CgTextBatch#drawCaptured} takes. */
    public long batch(int b) {
        return keys[b];
    }

    /** Batch {@code b}'s look: its blend and material. */
    public Look look(int b) {
        return looks[b];
    }

    /** Batch {@code b}'s records, {@link #count} of them from the start; the array is the capture's. */
    public float[] records(int b) {
        return records[b];
    }

    /** How many records batch {@code b} holds. */
    public int count(int b) {
        return counts[b];
    }

    /** The layout it drew: its size places an anchor. Null before a capture, or for one that drew nothing. */
    @Nullable
    public CgTextLayout layout() {
        return layout;
    }

    /** {@code CgGlyphAtlas.evictions()} when it was captured. */
    public long evictions() {
        return evictions;
    }

    void begin(long evictions) {
        batches = 0;
        layout = null;
        this.evictions = evictions;
    }

    void layout(CgTextLayout layout) {
        this.layout = layout;
    }

    /** Appends a batch: {@code count} records copied from record {@code from} of {@code src}. */
    void add(int rank, long key, Look look, float[] src, int from, int count) {
        if (batches == keys.length) {
            int capacity = batches * 2;
            ranks = Arrays.copyOf(ranks, capacity);
            keys = Arrays.copyOf(keys, capacity);
            looks = Arrays.copyOf(looks, capacity);
            records = Arrays.copyOf(records, capacity);
            counts = Arrays.copyOf(counts, capacity);
        }
        int floats = count * CgQuadRecord.FLOATS;
        float[] into = records[batches];
        if (into == null || into.length < floats) records[batches] = into = new float[Math.max(floats, 64)];
        System.arraycopy(src, from * CgQuadRecord.FLOATS, into, 0, floats);
        ranks[batches] = rank;
        keys[batches] = key;
        looks[batches] = look;
        counts[batches] = count;
        batches++;
    }
}
