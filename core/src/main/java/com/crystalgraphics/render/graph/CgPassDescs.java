package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The executor's render-pass descriptions, kept and handed out again while their attachments and load operations are
 * the same: a frame like the last describes its passes without allocating. Unreal fills an {@code FRHIRenderPassInfo}
 * on the stack each pass; with no stack values here, an unchanged description is the one already made.
 *
 * <pre>{@code
 * passDescs.begin(w, h);
 * passDescs.color(texture, level, layer, CgPassDesc.LoadOp.CLEAR, r, g, b, a);
 * passDescs.depth(depthTexture, level, 0, CgPassDesc.LoadOp.LOAD, 1f, CgPassDesc.LoadOp.LOAD, 0, false);
 * CgPassDesc desc = passDescs.end();   // the kept one where nothing moved
 * }</pre>
 *
 * <ul>
 *   <li>Attachments compare by identity: a remade texture is a new description.</li>
 *   <li>Render thread; one description built at a time.</li>
 * </ul>
 */
final class CgPassDescs {

    /** Descriptions kept, the oldest replaced first past it. */
    private static final int KEPT = 128;
    private static final int MAX_COLORS = 8;

    /** One kept description and what it was built from. */
    private static final class Entry {
        int width, height, colorCount, hash;
        final CgGpuTexture[] colors = new CgGpuTexture[MAX_COLORS];
        final int[] colorLevels = new int[MAX_COLORS], colorLayers = new int[MAX_COLORS];
        final CgPassDesc.LoadOp[] colorLoads = new CgPassDesc.LoadOp[MAX_COLORS];
        final float[] clear = new float[MAX_COLORS * 4];
        @Nullable
        CgGpuTexture depth;
        int depthLevel, depthLayer, clearStencil;
        CgPassDesc.LoadOp depthLoad, stencilLoad;
        float clearDepth;
        boolean readOnly;
        CgPassDesc desc;

        boolean sameAs(Entry o) {
            if (hash != o.hash || width != o.width || height != o.height || colorCount != o.colorCount) return false;
            for (int i = 0; i < colorCount; i++) {
                if (colors[i] != o.colors[i] || colorLevels[i] != o.colorLevels[i] || colorLayers[i] != o.colorLayers[i]
                        || colorLoads[i] != o.colorLoads[i]) return false;
                if (colorLoads[i] == CgPassDesc.LoadOp.CLEAR) {
                    for (int c = i * 4; c < i * 4 + 4; c++) if (Float.compare(clear[c], o.clear[c]) != 0) return false;
                }
            }
            if (depth != o.depth) return false;
            return depth == null || depthLevel == o.depthLevel && depthLayer == o.depthLayer && depthLoad == o.depthLoad
                    && stencilLoad == o.stencilLoad && readOnly == o.readOnly
                    && (depthLoad != CgPassDesc.LoadOp.CLEAR || Float.compare(clearDepth, o.clearDepth) == 0)
                    && (stencilLoad != CgPassDesc.LoadOp.CLEAR || clearStencil == o.clearStencil);
        }

        void copyFrom(Entry o) {
            width = o.width;
            height = o.height;
            colorCount = o.colorCount;
            hash = o.hash;
            System.arraycopy(o.colors, 0, colors, 0, MAX_COLORS);
            System.arraycopy(o.colorLevels, 0, colorLevels, 0, MAX_COLORS);
            System.arraycopy(o.colorLayers, 0, colorLayers, 0, MAX_COLORS);
            System.arraycopy(o.colorLoads, 0, colorLoads, 0, MAX_COLORS);
            System.arraycopy(o.clear, 0, clear, 0, clear.length);
            depth = o.depth;
            depthLevel = o.depthLevel;
            depthLayer = o.depthLayer;
            depthLoad = o.depthLoad;
            stencilLoad = o.stencilLoad;
            clearDepth = o.clearDepth;
            clearStencil = o.clearStencil;
            readOnly = o.readOnly;
        }

        CgPassDesc build() {
            List<CgPassDesc.Color> list = new ArrayList<>(colorCount);
            for (int i = 0; i < colorCount; i++) {
                list.add(new CgPassDesc.Color(CgTextureView.attachment(colors[i], colorLevels[i], colorLayers[i]),
                        colorLoads[i], clear[i * 4], clear[i * 4 + 1], clear[i * 4 + 2], clear[i * 4 + 3],
                        CgPassDesc.StoreOp.STORE));
            }
            CgPassDesc.Depth d = depth == null ? null : new CgPassDesc.Depth(CgTextureView.attachment(depth, depthLevel,
                    depthLayer), depthLoad, clearDepth, stencilLoad, clearStencil, CgPassDesc.StoreOp.STORE, readOnly);
            return new CgPassDesc("graph", List.copyOf(list), d, width, height);
        }
    }

    private final Entry pending = new Entry();
    private final Entry[] kept = new Entry[KEPT];
    private int keptCount, nextReplaced;

    /** Starts the description of a pass {@code width} x {@code height}. */
    void begin(int width, int height) {
        pending.width = width;
        pending.height = height;
        pending.colorCount = 0;
        pending.depth = null;
    }

    /** Its next colour attachment: {@code texture}'s {@code level} and {@code layer}, cleared to rgba or loaded. */
    void color(CgGpuTexture texture, int level, int layer, CgPassDesc.LoadOp load, float r, float g, float b, float a) {
        int i = pending.colorCount++;
        if (i == MAX_COLORS) throw new IllegalStateException("a pass has more than " + MAX_COLORS + " colour attachments");
        pending.colors[i] = texture;
        pending.colorLevels[i] = level;
        pending.colorLayers[i] = layer;
        pending.colorLoads[i] = load;
        pending.clear[i * 4] = r;
        pending.clear[i * 4 + 1] = g;
        pending.clear[i * 4 + 2] = b;
        pending.clear[i * 4 + 3] = a;
    }

    /** Its depth attachment. */
    void depth(CgGpuTexture texture, int level, int layer, CgPassDesc.LoadOp depthLoad, float clearDepth,
               CgPassDesc.LoadOp stencilLoad, int clearStencil, boolean readOnly) {
        pending.depth = texture;
        pending.depthLevel = level;
        pending.depthLayer = layer;
        pending.depthLoad = depthLoad;
        pending.clearDepth = clearDepth;
        pending.stencilLoad = stencilLoad;
        pending.clearStencil = clearStencil;
        pending.readOnly = readOnly;
    }

    /** The description begun: one kept where every attachment and load is the same, else made and kept. */
    CgPassDesc end() {
        int h = 31 * pending.width + pending.height;
        for (int i = 0; i < pending.colorCount; i++) h = 31 * h + System.identityHashCode(pending.colors[i]) + pending.colorLayers[i];
        pending.hash = 31 * h + System.identityHashCode(pending.depth);
        for (int i = 0; i < keptCount; i++) if (kept[i].sameAs(pending)) return kept[i].desc;
        Entry e;
        if (keptCount < KEPT) {
            e = kept[keptCount++] = new Entry();
        } else {
            e = kept[nextReplaced];
            nextReplaced = (nextReplaced + 1) % KEPT;
        }
        e.copyFrom(pending);
        e.desc = e.build();
        return e.desc;
    }

    /** Lets every kept attachment go: the device's textures were remade (a resize) or closed. */
    void clear() {
        for (int i = 0; i < keptCount; i++) kept[i] = null;
        keptCount = 0;
        nextReplaced = 0;
    }
}
