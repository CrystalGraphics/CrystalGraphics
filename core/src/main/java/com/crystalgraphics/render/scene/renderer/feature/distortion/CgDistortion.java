package com.crystalgraphics.render.scene.renderer.feature.distortion;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortionField;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The scene's distortion: where each haze's Distortion pass is applied, the array layers its offsets add into, and the
 * applies that bend the scene by them. A haze bends what sorts before it in the translucent pass, as each haze reading
 * its own copy of the scene would, while drawing its offsets once at the {@code world.distortionScale} share of the
 * view's size. The scene renderer's feature, on the {@code DISTORTION} pass type.
 *
 * <p>Per translucent callback, in this order: {@link #plan}, {@link #recordBends} ahead of the translucent pass,
 * {@link #addApplies} into it, {@link #recordFinal} after it; the after-distortion pass through {@link #afterForm}.</p>
 */
public final class CgDistortion {

    /** The apply: the scene bent by one layer of offsets, over a rect. */
    public static final String APPLY_SHADER = "crystalgraphics:shaders/world/distortion_apply.shader";
    /** Slots 0 to SLOTS - 1 hold applies placed in the translucent pass, a slot's never overlapping; the last the final. */
    private static final int SLOTS = CgDistortionField.MAX - 1, FINAL = SLOTS;
    /** How far an apply samples from its rect, a share of the target's height: the apply shader's SceneColorMargin. */
    private static final float MARGIN = 0.1f;
    /** Pixels an apply's offsets spread past its rect when read bilinearly from a smaller target, and then some. */
    private static final float SPREAD = 4f;
    /** The clip-space w a rect is cut at: nearer than any host's near plane. */
    private static final float NEAR_W = 0.01f;
    private static final CgFormat FORMAT = CgFormat.RGBA16_FLOAT;
    /** Offsets add, ONE ONE, hidden by the scene's copied depth rather than a depth test. */
    private static final CgRenderState BEND_STATE = CgRenderState.builder().depth(CgDepthState.NONE).blend(CgBlendState.SUM).build();
    private static final CgMesh QUAD = CgMesh.quads(1);
    private static final int GPU_BEND = CgGpuTrace.name("scene.distortion"), GPU_APPLY = CgGpuTrace.name("scene.distortionApply"),
            UNREADY = CgTrace.name("scene.passes.unready");

    private static CgMaterialInstance precacheApply;

    private final CgSceneRenderer renderer;
    private float scale = 0.5f;
    private float width, height;
    @Nullable
    private CgGraphTexture offsets;
    private final int[] layerOf = new int[SLOTS + 1];
    private final CgMaterialInstance[] applyMaterials = new CgMaterialInstance[SLOTS + 1];
    private final CgTexture[] applyBound = new CgTexture[SLOTS + 1];
    private final int[] applyLayer = new int[SLOTS + 1];
    private final CgPassConstants constants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private final CgDistortionField field = new CgDistortionField();
    private final Matrix4f viewProjection = new Matrix4f();
    private final float[] clipX = new float[8], clipY = new float[8], clipW = new float[8];

    private CgMeshBatches rows;
    /** Per row: its slot, whether it distorts, is drawn in the translucent pass, sharp, and drawn in place. */
    private byte[] slotOf = new byte[64];
    private boolean[] distorts = new boolean[64], inPass = new boolean[64], sharp = new boolean[64], inPlace = new boolean[64];
    private float[] rects = new float[256];
    private int applies;
    private int[] applySlot = new int[8];
    private float[] applyRect = new float[32];
    private long[] applyKey = new long[8];
    private final float[][] slotRects = new float[SLOTS][32];
    private final int[] slotRectCount = new int[SLOTS];
    private boolean finalUsed;
    private final float[] finalRect = new float[4];
    private int[] sorted = new int[64], scratch = new int[64];
    private final float[] pendingRect = new float[4], drawRect = new float[4];
    private final CgCommandForm[] slotForms = new CgCommandForm[SLOTS + 1];
    @Nullable
    private CgCommandForm afterBase;
    @Nullable
    private CgDrawCommands finalApply;
    /** A sharp row drawn in its place in the translucent pass, after an apply of what sorts before it. */
    private final CgCommandForm placed = (rows, b, p) -> inPlaceRow(rows, b) ? afterBase.form(rows, b, p) : null;
    /** The after-distortion pass: the sharp rows no apply took. */
    private final CgCommandForm after = (rows, b, p) -> inPlaceRow(rows, b) ? null : afterBase.form(rows, b, p);

    public CgDistortion(CgSceneRenderer renderer) {
        this.renderer = renderer;
        for (int s = 0; s <= SLOTS; s++) {
            int slot = s;
            slotForms[s] = (rows, b, p) -> b < distorts.length && rows == this.rows && distorts[b] && slotOf[b] == slot ? p : null;
        }
    }

    /** Starts the apply's program, which every slot shares; whether it is built. */
    public static boolean precacheApply() {
        if (precacheApply == null) precacheApply = CgMaterial.load(APPLY_SHADER).instance();
        CgPipeline pipeline = CgMaterialProxy.of(precacheApply).pipeline(CgInstanceKind.OBJECT);
        return pipeline == null || pipeline.precache();
    }

    /**
     * Decides where each haze is applied. Walking the translucent rows in key order, a sharp row that the hazes pending
     * before it overlap gets their apply placed just before it, on a slot none of whose applies that rect overlaps,
     * and then draws in place; where no slot is free it stays sharp, for the pass after the final apply. Every haze left
     * goes to the final apply. Answers how many rows distort; 0 until the apply is built where the view holds unready
     * draws ({@code SKIP_UNREADY}), the hazes held and counted.
     * {@code base} is the translucent pass's form, which the rows drawn in place and after the hazes keep.
     */
    public int plan(CgStageFiring firing, CgViewPasses passes, float scale, CgCommandForm base) {
        this.scale = scale;
        this.afterBase = base;
        CgView view = firing.view();
        width = view.width();
        height = view.height();
        rows = passes.visibility().batches();
        int count = rows.size();
        if (distorts.length < count) {
            int n = Math.max(count, distorts.length * 2);
            slotOf = new byte[n];
            distorts = new boolean[n];
            inPass = new boolean[n];
            sharp = new boolean[n];
            inPlace = new boolean[n];
            rects = new float[n * 4];
            sorted = new int[n];
            scratch = new int[n];
        }
        applies = 0;
        Arrays.fill(slotRectCount, 0);
        boolean apart = view.textures().linear() && firing.host().target().lendsAttachments();
        // A view that compiles at the draw draws its hazes now; one that holds waits for the apply's build
        boolean ready = precacheApply() || !view.flag(CgSceneRenderer.SKIP_UNREADY);
        CgCamera camera = view.camera();
        viewProjection.set(camera.projection()).mul(camera.view());
        int distorting = 0, sharpCount = 0, n = 0;
        for (int b = 0; b < count; b++) {
            boolean translucent = CgSceneRenderer.queue(rows, b) >= CgRenderQueue.TRANSPARENT_THRESHOLD
                    && (rows.flags(b) & CgMeshBatches.HALF_RESOLUTION) == 0 && !(apart && separate(rows, b));
            boolean s = translucent && after(rows, b);
            boolean d = translucent && CgMaterialProxy.of(rows.material(b)).hasDistortionPass();
            if (d && !ready) {
                d = false;   // the apply is a pass of the renderer's own: its hazes wait for it, counted
                CgTrace.add(CgChannels.WORLD, UNREADY, 1);
            }
            distorts[b] = d;
            inPass[b] = translucent;
            sharp[b] = s;
            inPlace[b] = false;
            slotOf[b] = FINAL;
            if (d) distorting++;
            if (s) sharpCount++;
            if (d || translucent) {
                rect(camera, b);
                sorted[n++] = b;
            }
        }
        CgTrace.counter(CgChannels.WORLD, "scene.distortionDraws", distorting);
        if (distorting > 0 && sharpCount > 0) {
            sortByKey(n);
            int pending = 0;
            float margin = MARGIN * height + SPREAD;
            for (int k = 0; k < n; k++) {
                int b = sorted[k];
                System.arraycopy(rects, b * 4, drawRect, 0, 4);
                if (sharp[b]) {
                    if (pending == 0 || !overlaps(pendingRect, drawRect, margin)) {
                        inPlace[b] = true;
                    } else {
                        int slot = freeSlot(pendingRect);
                        if (slot >= 0) {
                            for (int j = 0; j < k; j++) {
                                int h = sorted[j];
                                if (distorts[h] && slotOf[h] == FINAL) slotOf[h] = (byte) slot;
                            }
                            placeApply(slot, pendingRect, rows.key(b) - 1);
                            pending = 0;
                            inPlace[b] = true;
                        }
                    }
                }
                if (distorts[b]) {
                    if (pending++ == 0) System.arraycopy(drawRect, 0, pendingRect, 0, 4);
                    else union(pendingRect, drawRect);
                }
            }
        }
        finalUsed = false;
        for (int b = 0; b < count; b++) {
            if (!distorts[b] || slotOf[b] != FINAL) continue;
            System.arraycopy(rects, b * 4, drawRect, 0, 4);
            if (!finalUsed) System.arraycopy(drawRect, 0, finalRect, 0, 4);
            else union(finalRect, drawRect);
            finalUsed = true;
        }
        CgTrace.counter(CgChannels.WORLD, "scene.distortionApplies", applies + (finalUsed ? 1 : 0));
        return distorting;
    }

    /** The planned slots' Distortion passes, each into its layer: ahead of the translucent pass, whose applies read them. */
    public void recordBends(CgStageFiring firing, CgViewPasses passes) {
        Arrays.fill(layerOf, -1);
        int layers = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slotRectCount[slot] > 0) recordBend(firing, passes, slot, layers++);
        }
        field.set(offsets(), layers);
    }

    /**
     * Each apply placed in the translucent pass, sorted just before the row it must not bend, and the sharp rows drawn in
     * place, into {@code translucent}. Sort it after.
     */
    public void addApplies(CgDrawCommands translucent, CgViewPasses passes, CgRecording rec) {
        for (int a = 0; a < applies; a++) {
            CgMaterialInstance material = apply(applySlot[a]);
            CgPipeline pipeline = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) continue;
            translucent.add(pipeline, CgMaterialProxy.of(material).bindings(), QUAD).sortKey(applyKey[a])
                    .frameRecord(applyRecord(rec, applyRect, a * 4));
        }
        translucent.append(passes.commands(CgEnginePasses.AFTER_DISTORTION, rec, placed));
    }

    /**
     * The hazes no apply in the translucent pass took, into the final layer and applied over their rect after it; then
     * the field, where any layer holds offsets, as the view's distortion texture.
     */
    public void recordFinal(CgStageFiring firing, CgViewPasses passes) {
        if (finalUsed) {
            recordBend(firing, passes, FINAL, field.count());
            field.set(offsets, field.count() + 1);
            CgMaterialInstance material = apply(FINAL);
            CgPipeline pipeline = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
            if (pipeline != null) {
                CgRecording rec = firing.recording();
                if (finalApply == null || finalApply.table() != rec.primitives()) finalApply = new CgDrawCommands(rec.primitives());
                CgDrawCommands applied = finalApply;
                applied.clear();
                applied.add(pipeline, CgMaterialProxy.of(material).bindings(), QUAD).frameRecord(applyRecord(rec, finalRect, 0));
                rec.raster("scene.distortionApply").color(firing.target(), CgLoad.load())
                        .depth(firing.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(firing.constants())
                        .readsColor(firing.target()).readsDepth(firing.depth()).timed(GPU_APPLY).add(applied).end();
            }
        }
        if (field.count() > 0) {
            firing.view().textures().distortion(offsets);
            firing.view().publish(CgDistortionField.KEY, field);
        }
    }

    /**
     * The after-distortion pass's form over the plan's base: the sharp rows the plan did not draw in place in the
     * translucent pass.
     */
    public CgCommandForm afterForm() {
        return after;
    }

    private boolean inPlaceRow(CgMeshBatches rows, int b) {
        return rows == this.rows && b < inPlace.length && inPlace[b];
    }

    /** Forgets its materials, which the material registry frees with the context. */
    public void release() {
        Arrays.fill(applyMaterials, null);
        Arrays.fill(applyBound, null);
        precacheApply = null;
        offsets = null;
    }

    private void recordBend(CgStageFiring firing, CgViewPasses passes, int slot, int layer) {
        CgGraphTexture target = offsets();
        layerOf[slot] = layer;
        firing.constants().write(block, 0);
        constants.read(block, 0).resolution(target.getWidth(), target.getHeight());
        CgDrawCommands bends = passes.commands(CgEnginePasses.DISTORTION, firing.recording(), slotForms[slot]);
        firing.recording().raster("scene.distortionBend").color(target, 0, layer, CgLoad.CLEAR).constants(constants).state(BEND_STATE)
                .readsDepth(firing.depth())
                .texture(CgBindingModel.LIGHTMAP, firing.view().hostTextures().lightmapTexture()).timed(GPU_BEND).add(bends).end();
    }

    /** Row {@code b}'s rect on screen in pixels from the top left: its primitive's box projected, or the whole view. */
    private void rect(CgCamera camera, int b) {
        int at = b * 4;
        CgAABBc box = ((CgPrimitiveComponent<?>) rows.primitive(b)).worldBounds();
        if (box == null || !screenOf(camera, box, at)) {
            rects[at] = 0f;
            rects[at + 1] = 0f;
            rects[at + 2] = width;
            rects[at + 3] = height;
        }
    }

    /**
     * {@code box}'s corners projected, into {@link #rects} at {@code at}: a box crossing the near plane is cut by it, its
     * edges' crossings projected with the corners in front. False for a box wholly behind it.
     */
    private boolean screenOf(CgCamera camera, CgAABBc box, int at) {
        Matrix4f m = viewProjection;
        double ox = camera.originX(), oy = camera.originY(), oz = camera.originZ();
        for (int c = 0; c < 8; c++) {
            float x = (float) (((c & 1) == 0 ? box.minX() : box.maxX()) - ox);
            float y = (float) (((c & 2) == 0 ? box.minY() : box.maxY()) - oy);
            float z = (float) (((c & 4) == 0 ? box.minZ() : box.maxZ()) - oz);
            clipX[c] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            clipY[c] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            clipW[c] = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
        }
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            if (clipW[c] >= NEAR_W) {
                float nx = clipX[c] / clipW[c], ny = clipY[c] / clipW[c];
                x0 = Math.min(x0, nx);
                x1 = Math.max(x1, nx);
                y0 = Math.min(y0, ny);
                y1 = Math.max(y1, ny);
            }
            for (int bit = 1; bit < 8; bit <<= 1) {
                if ((c & bit) != 0) continue;
                int d = c | bit;
                if ((clipW[c] >= NEAR_W) == (clipW[d] >= NEAR_W)) continue;
                float t = (NEAR_W - clipW[c]) / (clipW[d] - clipW[c]);
                float nx = (clipX[c] + (clipX[d] - clipX[c]) * t) / NEAR_W;
                float ny = (clipY[c] + (clipY[d] - clipY[c]) * t) / NEAR_W;
                x0 = Math.min(x0, nx);
                x1 = Math.max(x1, nx);
                y0 = Math.min(y0, ny);
                y1 = Math.max(y1, ny);
            }
        }
        if (x0 > x1) return false;
        rects[at] = (x0 * 0.5f + 0.5f) * width;
        rects[at + 1] = (0.5f - y1 * 0.5f) * height;
        rects[at + 2] = (x1 * 0.5f + 0.5f) * width;
        rects[at + 3] = (0.5f - y0 * 0.5f) * height;
        return true;
    }

    private static boolean after(CgMeshBatches rows, int b) {
        return (rows.flags(b) & CgMeshBatches.AFTER_DISTORTION) != 0
                || CgSceneRenderer.queue(rows, b) >= CgRenderQueue.AFTER_DISTORTION_THRESHOLD || separate(rows, b);
    }

    private static boolean separate(CgMeshBatches rows, int b) {
        return rows.layer(b) == CgSortLayer.AFTER_DOF || CgMaterialProxy.of(rows.material(b)).translucencyPass().apart();
    }

    /** The first slot none of whose applies {@code rect} overlaps, its offsets' bilinear spread included; -1 if none. */
    private int freeSlot(float[] rect) {
        for (int slot = 0; slot < SLOTS; slot++) {
            float[] r = slotRects[slot];
            boolean free = true;
            for (int k = 0; k < slotRectCount[slot] && free; k++) {
                free = !(rect[0] - SPREAD < r[k * 4 + 2] && r[k * 4] < rect[2] + SPREAD
                        && rect[1] - SPREAD < r[k * 4 + 3] && r[k * 4 + 1] < rect[3] + SPREAD);
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

    /** {@link #sorted}'s first {@code n} by key, ties by index: the order the translucent pass draws them in. */
    private void sortByKey(int n) {
        int[] a = sorted, t = scratch;
        for (int run = 1; run < n; run *= 2) {
            for (int lo = 0; lo < n; lo += 2 * run) {
                int mid = Math.min(lo + run, n), hi = Math.min(lo + 2 * run, n), l = lo, r = mid, o = lo;
                while (l < mid && r < hi) t[o++] = Long.compareUnsigned(rows.key(a[r]), rows.key(a[l])) < 0 ? a[r++] : a[l++];
                while (l < mid) t[o++] = a[l++];
                while (r < hi) t[o++] = a[r++];
            }
            int[] swap = a;
            a = t;
            t = swap;
        }
        if (a != sorted) System.arraycopy(a, 0, sorted, 0, n);
    }

    /** The offsets array, {@code scale} of the view's size. */
    private CgGraphTexture offsets() {
        int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
        if (offsets == null || offsets.getWidth() != w || offsets.getHeight() != h) {
            offsets = CgGraphTexture.transientTexture("cg_scene_distortion", CgTextureDesc.array(w, h, CgDistortionField.MAX, FORMAT));
        }
        return offsets;
    }

    /** Slot {@code slot}'s apply, reading its layer. */
    private CgMaterialInstance apply(int slot) {
        CgMaterialInstance material = applyMaterials[slot];
        if (material == null) applyMaterials[slot] = material = CgMaterial.load(APPLY_SHADER).instance();
        CgTexture fields = offsets;
        int layer = layerOf[slot];
        if (fields != applyBound[slot] || layer != applyLayer[slot]) {
            material.sampler("_Distortion", 0, fields).set1i("_Layer", layer);
            applyBound[slot] = fields;
            applyLayer[slot] = layer;
        }
        return material;
    }

    /** An apply's record: its rect, pixels from the top left, as the NDC corners its vertex stage reads. */
    private int applyRecord(CgRecording rec, float[] rect, int at) {
        CgPrimitiveTable table = rec.primitives();
        int record = table.add();
        float[] data = table.data();
        Arrays.fill(data, record, record + 48, 0f);
        data[record] = data[record + 5] = data[record + 10] = data[record + 15] = 1f;
        data[record + 32] = rect[at] / width * 2f - 1f;
        data[record + 33] = 1f - rect[at + 3] / height * 2f;
        data[record + 34] = rect[at + 2] / width * 2f - 1f;
        data[record + 35] = 1f - rect[at + 1] / height * 2f;
        return CgPrimitiveTable.slot(record);
    }
}
