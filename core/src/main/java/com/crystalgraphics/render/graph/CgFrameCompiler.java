package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What an execution of a frame decides before its first step, so the step loop decides nothing (Unreal RDG's
 * {@code Compile}): which raster steps share a render pass, and which sample their depth read-only. Written into the
 * frame's per-step arrays; the executor replays them.
 *
 * <ul>
 *   <li>Run at an execution's start, not at build: a decision about an external target needs what it lends, known once
 *       the execution opens it. A graph texture's is its description's.</li>
 *   <li>A frame executed again merges nothing, and its decisions are made for that.</li>
 * </ul>
 */
final class CgFrameCompiler {

    private CgFrameCompiler() {}

    /**
     * Each raster step's render pass: the end of the run it begins ({@link CgFrame#runEnd}, itself for a step that
     * shares none), the run's second colour, and whether it samples its depth read-only ({@link CgFrame#readOnlyDepth}).
     *
     * @param takes      whether the device takes transitions: read-only depth and merging are a device's
     * @param merging    whether this execution merges (a first execution where the device takes them)
     * @param gpuCounts  whether indirect draws are counted on the GPU, their commands written by a dispatch first
     */
    static void renderPasses(CgFrame frame, boolean takes, boolean merging, boolean gpuCounts) {
        int n = frame.stepCount;
        frame.compiledSteps(n);
        Arrays.fill(frame.runEnd, 0, n, -1);
        Arrays.fill(frame.runAttachment, 0, n, null);
        for (int s = 0; s < n; s++) {
            frame.runEnd[s] = s;
            frame.readOnlyDepth[s] = frame.steps[s] instanceof CgRasterPass raster && readOnlyDepth(frame, s, raster, takes, null);
        }
        if (!merging) return;
        for (int s = 0; s < n; s++) {
            if (!(frame.steps[s] instanceof CgRasterPass first) || first.target == null || unmergeable(frame, first)) continue;
            CgGraphTexture attachment = first.attachment();
            int k = s + 1;
            while (k < n && frame.steps[k] instanceof CgRasterPass next && joins(frame, s, first, attachment, k, next, takes, gpuCounts)) {
                if (attachment == null) attachment = next.attachment();
                k++;
            }
            int end = k - 1;
            frame.runEnd[s] = end;
            if (end > s) {
                // Its steps draw with the run's second colour bound throughout
                for (int j = s; j <= end; j++) {
                    frame.runAttachment[j] = attachment;
                    if (frame.steps[j] instanceof CgRasterPass raster) {
                        frame.readOnlyDepth[j] = readOnlyDepth(frame, j, raster, takes, attachment);
                    }
                }
            }
            s = end;
        }
    }

    /**
     * Whether raster step {@code s} samples its target's depth in place, read-only, rather than copying it: on a device,
     * where no draw of the pass writes depth and its depth texture can be sampled, a single level and layer. Unreal's
     * translucency binds {@code DepthRead} so; a pass writing the depth it reads keeps its copy.
     *
     * @param runAttachment the second colour of the run it draws in, or null
     */
    private static boolean readOnlyDepth(CgFrame frame, int s, CgRasterPass pass, boolean takes,
                                         @Nullable CgGraphTexture runAttachment) {
        if (!takes || pass.sceneDepthUnit() < 0 || frame.rasters[s].writesDepth || pass.depthFrom() != null
                || pass.load.clearsDepth() || pass.load.clearsStencil()) {
            return false;
        }
        CgGraphTexture target = pass.target != null ? pass.target : CgGraphTexture.current(), depth = pass.depthTexture();
        if (depth != target || depth.kind() == CgGraphTexture.Kind.EXTERNAL) return samplesDepth(depth);
        CgGraphTexture attachment = pass.attachment() != null ? pass.attachment() : runAttachment;
        if (attachment != null || target.getLevels() != 1 || target.getLayers() != 1) return false;
        return samplesDepth(target);
    }

    /**
     * Whether {@code texture}'s depth can be sampled: an external target's as it was opened, a graph texture's as the
     * graph makes it ({@code CgGraphStorage.deviceDesc}: a depth format, one sample).
     */
    private static boolean samplesDepth(CgGraphTexture texture) {
        if (texture.kind() == CgGraphTexture.Kind.EXTERNAL) {
            CgGraphStorage storage = CgExecutor.storage(texture);
            CgGpuTexture image = storage == null ? null : storage.depth();
            return image != null && image.desc().usage().contains(CgGpuTexture.Usage.SAMPLED);
        }
        return texture.desc() != null && texture.desc().isDepth() && !texture.desc().isMultisampled();
    }

    private static boolean unmergeable(CgFrame frame, CgRasterPass pass) {
        return pass.damage() != null && !frame.wholePasses || pass.target.kind() == CgGraphTexture.Kind.HISTORY;
    }

    /**
     * Whether raster step {@code k} joins the run {@code first} begins at {@code start}, whose second colour so far is
     * {@code attachment}: the same target, level, layer and second colour, loading what is there, with no copy, rename,
     * damage or indirect command of its own, one depth mode, reading nothing the run writes (Unreal RDG's
     * {@code MergeRenderPasses}).
     */
    private static boolean joins(CgFrame frame, int start, CgRasterPass first, @Nullable CgGraphTexture attachment, int k,
                                 CgRasterPass next, boolean takes, boolean gpuCounts) {
        if (next.target != first.target || next.level != first.level || next.layer != first.layer
                || next.attachment() != null && attachment != null && next.attachment() != attachment || next.load.clears()
                || next.replacesTarget()
                || next.depthFrom() != null || next.colorFrom() != null || unmergeable(frame, next)) {
            return false;
        }
        // One depth mode a pass, as the run's own passes decide it before it is a run
        if (readOnlyDepth(frame, k, next, takes, null) != readOnlyDepth(frame, start, first, takes, null)) return false;
        CgFrame.Raster packed = frame.rasters[k];
        if (packed.indirects > 0 && gpuCounts) return false;   // its commands are written by a dispatch first
        for (int b = 0; b < packed.count; b++) if (packed.copyBefore[b] != 0) return false;
        for (int i = frame.accessFrom[k]; i < frame.accessFrom[k + 1]; i++) {
            CgGraphResource view = frame.accessView[i];
            int bits = frame.accessBits[i];
            if (view == first.depthBeside()) {
                // Drawn with another's depth, a pass names it read and written: ordered on it, sampling none of it.
                if ((bits & ~(CgAccess.COLOR_WRITE | CgAccess.SAMPLED_READ)) != 0) return false;
            } else if ((view == first.target || view == attachment || view == next.attachment())
                    && (bits & ~CgAccess.COLOR_WRITE) != 0) {
                return false;
            }
        }
        return true;
    }
}
