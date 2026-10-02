package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgPalette;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A stretch of recording kept to be added to a later recording: the chunks a {@link CgPassRecorder} took between two
 * {@link CgPassRecorder#chunksTaken} readings, with the clip entries and shapes the stretch added and the snapshots it
 * binds. What lets drawing that would come out the same be recorded again without drawing it (render-graph G6).
 *
 * <pre>{@code
 * long from = recorder.chunksTaken(), state = recorder.stateChanges();
 * int operations = recording.operations(), clips = recording.clips().count();
 * ... draw, then flush ...
 * boolean kept = recorder.stateChanges() == state && recording.operations() == operations
 *         && stretch.capture(recorder, from, recording, keptBindings, clips, clipEntry, spatial, effect);
 *
 * // a later frame, in which the drawing would come out the same:
 * if (!stretch.replay(recorder, recording, clipEntry, spatial, effect)) { ... draw it ... }
 * }</pre>
 *
 * <ul>
 *   <li>Capture refuses a stretch that drew in another node, stamped a clip from outside its own chain, drew a world
 *       object, or binds a texture made for one frame. Recording anything but chunks, or changing pass or scissor,
 *       is the caller's to refuse, by the readings above.</li>
 *   <li>Snapshots are copied into the table capture is given, which outlives the recordings: reset it and every
 *       stretch kept against it is void.</li>
 *   <li>Replay renumbers clip entries, shapes and nodes into the recording; where nothing renumbers, the kept chunks
 *       are added as they are and nothing is allocated. It adds every chunk or none: false, with nothing added, when
 *       a clip copy runs past {@link CgClipTable#MAX_DEPTH}.</li>
 *   <li>Shapes are renumbered only in the quads of a pipeline whose shader uses {@code shape}: to any other,
 *       {@code custom2} means something else.</li>
 * </ul>
 */
public final class CgReplay {

    private static final int QUAD_FLOATS = CgInstanceKind.QUAD.floats(), CURVE_FLOATS = CgInstanceKind.CURVE.floats();
    private static final int QUAD_CLIP = offset(CgInstanceKind.QUAD, "clip"), QUAD_NODE = offset(CgInstanceKind.QUAD, "node");
    private static final int QUAD_SHAPE = offset(CgInstanceKind.QUAD, "custom2");
    private static final int CURVE_CLIP = offset(CgInstanceKind.CURVE, "clip"), CURVE_NODE = offset(CgInstanceKind.CURVE, "node");
    private static final int KINDS = CgInstanceKind.values().length;

    private CgDrawChunk[] chunks = new CgDrawChunk[2];
    private int chunkCount;
    private boolean kept;

    /** The numbering the kept records use: the clip the stretch started under, and its nodes. */
    private int baseClip, spatial, effect;
    /** Per clip entry the stretch added: its floats, its parent (-1 the base, else an earlier one's position), its index. */
    private int clipCount;
    private float[] clipEntries = new float[0];
    private int[] clipParents = new int[0], clipIndices = new int[0], newClips = new int[0];
    /** Per shape the records name: its floats and its index. */
    private int shapeCount;
    private float[] shapeEntries = new float[0];
    private int[] shapeIndices = new int[0], newShapes = new int[0];

    /** Whether a stretch is kept: the last {@link #capture} took one and nothing voided it since. */
    public boolean kept() {
        return kept;
    }

    /** Drops what is kept; the next {@link #replay} answers false. */
    public void clear() {
        kept = false;
        Arrays.fill(chunks, 0, chunkCount, null);
        chunkCount = 0;
    }

    /** How many chunks are kept. */
    public int chunks() {
        return chunkCount;
    }

    /**
     * Keeps the chunks {@code recorder} took since reading {@code from}, recorded into {@code recording} under clip
     * entry {@code baseClip} in spatial node {@code spatial} and effect node {@code effect}, the clip table then holding
     * {@code clipsBefore} entries. Answers whether it kept them; a refusal drops what was kept before.
     *
     * @param bindings where the snapshots are copied: a table that outlives the recording
     */
    public boolean capture(CgPassRecorder recorder, long from, CgRecording recording, CgBindingTable bindings,
                           int clipsBefore, int baseClip, int spatial, int effect) {
        clear();
        clipCount = 0;
        shapeCount = 0;
        CgClipTable clips = recording.clips();
        int clipsAfter = clips.count();
        ensureClips(clipsAfter - clipsBefore);
        for (int e = clipsBefore; e < clipsAfter; e++) {
            int parent = clips.parent(e);
            if (parent != baseClip && parent < clipsBefore || clips.node(e) != spatial) return false;
            clips.read(e, clipEntries, clipCount * CgClipTable.ENTRY_FLOATS);
            clipParents[clipCount] = parent == baseClip ? -1 : parent - clipsBefore;
            clipIndices[clipCount++] = e;
        }
        float node = CgPalette.pack(spatial, effect);
        CgShapeTable shapes = recording.shapes();
        for (long at = from, to = recorder.chunksTaken(); at < to; at++) {
            CgDrawChunk chunk = recorder.taken(at);
            if (chunk == null || chunk.spatial() != spatial || chunk.clip() != 0 || chunk.effect() != 0) return false;
            int[] ids = new int[chunk.draws()];
            for (int d = 0; d < chunk.draws(); d++) {
                CgInstanceKind kind = chunk.kind(d);
                if (kind == CgInstanceKind.OBJECT) return false;
                boolean quad = kind == CgInstanceKind.QUAD;
                int floats = quad ? QUAD_FLOATS : CURVE_FLOATS;
                int clipAt = quad ? QUAD_CLIP : CURVE_CLIP, nodeAt = quad ? QUAD_NODE : CURVE_NODE;
                boolean shaped = quad && readsShapes(chunk.pipeline(d));
                float[] data = chunk.data(kind);
                for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                    int o = r * floats;
                    if (data[o + nodeAt] != node) return false;
                    int clip = (int) data[o + clipAt];
                    if (clip != baseClip && clip < clipsBefore) return false;
                    if (shaped) {
                        int shape = (int) data[o + QUAD_SHAPE];
                        if (shape >= CgShapeTable.FIRST_SHAPE && shapeAt(shape) < 0) keepShape(shapes, shape);
                    }
                }
                CgBindingTable table = chunk.bindings();
                int id = chunk.binding(d);
                for (int i = 0; i < table.textures(id); i++) if (!lasts(table.texture(id, i))) return false;
                ids[d] = bindings.copy(table, id);
            }
            if (chunkCount == chunks.length) chunks = Arrays.copyOf(chunks, chunkCount * 2);
            chunks[chunkCount++] = chunk.with(spatial, bindings, ids, null);
        }
        this.baseClip = baseClip;
        this.spatial = spatial;
        this.effect = effect;
        kept = true;
        return true;
    }

    /**
     * Adds the kept chunks to {@code recorder}'s pass in {@code recording}, under clip entry {@code baseClip} in
     * spatial node {@code spatial} and effect node {@code effect}. Answers false, adding no chunk, when nothing is
     * kept or a clip will not fit.
     */
    public boolean replay(CgPassRecorder recorder, CgRecording recording, int baseClip, int spatial, int effect) {
        if (!kept) return false;
        boolean same = baseClip == this.baseClip && spatial == this.spatial && effect == this.effect;
        CgClipTable clips = recording.clips();
        for (int k = 0; k < clipCount; k++) {
            int parent = clipParents[k] < 0 ? baseClip : newClips[clipParents[k]];
            int entry = clips.addCopy(clipEntries, k * CgClipTable.ENTRY_FLOATS, parent, spatial);
            if (entry < 0) return false;
            newClips[k] = entry;
            same &= entry == clipIndices[k];
        }
        CgShapeTable shapes = recording.shapes();
        for (int k = 0; k < shapeCount; k++) {
            newShapes[k] = shapes.addCopy(shapeEntries, k * CgShapeTable.ENTRY_FLOATS);
            same &= newShapes[k] == shapeIndices[k];
        }
        if (!same) renumber(baseClip, spatial, effect);
        for (int i = 0; i < chunkCount; i++) recorder.add(chunks[i]);
        return true;
    }

    /**
     * Where this stretch draws otherwise than {@code other}, both kept against one snapshot table, or null where they
     * draw the same: what a check compares a kept stretch against a fresh paint of it with. Records are compared but
     * for what replay renumbers: clip entries by their place in the stretch, shapes by their content, nodes not at all.
     */
    @Nullable
    public String differs(CgReplay other) {
        if (!kept || !other.kept) return "not kept";
        if (chunkCount != other.chunkCount) return chunkCount + " chunks against " + other.chunkCount;
        if (clipCount != other.clipCount) return clipCount + " clip entries against " + other.clipCount;
        for (int k = 0; k < clipCount; k++) {
            if (clipParents[k] != other.clipParents[k]) return "clip entry " + k + " sits in another";
            for (int f = 0, o = k * CgClipTable.ENTRY_FLOATS; f < CgClipTable.ENTRY_FLOATS; f++) {
                if (f != CgClipTable.PARENT_FLOAT && f != CgClipTable.NODE_FLOAT
                        && Float.compare(clipEntries[o + f], other.clipEntries[o + f]) != 0) {
                    return "clip entry " + k + " float " + f;
                }
            }
        }
        for (int i = 0; i < chunkCount; i++) {
            CgDrawChunk a = chunks[i], b = other.chunks[i];
            if (a.draws() != b.draws()) return "chunk " + i + ": " + a.draws() + " draws against " + b.draws();
            for (int d = 0; d < a.draws(); d++) {
                String where = "chunk " + i + " draw " + d;
                if (a.pipeline(d) != b.pipeline(d)) return where + ": another pipeline";
                if (a.kind(d) != b.kind(d) || a.instances(d) != b.instances(d)) return where + ": other instances";
                if (a.mesh(d) != b.mesh(d)) return where + ": another mesh";
                if (a.binding(d) != b.binding(d)) return where + ": " + bindingDiffers(a.bindings(), a.binding(d), b.binding(d));
                if (a.x0(d) != b.x0(d) || a.y0(d) != b.y0(d) || a.x1(d) != b.x1(d) || a.y1(d) != b.y1(d)) {
                    return where + ": other bounds";
                }
                String record = differs(a, b, d, other);
                if (record != null) return where + ": " + record;
            }
        }
        return null;
    }

    /** Where draw {@code d} of {@code a}, this stretch's, has records otherwise than {@code b}, {@code other}'s. */
    @Nullable
    private String differs(CgDrawChunk a, CgDrawChunk b, int d, CgReplay other) {
        CgInstanceKind kind = a.kind(d);
        boolean quad = kind == CgInstanceKind.QUAD;
        int floats = quad ? QUAD_FLOATS : CURVE_FLOATS;
        int clipAt = quad ? QUAD_CLIP : CURVE_CLIP, nodeAt = quad ? QUAD_NODE : CURVE_NODE;
        boolean shaped = quad && readsShapes(a.pipeline(d));
        float[] x = a.data(kind), y = b.data(kind);
        for (int n = 0; n < a.instances(d); n++) {
            int o = (a.first(d) + n) * floats, p = (b.first(d) + n) * floats;
            for (int f = 0; f < floats; f++) {
                if (f == nodeAt) continue;
                if (f == clipAt) {
                    if (clipSlot((int) x[o + f]) != other.clipSlot((int) y[p + f])) return "record " + n + " clip";
                } else if (shaped && f == QUAD_SHAPE) {
                    if (!sameShape((int) x[o + f], other, (int) y[p + f])) return "record " + n + " shape";
                } else if (Float.compare(x[o + f], y[p + f]) != 0) {
                    return "record " + n + " float " + f + ": " + x[o + f] + " against " + y[p + f];
                }
            }
        }
        return null;
    }

    /** What differs between two snapshots of one table: a texture, a block or a buffer. */
    private static String bindingDiffers(CgBindingTable table, int a, int b) {
        if (table.textures(a) != table.textures(b)) return "another texture count";
        for (int i = 0; i < table.textures(a); i++) {
            if (table.texture(a, i) != table.texture(b, i)) return "texture " + i + ": " + table.texture(a, i) + " against " + table.texture(b, i);
        }
        if (table.blocks(a) != table.blocks(b)) return "another block count";
        for (int k = 0; k < table.blocks(a); k++) {
            if (!Arrays.equals(table.blockFloats(a, k), table.blockFloats(b, k))) return "block " + k + " (binding " + table.blockBinding(a, k) + ")";
        }
        return "another buffer";
    }

    /** A kept record's clip entry by its place in the stretch: -1 the base, else the position of an added one. */
    private int clipSlot(int entry) {
        if (entry == baseClip) return -1;
        for (int k = 0; k < clipCount; k++) if (clipIndices[k] == entry) return k;
        return -2;
    }

    private boolean sameShape(int shape, CgReplay other, int otherShape) {
        if (shape < CgShapeTable.FIRST_SHAPE || otherShape < CgShapeTable.FIRST_SHAPE) return shape == otherShape;
        int a = shapeAt(shape) * CgShapeTable.ENTRY_FLOATS, b = other.shapeAt(otherShape) * CgShapeTable.ENTRY_FLOATS;
        return Arrays.equals(shapeEntries, a, a + CgShapeTable.ENTRY_FLOATS,
                other.shapeEntries, b, b + CgShapeTable.ENTRY_FLOATS);
    }

    /** Rewrites the kept records into the numbering {@link #replay} just made, which then becomes theirs. */
    private void renumber(int baseClip, int spatial, int effect) {
        float node = CgPalette.pack(spatial, effect);
        for (int i = 0; i < chunkCount; i++) {
            CgDrawChunk chunk = chunks[i];
            float[][] records = new float[KINDS][];
            for (int d = 0; d < chunk.draws(); d++) {
                CgInstanceKind kind = chunk.kind(d);
                int k = kind.ordinal();
                if (records[k] == null) records[k] = chunk.data(kind).clone();
                float[] data = records[k];
                boolean quad = kind == CgInstanceKind.QUAD;
                int floats = quad ? QUAD_FLOATS : CURVE_FLOATS;
                int clipAt = quad ? QUAD_CLIP : CURVE_CLIP, nodeAt = quad ? QUAD_NODE : CURVE_NODE;
                boolean shaped = quad && readsShapes(chunk.pipeline(d));
                for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                    int o = r * floats;
                    data[o + nodeAt] = node;
                    data[o + clipAt] = clipFor((int) data[o + clipAt], baseClip);
                    if (shaped) {
                        int shape = (int) data[o + QUAD_SHAPE];
                        if (shape >= CgShapeTable.FIRST_SHAPE) data[o + QUAD_SHAPE] = newShapes[shapeAt(shape)];
                    }
                }
            }
            for (int k = 0; k < KINDS; k++) if (records[k] == null) records[k] = chunk.data(CgInstanceKind.of(k));
            chunks[i] = chunk.with(spatial, chunk.bindings(), null, records);
        }
        this.baseClip = baseClip;
        this.spatial = spatial;
        this.effect = effect;
        System.arraycopy(newClips, 0, clipIndices, 0, clipCount);
        System.arraycopy(newShapes, 0, shapeIndices, 0, shapeCount);
    }

    /** A kept record's clip entry in the new numbering: the base, or an entry the stretch added. */
    private int clipFor(int entry, int newBase) {
        if (entry == baseClip) return newBase;
        for (int k = 0; k < clipCount; k++) if (clipIndices[k] == entry) return newClips[k];
        throw new IllegalStateException("a kept record names clip entry " + entry + ", which capture did not keep");
    }

    private int shapeAt(int index) {
        for (int k = 0; k < shapeCount; k++) if (shapeIndices[k] == index) return k;
        return -1;
    }

    private void keepShape(CgShapeTable shapes, int index) {
        if (shapeCount == shapeIndices.length) {
            int n = Math.max(4, shapeCount * 2);
            shapeEntries = Arrays.copyOf(shapeEntries, n * CgShapeTable.ENTRY_FLOATS);
            shapeIndices = Arrays.copyOf(shapeIndices, n);
            newShapes = Arrays.copyOf(newShapes, n);
        }
        shapes.read(index, shapeEntries, shapeCount * CgShapeTable.ENTRY_FLOATS);
        shapeIndices[shapeCount++] = index;
    }

    private void ensureClips(int n) {
        if (clipIndices.length >= n) return;
        clipEntries = Arrays.copyOf(clipEntries, n * CgClipTable.ENTRY_FLOATS);
        clipParents = Arrays.copyOf(clipParents, n);
        clipIndices = Arrays.copyOf(clipIndices, n);
        newClips = Arrays.copyOf(newClips, n);
    }

    /** Whether a texture is the same object next frame: anything but a graph texture made for one frame. */
    private static boolean lasts(CgTexture texture) {
        return !(texture instanceof CgGraphTexture graph) || graph.kind() == CgGraphTexture.Kind.REQUESTED
                || graph.kind() == CgGraphTexture.Kind.IMPORTED;
    }

    private static boolean readsShapes(int pipeline) {
        return CgPipeline.byId(pipeline).shader().usesEngineBuffer("shape");
    }

    private static int offset(CgInstanceKind kind, String field) {
        return kind.format().getField(field).getFloatOffset();
    }
}
