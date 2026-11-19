package com.crystalgraphics.api.canvas;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.render.canvas.CgClipTable;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgMaterialBindings;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.paint.CgGradientTable;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.property.CgSpatialTree;

/**
 * A stretch of canvas drawing kept to be added to a later frame without drawing it again: Blink's paint chunk. The
 * caller decides when the drawing would come out the same (its own key: a box's revision, its size); the chunk keeps
 * what the canvas recorded and renumbers the clip entries, spatial nodes and gradients it made into the new recording.
 *
 * <pre>{@code
 * CgPaintChunk kept = new CgPaintChunk();                // one per thing kept, held across frames
 *
 * if (unchanged && canvas.replay(kept)) return;           // drawn from what was kept
 * canvas.beginChunk();
 * paintTheBox(canvas);
 * CgPaintChunk.Refusal why = canvas.endChunk(kept);       // NONE: kept; anything else says why not
 * }</pre>
 *
 * <ul>
 *   <li>Replay at the state the chunk began in (the same transform, opacity and clip), as the caller's key promises:
 *       what the chunk drew is recorded as it was, against the clip and node current at replay.</li>
 *   <li>A chunk is refused, with its reason, when the drawing opened another pass, stamped a clip or a node from
 *       outside itself, or bound a texture that lives one frame.</li>
 *   <li>{@link #differs} is the check mode: a fresh paint captured into a second chunk must match the kept one.</li>
 * </ul>
 */
public final class CgPaintChunk {

    /** Why a stretch was not kept. */
    public enum Refusal {
        /** Kept. */
        NONE,
        /** Something recorded between its draws opened a new pass: a layer, a copy, a backdrop. */
        PASS,
        /** A draw names a clip entry from before the chunk, other than the one it began under. */
        OUTER_CLIP,
        /** A draw is positioned in a spatial node from before the chunk, other than the one it began in. */
        OUTER_NODE,
        /** A draw binds a texture that lives one frame. */
        FRAME_TEXTURE,
        /** A clip copied in at replay would nest past {@link CgClipTable#MAX_DEPTH}. */
        CLIP_DEPTH
    }

    private static final int FLOATS = CgUiElements.FLOATS;
    private static final CgInstanceKind ELEMENT = CgInstanceKind.ELEMENT;

    private CgDrawChunk[] chunks = new CgDrawChunk[2];
    private int chunkCount;
    private boolean kept;
    private Refusal refusal = Refusal.NONE;
    private int baseClip, baseNode;

    // Clip entries the stretch made: floats, parent (-1 the base, else an earlier one's position), index then
    private float[] clips = new float[0];
    private int[] clipParents = new int[0], clipIndices = new int[0], clipNodes = new int[0], newClips = new int[0];
    private int clipCount;
    // Spatial nodes it made: the affine and movability, parent (-1 the base, else an earlier one's position), index then
    private float[] nodes = new float[0];
    private boolean[] nodeMovable = new boolean[0];
    private int[] nodeParents = new int[0], nodeIndices = new int[0], newNodes = new int[0];
    private int nodeCount;
    // Gradients its records name: the gradient and its index then
    private CgGradient[] gradients = new CgGradient[0];
    private int[] gradientIndices = new int[0], newGradients = new int[0];
    private int gradientCount;

    private float x0, y0, x1, y1;

    /** Whether a stretch is kept. */
    public boolean kept() {
        return kept;
    }

    /** Why the last capture kept nothing; {@link Refusal#NONE} when it kept. */
    public Refusal refusal() {
        return refusal;
    }

    /** The union of what it drew in its base node's space, in the canvas's units there. */
    public float x0() {
        return x0;
    }

    public float y0() {
        return y0;
    }

    public float x1() {
        return x1;
    }

    public float y1() {
        return y1;
    }

    /** Drops what is kept. */
    public void clear() {
        kept = false;
        Arrays.fill(chunks, 0, chunkCount, null);
        chunkCount = 0;
        Arrays.fill(gradients, 0, gradientCount, null);
        gradientCount = 0;
        clipCount = 0;
        nodeCount = 0;
    }

