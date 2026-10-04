package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgDistortionField;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;

/**
 * The world renderer's distortion: where each haze's Distortion pass is applied, the targets they add into, and the
 * applies that bend the stage's target by them. A haze bends what sorts before it in the transparent pass, as each haze
 * reading its own copy of the target would, while drawing its offsets once at {@link #scale} of the target's size.
 *
 * <p>Per transparent firing, in this order: {@link #plan}, {@link #recordBends} ahead of the transparent pass,
 * {@link #addApplies} into it, {@link #recordFinal} after it. The world renderer sees it through {@link Draws}.</p>
 */
final class CgWorldDistortion {

    /** What it needs of the world renderer's draws this firing. */
    interface Draws {

        int count();

        /** Whether draw {@code i} distorts and is drawn in this firing's transparent pass, not at half size. */
        boolean distorts(int i);

        /** Whether draw {@code i} draws in the transparent pass this firing, half-size draws not. */
        boolean inPass(int i);

        /** Whether draw {@code i} must not be bent by the hazes sorted before it ({@code Draw.afterDistortion}). */
        boolean sharp(int i);

        /** Draws {@code i} in its place in the transparent pass, an apply of what sorts before it placed ahead of it. */
        void inPlace(int i);

        long key(int i);

        /** Draw {@code i}'s rect on screen in pixels from the top left, the whole target where it has none. */
        void rect(int i, float[] out);

        /** Draw {@code i}'s Distortion passes into {@code chunks}. */
        void drawDistortion(CgChunkBuilder chunks, CgRecording recording, int i);
    }

    /** Slots 0 to SLOTS - 1 hold applies placed in the transparent pass, a slot's never overlapping; the last the final. */
    private static final int SLOTS = CgDistortionField.MAX - 1, FINAL = SLOTS;
    /** How far an apply samples from its rect, a share of the target's height: the apply shader's SceneColorMargin. */
    private static final float MARGIN = 0.1f;
    /** Pixels an apply's offsets spread past its rect when read bilinearly from a half-size target, and then some. */
    private static final float SPREAD = 4f;
    private static final CgFrameBufferFormat FORMAT = CgFrameBufferFormat.builder("cg_world_distortion")
            .color(0, CgTextureType.RGBA16F).build();
    private static final String APPLY_SHADER = "crystalgraphics:shaders/world_distortion_apply.shader";
    /** Offsets add, ONE ONE, hidden by the scene's copied depth rather than a depth test. */
    private static final CgRenderState BEND_STATE = CgRenderState.builder()
            .depth(CgDepthState.NONE).blend(new CgBlendState(true, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE,
                    CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD)).build();
    /** What an apply draws: one quad over its hazes' rect. */
    private static final CgMesh QUAD = CgMesh.quads(1);
    private static final int GPU_BEND = CgGpuTrace.name("world.distortion"),
            GPU_APPLY = CgGpuTrace.name("world.distortionApply");

    private float scale = 0.5f;
    private float width, height;
    private final CgGraphTexture[] targets = new CgGraphTexture[SLOTS + 1];
    private final CgMaterial[] applyMaterials = new CgMaterial[SLOTS + 1];
    private final CgTexture[] applyBound = new CgTexture[SLOTS + 1];
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private final CgDistortionField field = new CgDistortionField();

    /** Each distorting draw's slot. */
    private byte[] slotOf = new byte[64];
    // The applies placed in the transparent pass: each one's slot, its rect in pixels from the top left, its sort key.
    private int applies;
    private int[] applySlot = new int[8];
    private float[] applyRect = new float[32];
    private long[] applyKey = new long[8];
    /** Each slot's applies' rects, so a new one on it overlaps none. */
    private final float[][] slotRects = new float[SLOTS][32];
    private final int[] slotRectCount = new int[SLOTS];
    /** Whether the final apply has hazes, and their rect. */
    private boolean finalUsed;
    private final float[] finalRect = new float[4];
    // The transparent draws in key order, and the merge sort's scratch.
    private int[] sorted = new int[64], scratch = new int[64];
    private final float[] pendingRect = new float[4], drawRect = new float[4];

    /** The targets' size as a share of the world's: 0.5 by default; 1 a texel a pixel. */
    void scale(float scale) {
        if (!(scale > 0f && scale <= 1f)) throw new IllegalArgumentException("a distortion scale of " + scale + ": above 0, at most 1");
        this.scale = scale;
    }

    float scale() {
        return scale;
    }

