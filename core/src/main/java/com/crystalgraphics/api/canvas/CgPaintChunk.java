package com.crystalgraphics.api.canvas;

import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.path.CgCurveRecords;
import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.render.ui.property.CgClipTree;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.ui.element.CgQuadRecord;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.ui.path.CgPathCache;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.render.draw.CgMaterialBindings;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.paint.CgGradientTable;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.property.CgEffectTree;
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
        /** A draw is positioned in a spatial or effect node from before the chunk, other than the one it began in. */
        OUTER_NODE,
        /** A draw binds a texture that lives one frame. */
        FRAME_TEXTURE,
        /** A clip copied in at replay would nest past {@link CgClipTree#MAX_DEPTH}. */
        CLIP_DEPTH,
        /** A text item drew a glyph or shadow below its tier, still generating: kept, it would stay that way. */
        DEGRADED,
        /** Replayed after the glyph atlases evicted or the canvas's text gamma changed: its glyph quads are stale. */
        STALE_TEXT,
        /** Replayed after the path atlas filled and was placed again: its paths' cells are gone. */
        STALE_PATHS,
        /** A draw of a kind a chunk does not keep. */
        KIND
    }

    private static final CgInstanceKind ELEMENT = CgInstanceKind.ELEMENT, QUAD = CgInstanceKind.QUAD,
            CURVE = CgInstanceKind.CURVE;

    private CgDrawChunk[] chunks = new CgDrawChunk[2];
    private int chunkCount;
    private boolean kept;
    private Refusal refusal = Refusal.NONE;
    private int baseClip, baseNode, baseEffect;
    /** The node and effect node the last replay (or the capture) put its base at. */
    private int replayNode, replayEffect;

    // Clip entries the stretch made: floats, parent (-1 the base, else an earlier one's position), index then
    private float[] clips = new float[0];
    private int[] clipParents = new int[0], clipIndices = new int[0], clipNodes = new int[0], newClips = new int[0];
    private int clipCount;
    // Spatial nodes it made: the affine and movability, parent (-1 the base, else an earlier one's position), index then
    private float[] nodes = new float[0];
    private boolean[] nodeMovable = new boolean[0];
    private int[] nodeParents = new int[0], nodeIndices = new int[0], newNodes = new int[0];
    private int nodeCount;
    // Effect nodes it made: opacity, parent (-1 the base, else an earlier one's position), index then
    private float[] effectOpacities = new float[0];
    private int[] effectParents = new int[0], effectIndices = new int[0], newEffects = new int[0];
    private int effectCount;
    // Gradients its records name: the gradient and its index then
    private CgGradient[] gradients = new CgGradient[0];
    private int[] gradientIndices = new int[0], newGradients = new int[0];
    private int gradientCount;

    private float x0, y0, x1, y1;
    /** For a chunk holding text: the gamma it drew under and the atlases' eviction generation then; else null. */
    @Nullable private CgTextGamma textGamma;
    private long evictions;
    /** For a chunk drawing cached paths: the path atlas's resets then; else -1. */
    private int pathResets = -1;

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
        effectCount = 0;
        textGamma = null;
        pathResets = -1;
    }

    /**
     * Keeps the {@code count} chunks the canvas taped, recorded into {@code recording} under {@code baseClip} in
     * {@code baseNode}, the tables then holding {@code clipsBefore} entries and {@code nodesBefore} nodes.
     */
    Refusal capture(CgCanvas canvas, int count, CgRecording recording, int clipsBefore, int nodesBefore,
                    int effectsBefore, int baseClip, int baseNode, int baseEffect, boolean onePass, boolean degraded,
                    @Nullable CgTextGamma textGamma, boolean paths, float bx0, float by0, float bx1, float by1) {
        clear();
        refusal = degraded ? Refusal.DEGRADED
                : keep(canvas, count, recording, clipsBefore, nodesBefore, effectsBefore, baseClip, baseNode, baseEffect,
                        onePass);
        if (refusal != Refusal.NONE) {
            clear();
            return refusal;
        }
        this.baseClip = baseClip;
        this.baseNode = baseNode;
        this.baseEffect = baseEffect;
        // Until a replay, its ids are the capture's own
        replayNode = baseNode;
        replayEffect = baseEffect;
        System.arraycopy(nodeIndices, 0, newNodes, 0, nodeCount);
        System.arraycopy(effectIndices, 0, newEffects, 0, effectCount);
        this.textGamma = textGamma;
        if (textGamma != null) evictions = CgFontRegistry.get().getAtlasEvictionGeneration();
        pathResets = paths ? CgPathCache.SHARED.resets() : -1;
        x0 = bx0;
        y0 = by0;
        x1 = bx1;
        y1 = by1;
        kept = true;
        return refusal;
    }

    private Refusal keep(CgCanvas canvas, int count, CgRecording recording, int clipsBefore, int nodesBefore,
                         int effectsBefore, int baseClip, int baseNode, int baseEffect, boolean onePass) {
        if (!onePass) return Refusal.PASS;
        CgClipTree clipTable = CgCanvasTables.of(recording).clips();
        int clipsAfter = clipTable.count();
        ensureClips(clipsAfter - clipsBefore);
        for (int e = clipsBefore; e < clipsAfter; e++) {
            int parent = clipTable.parent(e);
            if (parent != baseClip && parent < clipsBefore) return Refusal.OUTER_CLIP;
            int node = clipTable.node(e);
            if (node != baseNode && node < nodesBefore) return Refusal.OUTER_NODE;
            clipTable.read(e, clips, clipCount * CgClipTree.ENTRY_FLOATS);
            clipParents[clipCount] = parent == baseClip ? -1 : parent - clipsBefore;
            clipNodes[clipCount] = node;
            clipIndices[clipCount++] = e;
        }
        CgSpatialTree tree = CgCanvasTables.of(recording).spatial();
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
        CgEffectTree effects = CgCanvasTables.of(recording).effects();
        int effectsAfter = effects.count();
        ensureEffects(effectsAfter - effectsBefore);
        for (int e = effectsBefore; e < effectsAfter; e++) {
            int parent = effects.parent(e);
            if (parent != baseEffect && parent < effectsBefore) return Refusal.OUTER_NODE;
            effectOpacities[effectCount] = effects.opacity(e);
            effectParents[effectCount] = parent == baseEffect ? -1 : parent - effectsBefore;
            effectIndices[effectCount++] = e;
        }
        CgGradientTable gradientTable = CgCanvasTables.of(recording).gradients();
        for (int i = 0; i < count; i++) {
            CgDrawChunk chunk = canvas.taped(i);
            int spatial = chunk.spatial();
            if (spatial != baseNode && spatial < nodesBefore) return Refusal.OUTER_NODE;
            for (int d = 0; d < chunk.draws(); d++) {
                CgInstanceKind kind = chunk.kind(d);
                if (kind != ELEMENT && kind != QUAD && kind != CURVE) return Refusal.KIND;
                CgMaterialBindings bindings = chunk.binding(d);
                for (int t = 0; t < bindings.textures(); t++) {
                    if (!CgGraphTexture.outlivesFrame(bindings.texture(t))) return Refusal.FRAME_TEXTURE;
                }
                float[] data = chunk.data(kind);
                int stride = floats(kind), clipAt = clipField(kind), nodeAt = nodeField(kind);
                for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                    int o = r * stride;
                    int clip = clipOf(kind, data[o + clipAt]);
                    if (clip != baseClip && clip < clipsBefore) return Refusal.OUTER_CLIP;
                    int node = CgPalette.spatialOf(data[o + nodeAt]);
                    if (node != baseNode && node < nodesBefore) return Refusal.OUTER_NODE;
                    int effect = CgPalette.effectOf(data[o + nodeAt]);
                    if (effect != baseEffect && effect < effectsBefore) return Refusal.OUTER_NODE;
                    if (kind == ELEMENT && CgUiElements.kind(data, o) == CgUiElements.FILL_GRADIENT) {
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
    boolean replay(CgCanvas canvas, CgRecording recording, int clip, int node, int effect, CgTextGamma gamma) {
        if (!kept) return false;
        if (textGamma != null && (!textGamma.equals(gamma) || CgFontRegistry.get().getAtlasEvictionGeneration() != evictions)) {
            clear();
            refusal = Refusal.STALE_TEXT;
            return false;
        }
        if (pathResets >= 0 && CgPathCache.SHARED.resets() != pathResets) {
            clear();
            refusal = Refusal.STALE_PATHS;
            return false;
        }
        boolean same = clip == baseClip && node == baseNode && effect == baseEffect;
        replayNode = node;
        replayEffect = effect;
        CgSpatialTree tree = CgCanvasTables.of(recording).spatial();
        for (int k = 0; k < nodeCount; k++) {
            int parent = nodeParents[k] < 0 ? node : newNodes[nodeParents[k]];
            int o = k * 6;
            newNodes[k] = tree.add(parent, nodes[o], nodes[o + 1], nodes[o + 2], nodes[o + 3], nodes[o + 4], nodes[o + 5],
                    nodeMovable[k]);
            same &= newNodes[k] == nodeIndices[k];
        }
        CgEffectTree effects = CgCanvasTables.of(recording).effects();
        for (int k = 0; k < effectCount; k++) {
            int parent = effectParents[k] < 0 ? effect : newEffects[effectParents[k]];
            newEffects[k] = effects.add(parent, effectOpacities[k]);
            same &= newEffects[k] == effectIndices[k];
        }
        CgClipTree clipTable = CgCanvasTables.of(recording).clips();
        for (int k = 0; k < clipCount; k++) {
            int parent = clipParents[k] < 0 ? clip : newClips[clipParents[k]];
            int entry = clipTable.addCopy(clips, k * CgClipTree.ENTRY_FLOATS, parent, renumberNode(clipNodes[k], node));
            if (entry < 0) {
                refusal = Refusal.CLIP_DEPTH;
                return false;
            }
            newClips[k] = entry;
            same &= entry == clipIndices[k];
        }
        CgGradientTable gradientTable = CgCanvasTables.of(recording).gradients();
        for (int k = 0; k < gradientCount; k++) {
            newGradients[k] = gradientTable.add(gradients[k]);
            same &= newGradients[k] == gradientIndices[k];
        }
        for (int i = 0; i < chunkCount; i++) {
            canvas.addChunk(same ? chunks[i] : renumbered(chunks[i], clip, node, effect));
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
                if (!a.binding(d).sameAs(b.binding(d))) {
                    return "chunk " + i + " draw " + d + ": " + bindingDifference(a.binding(d), b.binding(d));
                }
                if (a.instances(d) != b.instances(d)) {
                    return "chunk " + i + " draw " + d + ": " + a.instances(d) + " records against " + b.instances(d);
                }
                CgInstanceKind kind = a.kind(d);
                if (kind != b.kind(d)) return "chunk " + i + " draw " + d + ": " + kind + " against " + b.kind(d);
                float[] da = a.data(kind), db = b.data(kind);
                int stride = floats(kind), clipAt = clipField(kind), nodeAt = nodeField(kind);
                for (int r = 0; r < a.instances(d); r++) {
                    int oa = (a.first(d) + r) * stride, ob = (b.first(d) + r) * stride;
                    for (int f = 0; f < stride; f++) {
                        if (f == clipAt || f == nodeAt) continue;
                        if (kind == ELEMENT && f == CgUiElements.PAINT
                                && CgUiElements.kind(da, oa) == CgUiElements.FILL_GRADIENT) {
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
                    if (clipPlace(clipOf(kind, da[oa + clipAt])) != other.clipPlace(clipOf(kind, db[ob + clipAt]))) {
                        return "chunk " + i + " draw " + d + " record " + r + ": another clip";
                    }
                }
            }
        }
        return null;
    }

    /** What of two bindings differs, for {@link #differs}: the first texture, block or buffer that is another. */
    private static String bindingDifference(CgMaterialBindings a, CgMaterialBindings b) {
        if (a.textures() != b.textures()) return a.textures() + " textures against " + b.textures();
        for (int t = 0; t < a.textures(); t++) {
            if (a.texture(t) != b.texture(t)) return "texture " + t + ": " + a.texture(t) + " against " + b.texture(t);
        }
        if (a.blocks() != b.blocks()) return a.blocks() + " blocks against " + b.blocks();
        for (int k = 0; k < a.blocks(); k++) {
            if (a.block(k) != b.block(k)) return "block " + k + ": " + a.block(k) + " against " + b.block(k);
        }
        return "samplers, buffers or storage";
    }

    /** A chunk's copy with its records' clips, nodes and gradients renumbered into the replay's. */
    private CgDrawChunk renumbered(CgDrawChunk chunk, int clip, int node, int effect) {
        float[][] instances = new float[CgInstanceKind.values().length][];
        for (int d = 0; d < chunk.draws(); d++) {
            CgInstanceKind kind = chunk.kind(d);
            float[] data = instances[kind.ordinal()];
            if (data == null) data = instances[kind.ordinal()] = chunk.data(kind).clone();
            int stride = floats(kind), clipAt = clipField(kind), nodeAt = nodeField(kind);
            for (int r = chunk.first(d), end = r + chunk.instances(d); r < end; r++) {
                int o = r * stride;
                int place = clipPlace(clipOf(kind, data[o + clipAt]));
                data[o + clipAt] = clipValue(kind, place < 0 ? clip : newClips[place]);
                int n = CgPalette.spatialOf(data[o + nodeAt]);
                data[o + nodeAt] = CgPalette.pack(renumberNode(n, node), renumberEffect(CgPalette.effectOf(data[o + nodeAt]), effect));
                if (kind == ELEMENT && CgUiElements.kind(data, o) == CgUiElements.FILL_GRADIENT) {
                    int g = gradientPlace(Float.floatToRawIntBits(data[o + CgUiElements.PAINT]));
                    data[o + CgUiElements.PAINT] = Float.intBitsToFloat(newGradients[g]);
                }
            }
        }
        return chunk.with(renumberNode(chunk.spatial(), node), instances);
    }

    // A record's layout by kind: an element's clip is an int's bits, a glyph quad's the int as a float
    private static int floats(CgInstanceKind kind) {
        return kind == ELEMENT ? CgUiElements.FLOATS : kind == CURVE ? CgCurveRecords.FLOATS : CgQuadRecord.FLOATS;
    }

    private static int clipField(CgInstanceKind kind) {
        return kind == ELEMENT ? CgUiElements.CLIP : kind == CURVE ? CgCurveRecords.CLIP : CgQuadRecord.CLIP;
    }

    private static int nodeField(CgInstanceKind kind) {
        return kind == ELEMENT ? CgUiElements.NODE : kind == CURVE ? CgCurveRecords.NODE : CgQuadRecord.NODE;
    }

    private static int clipOf(CgInstanceKind kind, float field) {
        return kind == ELEMENT ? Float.floatToRawIntBits(field) : (int) field;
    }

    private static float clipValue(CgInstanceKind kind, int entry) {
        return kind == ELEMENT ? Float.intBitsToFloat(entry) : entry;
    }

    /**
     * Spatial node {@code recorded}, one the stretch made when it was captured (a {@code movable()}), as the last replay
     * numbered it: what {@code canvas.nodeTransform} takes. Its own id before any replay.
     */
    public int node(int recorded) {
        return renumberNode(recorded, replayNode);
    }

    /** Effect node {@code recorded}, one the stretch made (a {@code fader()}), as the last replay numbered it. */
    public int effect(int recorded) {
        return renumberEffect(recorded, replayEffect);
    }

    private int renumberEffect(int e, int effect) {
        if (e == baseEffect) return effect;
        for (int k = 0; k < effectCount; k++) if (effectIndices[k] == e) return newEffects[k];
        return e;
    }

    private void ensureEffects(int n) {
        if (effectOpacities.length >= n) return;
        effectOpacities = new float[n];
        effectParents = new int[n];
        effectIndices = new int[n];
        newEffects = new int[n];
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
        clips = Arrays.copyOf(clips, n * CgClipTree.ENTRY_FLOATS);
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