    /**
     * Keeps the {@code count} chunks the canvas taped, recorded into {@code recording} under {@code baseClip} in
     * {@code baseNode}, the tables then holding {@code clipsBefore} entries and {@code nodesBefore} nodes.
     */
    Refusal capture(CgCanvas canvas, int count, CgRecording recording, int clipsBefore, int nodesBefore, int baseClip,
                    int baseNode, boolean onePass, float bx0, float by0, float bx1, float by1) {
        clear();
        refusal = keep(canvas, count, recording, clipsBefore, nodesBefore, baseClip, baseNode, onePass);
        if (refusal != Refusal.NONE) {
            clear();
            return refusal;
        }
        this.baseClip = baseClip;
        this.baseNode = baseNode;
        x0 = bx0;
        y0 = by0;
        x1 = bx1;
        y1 = by1;
        kept = true;
        return refusal;
    }

    private Refusal keep(CgCanvas canvas, int count, CgRecording recording, int clipsBefore, int nodesBefore,
                         int baseClip, int baseNode, boolean onePass) {
        if (!onePass) return Refusal.PASS;
        CgClipTable clipTable = recording.clips();
        int clipsAfter = clipTable.count();
        ensureClips(clipsAfter - clipsBefore);
        for (int e = clipsBefore; e < clipsAfter; e++) {
            int parent = clipTable.parent(e);
            if (parent != baseClip && parent < clipsBefore) return Refusal.OUTER_CLIP;
            int node = clipTable.node(e);
            if (node != baseNode && node < nodesBefore) return Refusal.OUTER_NODE;
            clipTable.read(e, clips, clipCount * CgClipTable.ENTRY_FLOATS);
            clipParents[clipCount] = parent == baseClip ? -1 : parent - clipsBefore;
            clipNodes[clipCount] = node;
            clipIndices[clipCount++] = e;
        }
        CgSpatialTree tree = recording.spatial();
        int nodesAfter = tree.count();
        ensureNodes(nodesAfter - nodesBefore);
        for (int n = nodesBefore; n < nodesAfter; n++) {
            int parent = tree.parent(n);
            if (parent != baseNode && parent < nodesBefore) return Refusal.OUTER_NODE;
            for (int i = 0; i < 6; i++) nodes[nodeCount * 6 + i] = tree.local(n, i);
            nodeMovable[nodeCount] = tree.movable(n);
            nodeParents[nodeCount] = parent == baseNode ? -1 : parent - nodesBefore;
            nodeIndices[nodeCount++] = n;
        }
        CgGradientTable gradientTable = recording.gradients();
        for (int i = 0; i < count; i++) {
            CgDrawChunk chunk = canvas.taped(i);
            int spatial = chunk.spatial();
            if (spatial != baseNode && spatial < nodesBefore) return Refusal.OUTER_NODE;
            for (int d = 0; d < chunk.draws(); d++) {
                if (chunk.kind(d) != ELEMENT) return Refusal.OUTER_NODE;
                CgMaterialBindings bindings = chunk.binding(d);
                for (int t = 0; t < bindings.textures(); t++) {
                    if (!CgGraphTexture.outlivesFrame(bindings.texture(t))) return Refusal.FRAME_TEXTURE;
                }
                float[] data = chunk.data(ELEMENT);
                for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                    int o = r * FLOATS;
                    int clip = Float.floatToRawIntBits(data[o + CgUiElements.CLIP]);
                    if (clip != baseClip && clip < clipsBefore) return Refusal.OUTER_CLIP;
                    int node = CgPalette.spatialOf(data[o + CgUiElements.NODE]);
                    if (node != baseNode && node < nodesBefore) return Refusal.OUTER_NODE;
                    if (CgUiElements.kind(data, o) == CgUiElements.FILL_GRADIENT) {
                        keepGradient(gradientTable, Float.floatToRawIntBits(data[o + CgUiElements.PAINT]));
                    }
                }
            }
            if (chunkCount == chunks.length) chunks = Arrays.copyOf(chunks, chunkCount * 2);
            chunks[chunkCount++] = chunk;
        }
        return Refusal.NONE;
    }

    /**
     * Adds what is kept to {@code canvas}'s recording under the clip and node current there, renumbered. False, with
     * nothing added, when nothing is kept or a clip would nest too deep.
     */
    boolean replay(CgCanvas canvas, CgRecording recording, int clip, int node) {
        if (!kept) return false;
        boolean same = clip == baseClip && node == baseNode;
        CgSpatialTree tree = recording.spatial();
        for (int k = 0; k < nodeCount; k++) {
            int parent = nodeParents[k] < 0 ? node : newNodes[nodeParents[k]];
            int o = k * 6;
            newNodes[k] = tree.add(parent, nodes[o], nodes[o + 1], nodes[o + 2], nodes[o + 3], nodes[o + 4], nodes[o + 5],
                    nodeMovable[k]);
            same &= newNodes[k] == nodeIndices[k];
        }
        CgClipTable clipTable = recording.clips();
        for (int k = 0; k < clipCount; k++) {
            int parent = clipParents[k] < 0 ? clip : newClips[clipParents[k]];
            int entry = clipTable.addCopy(clips, k * CgClipTable.ENTRY_FLOATS, parent, renumberNode(clipNodes[k], node));
            if (entry < 0) {
                refusal = Refusal.CLIP_DEPTH;
                return false;
            }
            newClips[k] = entry;
            same &= entry == clipIndices[k];
        }
        CgGradientTable gradientTable = recording.gradients();
        for (int k = 0; k < gradientCount; k++) {
            newGradients[k] = gradientTable.add(gradients[k]);
            same &= newGradients[k] == gradientIndices[k];
        }
        for (int i = 0; i < chunkCount; i++) {
            canvas.addChunk(same ? chunks[i] : renumbered(chunks[i], clip, node));
        }
        return true;
    }

    /**
     * Where this chunk draws otherwise than {@code other}, or null where they draw the same: records compared but for
     * what replay renumbers (clip entries by their place in the chunk, nodes by theirs, gradients by value).
     */
    @Nullable
    public String differs(CgPaintChunk other) {
        if (!kept || !other.kept) return "kept " + kept + " against " + other.kept;
        if (chunkCount != other.chunkCount) return chunkCount + " chunks against " + other.chunkCount;
        for (int i = 0; i < chunkCount; i++) {
            CgDrawChunk a = chunks[i], b = other.chunks[i];
            if (a.draws() != b.draws()) return "chunk " + i + ": " + a.draws() + " draws against " + b.draws();
            for (int d = 0; d < a.draws(); d++) {
                if (a.pipeline(d) != b.pipeline(d)) return "chunk " + i + " draw " + d + ": another pipeline";
                if (a.binding(d) != b.binding(d)) return "chunk " + i + " draw " + d + ": other bindings";
                if (a.instances(d) != b.instances(d)) {
                    return "chunk " + i + " draw " + d + ": " + a.instances(d) + " records against " + b.instances(d);
                }
                float[] da = a.data(ELEMENT), db = b.data(ELEMENT);
                for (int r = 0; r < a.instances(d); r++) {
                    int oa = (a.first(d) + r) * FLOATS, ob = (b.first(d) + r) * FLOATS;
                    for (int f = 0; f < FLOATS; f++) {
                        if (f == CgUiElements.CLIP || f == CgUiElements.NODE) continue;
                        if (f == CgUiElements.PAINT && CgUiElements.kind(da, oa) == CgUiElements.FILL_GRADIENT) {
                            if (!gradientOf(da[oa + f]).equals(other.gradientOf(db[ob + f]))) {
                                return "chunk " + i + " draw " + d + " record " + r + ": another gradient";
                            }
                            continue;
                        }
                        if (Float.floatToRawIntBits(da[oa + f]) != Float.floatToRawIntBits(db[ob + f])) {
                            return "chunk " + i + " draw " + d + " record " + r + " float " + f + ": " + da[oa + f]
                                    + " against " + db[ob + f];
                        }
                    }
                    if (clipPlace(Float.floatToRawIntBits(da[oa + CgUiElements.CLIP]))
                            != other.clipPlace(Float.floatToRawIntBits(db[ob + CgUiElements.CLIP]))) {
                        return "chunk " + i + " draw " + d + " record " + r + ": another clip";
                    }
                }
            }
        }
        return null;
    }

    /** A chunk's copy with its records' clips, nodes and gradients renumbered into the replay's. */
    private CgDrawChunk renumbered(CgDrawChunk chunk, int clip, int node) {
        float[] data = chunk.data(ELEMENT).clone();
        for (int d = 0; d < chunk.draws(); d++) {
            for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                int o = r * FLOATS;
                int c = Float.floatToRawIntBits(data[o + CgUiElements.CLIP]);
                int place = clipPlace(c);
                data[o + CgUiElements.CLIP] = Float.intBitsToFloat(place < 0 ? clip : newClips[place]);
                int n = CgPalette.spatialOf(data[o + CgUiElements.NODE]);
                data[o + CgUiElements.NODE] = CgPalette.pack(renumberNode(n, node), 0);
                if (CgUiElements.kind(data, o) == CgUiElements.FILL_GRADIENT) {
                    int g = gradientPlace(Float.floatToRawIntBits(data[o + CgUiElements.PAINT]));
                    data[o + CgUiElements.PAINT] = Float.intBitsToFloat(newGradients[g]);
                }
            }
        }
        float[][] instances = new float[CgInstanceKind.values().length][];
        instances[ELEMENT.ordinal()] = data;
        return chunk.with(renumberNode(chunk.spatial(), node), instances);
    }

    private int renumberNode(int n, int node) {
        if (n == baseNode) return node;
        for (int k = 0; k < nodeCount; k++) if (nodeIndices[k] == n) return newNodes[k];
        return n;
    }

    /** A clip entry's place among those the chunk made, -1 for its base. */
    private int clipPlace(int entry) {
        for (int k = 0; k < clipCount; k++) if (clipIndices[k] == entry) return k;
        return -1;
    }

    private int gradientPlace(int index) {
        for (int k = 0; k < gradientCount; k++) if (gradientIndices[k] == index) return k;
        throw new IllegalStateException("a gradient the chunk did not keep: " + index);
    }

    private CgGradient gradientOf(float paint) {
        return gradients[gradientPlace(Float.floatToRawIntBits(paint))];
    }

    private void keepGradient(CgGradientTable table, int index) {
        for (int k = 0; k < gradientCount; k++) if (gradientIndices[k] == index) return;
        if (gradientCount == gradients.length) {
            int n = Math.max(4, gradientCount * 2);
            gradients = Arrays.copyOf(gradients, n);
            gradientIndices = Arrays.copyOf(gradientIndices, n);
            newGradients = Arrays.copyOf(newGradients, n);
        }
        gradients[gradientCount] = table.gradient(index);
        gradientIndices[gradientCount++] = index;
    }

    private void ensureClips(int n) {
        if (clipIndices.length >= n) return;
        clips = Arrays.copyOf(clips, n * CgClipTable.ENTRY_FLOATS);
        clipParents = Arrays.copyOf(clipParents, n);
        clipIndices = Arrays.copyOf(clipIndices, n);
        clipNodes = Arrays.copyOf(clipNodes, n);
        newClips = Arrays.copyOf(newClips, n);
    }

    private void ensureNodes(int n) {
        if (nodeIndices.length >= n) return;
        nodes = Arrays.copyOf(nodes, n * 6);
        nodeMovable = Arrays.copyOf(nodeMovable, n);
        nodeParents = Arrays.copyOf(nodeParents, n);
        nodeIndices = Arrays.copyOf(nodeIndices, n);
        newNodes = Arrays.copyOf(newNodes, n);
    }
}