    /**
     * Decides where each haze is applied. Walking the draws in key order, a sharp draw that the hazes pending before it
     * overlap gets their apply placed just before it, on a slot none of whose applies that rect overlaps, and then
     * draws in place; where no slot is free it stays sharp, for the pass after the final apply. Every haze left goes to
     * the final apply. Answers how many draws distort.
     */
    int plan(Draws draws, float width, float height) {
        this.width = width;
        this.height = height;
        int count = draws.count();
        if (slotOf.length < count) slotOf = new byte[Math.max(count, slotOf.length * 2)];
        if (sorted.length < count) {
            sorted = new int[Math.max(count, sorted.length * 2)];
            scratch = new int[sorted.length];
        }
        applies = 0;
        Arrays.fill(slotRectCount, 0);
        int distorting = 0, sharp = 0, n = 0;
        for (int i = 0; i < count; i++) {
            boolean d = draws.distorts(i);
            slotOf[i] = FINAL;
            if (d) distorting++;
            if (draws.sharp(i)) sharp++;
            if (d || draws.inPass(i)) sorted[n++] = i;
        }
        CgTrace.counter(CgChannels.WORLD, "world.distortionDraws", distorting);
        if (distorting > 0 && sharp > 0) {
            sortByKey(draws, n);
            int pending = 0;
            float margin = MARGIN * height + SPREAD;
            for (int k = 0; k < n; k++) {
                int i = sorted[k];
                draws.rect(i, drawRect);
                if (draws.sharp(i)) {
                    if (pending == 0 || !overlaps(pendingRect, drawRect, margin)) {
                        draws.inPlace(i);
                    } else {
                        int slot = freeSlot(pendingRect);
                        if (slot >= 0) {
                            for (int j = 0; j < k; j++) {
                                int h = sorted[j];
                                if (draws.distorts(h) && slotOf[h] == FINAL) slotOf[h] = (byte) slot;
                            }
                            placeApply(slot, pendingRect, draws.key(i) - 1);
                            pending = 0;
                            draws.inPlace(i);
                        }
                    }
                }
                if (draws.distorts(i)) {
                    if (pending++ == 0) System.arraycopy(drawRect, 0, pendingRect, 0, 4);
                    else union(pendingRect, drawRect);
                }
            }
        }
        finalUsed = false;
        for (int i = 0; i < count; i++) {
            if (!draws.distorts(i) || slotOf[i] != FINAL) continue;
            draws.rect(i, drawRect);
            if (!finalUsed) System.arraycopy(drawRect, 0, finalRect, 0, 4);
            else union(finalRect, drawRect);
            finalUsed = true;
        }
        CgTrace.counter(CgChannels.WORLD, "world.distortionApplies", applies + (finalUsed ? 1 : 0));
        return distorting;
    }

    /** The planned slots' Distortion passes, each into its target: ahead of the transparent pass, whose applies read them. */
    void recordBends(CgStageFrame stage, CgRecording recording, Draws draws) {
        field.clear();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slotRectCount[slot] > 0) field.add(recordBend(stage, recording, draws, slot));
        }
    }

    /** Each apply placed in the transparent pass, sorted just before the draw it must not bend. */
    void addApplies(CgChunkBuilder chunks, CgRecording recording) {
        for (int a = 0; a < applies; a++) {
            CgMaterial material = apply(applySlot[a]);
            CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) continue;
            chunks.draw(pipeline, material.captureBindings(recording.bindings()), QUAD).sortKey(applyKey[a]);
            chunks.bounds(applyRect[a * 4], applyRect[a * 4 + 1], applyRect[a * 4 + 2], applyRect[a * 4 + 3]);
            applyInstance(chunks, applyRect, a * 4);
        }
    }

    /**
     * The hazes no apply in the transparent pass took, into the final target and applied over their rect after it; then
     * the field published as {@link CgFrameKeys#DISTORTION}, if any target holds offsets.
     */
    void recordFinal(CgStageFrame stage, CgRecording recording, Draws draws) {
        if (finalUsed) {
            field.add(recordBend(stage, recording, draws, FINAL));
            CgMaterial material = apply(FINAL);
            CgPipeline pipeline = material.pipeline(CgInstanceKind.OBJECT);
            if (pipeline != null) {
                CgRasterPass pass = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                        .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT)
                        .timed(GPU_APPLY);
                CgChunkBuilder chunks = recording.chunks().begin();
                chunks.draw(pipeline, material.captureBindings(recording.bindings()), QUAD);
                chunks.bounds(finalRect[0], finalRect[1], finalRect[2], finalRect[3]);
                applyInstance(chunks, finalRect, 0);
                pass.add(chunks.end());
                pass.end();
            }
        }
        if (field.count() > 0) stage.resources().put(CgFrameKeys.DISTORTION, field);
    }

    /** Forgets its materials, which the material registry frees with the context. */
    void release() {
        Arrays.fill(applyMaterials, null);
        Arrays.fill(applyBound, null);
    }

    private CgGraphTexture recordBend(CgStageFrame stage, CgRecording recording, Draws draws, int slot) {
        CgGraphTexture target = target(slot);
        stage.constants().write(block, 0);
        constants.read(block, 0).resolution(target.getWidth(), target.getHeight());
        CgRasterPass bend = recording.raster(target, CgLoad.clear(0f, 0f, 0f, 0f), constants, BEND_STATE, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_BEND);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0, count = draws.count(); i < count; i++) {
            if (draws.distorts(i) && slotOf[i] == slot) draws.drawDistortion(chunks, recording, i);
        }
        bend.add(chunks.end());
        bend.end();
        return target;
    }

    /** The first slot none of whose applies {@code rect} overlaps, its offsets' bilinear spread included; -1 if none. */
    private int freeSlot(float[] rect) {
        for (int slot = 0; slot < SLOTS; slot++) {
            float[] rects = slotRects[slot];
            boolean free = true;
            for (int r = 0; r < slotRectCount[slot] && free; r++) {
                free = !(rect[0] - SPREAD < rects[r * 4 + 2] && rects[r * 4] < rect[2] + SPREAD
                        && rect[1] - SPREAD < rects[r * 4 + 3] && rects[r * 4 + 1] < rect[3] + SPREAD);
            }
            if (free) return slot;
        }
        return -1;
    }

    private void placeApply(int slot, float[] rect, long key) {
        int r = slotRectCount[slot]++;
        if (slotRects[slot].length < (r + 1) * 4) slotRects[slot] = Arrays.copyOf(slotRects[slot], (r + 1) * 8);
        System.arraycopy(rect, 0, slotRects[slot], r * 4, 4);
        if (applySlot.length == applies) {
            applySlot = Arrays.copyOf(applySlot, applies * 2);
            applyKey = Arrays.copyOf(applyKey, applies * 2);
            applyRect = Arrays.copyOf(applyRect, applies * 8);
        }
        applySlot[applies] = slot;
        applyKey[applies] = key;
        System.arraycopy(rect, 0, applyRect, applies * 4, 4);
        applies++;
    }

    private static void union(float[] into, float[] r) {
        into[0] = Math.min(into[0], r[0]);
        into[1] = Math.min(into[1], r[1]);
        into[2] = Math.max(into[2], r[2]);
        into[3] = Math.max(into[3], r[3]);
    }

    private static boolean overlaps(float[] a, float[] b, float margin) {
        return a[0] - margin < b[2] && b[0] < a[2] + margin && a[1] - margin < b[3] && b[1] < a[3] + margin;
    }

    /** {@link #sorted}'s first {@code n} by key, ties by index: the order the transparent pass's batches take. */
    private void sortByKey(Draws draws, int n) {
        int[] a = sorted, b = scratch;
        for (int run = 1; run < n; run *= 2) {
            for (int lo = 0; lo < n; lo += 2 * run) {
                int mid = Math.min(lo + run, n), hi = Math.min(lo + 2 * run, n), l = lo, r = mid, o = lo;
                while (l < mid && r < hi) b[o++] = draws.key(a[r]) < draws.key(a[l]) ? a[r++] : a[l++];
                while (l < mid) b[o++] = a[l++];
                while (r < hi) b[o++] = a[r++];
            }
            int[] t = a;
            a = b;
            b = t;
        }
        if (a != sorted) System.arraycopy(a, 0, sorted, 0, n);
    }

    /** Slot {@code slot}'s target, {@link #scale} of the stage's size. */
    private CgGraphTexture target(int slot) {
        int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
        CgGraphTexture target = targets[slot];
        if (target == null || target.getWidth() != w || target.getHeight() != h) {
            targets[slot] = target = CgGraphTexture.transientTexture("cg_world_distortion" + slot, new CgTextureDesc(w, h, FORMAT));
        }
        return target;
    }

    /** Slot {@code slot}'s apply, reading its target. */
    private CgMaterial apply(int slot) {
        CgMaterial material = applyMaterials[slot];
        if (material == null) applyMaterials[slot] = material = CgMaterial.newInstance(APPLY_SHADER);
        CgTexture offsets = targets[slot];
        if (offsets != applyBound[slot]) {
            material.applyProperties(b -> b.sampler("_Distortion", 0, offsets));
            applyBound[slot] = offsets;
        }
        return material;
    }

    /** An apply's instance: its rect, pixels from the top left, as the NDC corners its vertex stage reads. */
    private void applyInstance(CgChunkBuilder chunks, float[] rect, int at) {
        int base = chunks.instance();
        float[] data = chunks.data();
        data[base + 32] = rect[at] / width * 2f - 1f;
        data[base + 33] = 1f - rect[at + 3] / height * 2f;
        data[base + 34] = rect[at + 2] / width * 2f - 1f;
        data[base + 35] = 1f - rect[at + 1] / height * 2f;
    }
}
