package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgFrameBindings;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.mesh.CgMeshStore;

/**
 * What an execution of a frame decides before its first step, so the step loop decides nothing (Unreal RDG's
 * {@code Compile}): which raster steps share a render pass and which sample their depth read-only; which compute steps
 * run on the compute queue; each step's prologue of transitions and the split transitions it begins. Written into the
 * frame's per-step arrays; the executor replays them, issuing each transition from the state it tracks.
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
        if (!takes || !pass.readsOwnDepth() || frame.rasters[s].writesDepth || pass.depthFrom() != null
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
            CgGraphStorage storage = CgGraphDevice.get().storage(texture);
            CgGpuTexture image = storage == null ? null : storage.depth();
            return image != null && image.desc().usage().contains(CgGpuTexture.Usage.SAMPLED);
        }
        return texture.desc() != null && texture.desc().isDepth() && !texture.desc().isMultisampled();
    }

    /**
     * Each step's async placement, its prologue (Unreal's {@code CompilePassBarriers}: what it reads and writes moved to
     * its access, a run's by its first step) and the split transitions it begins (a later read two or more steps on,
     * from its producer's end). After {@link #renderPasses}.
     *
     * @param asyncCompute whether the device has a compute queue this execution uses; {@code asyncAll} runs every
     *                     compute step there
     * @param splitting    whether this execution splits transitions (a first execution on a device that takes them)
     */
    static void transitions(CgFrame frame, boolean asyncCompute, boolean asyncAll, boolean splitting) {
        int n = frame.stepCount;
        for (int s = 0; s < n; s++) {
            frame.async[s] = asyncCompute && frame.steps[s] instanceof CgComputePass compute && (compute.isAsync() || asyncAll);
        }
        int runStart = -1, runEnd = -1;
        for (int s = 0; s < n; s++) {
            frame.prologueFrom[s] = frame.prologueCount;
            if (s > runEnd) {
                runStart = s;
                runEnd = frame.runEnd[s];
            }
            CgPass pass = frame.steps[s];
            frame.prologued[s] = !(s > runStart && s <= runEnd) && !(pass instanceof CgComputePass) && !(pass instanceof CgPass.Include);
            if (!frame.prologued[s]) continue;
            CgGraphTexture runAttachment = runEnd > s ? frame.runAttachment[s] : null;
            if (runEnd > s && runAttachment != null && ((CgRasterPass) pass).attachment() == null) {
                frame.prologue(runAttachment, 0, 0, 0);   // a later pass's attachment, bound from the run's start
            }
            for (int j = s; j <= Math.max(s, runEnd); j++) {
                CgPass step = frame.steps[j];
                for (int i = frame.accessFrom[j]; i < frame.accessFrom[j + 1]; i++) {
                    int bits = frame.accessBits[i];
                    if (bits == 0) continue;
                    if (frame.accessView[i] instanceof CgGraphBuffer buffer) {
                        frame.prologue(buffer, bits, -1, -1);
                    } else if (step instanceof CgRasterPass raster && (bits & CgAccess.COLOR_WRITE) != 0) {
                        if (j > s) continue;   // the run's attachments, moved with its first step
                        CgGraphTexture texture = (CgGraphTexture) frame.accessView[i];
                        boolean target = texture == raster.target;
                        if (texture == raster.depthTexture() && frame.readOnlyDepth[j]) {
                            frame.prologue(texture, CgAccess.COLOR_WRITE | CgAccess.DEPTH_READ, -1, -1);   // sampled in place
                            continue;
                        }
                        frame.prologue(texture, bits & ~CgAccess.COLOR_WRITE, target ? raster.level : 0, target ? raster.layer : 0);
                    } else {
                        frame.prologue(frame.accessView[i], bits, -1, -1);
                    }
                }
            }
        }
        frame.prologueFrom[n] = frame.prologueCount;

        runEnd = -1;
        for (int s = 0; s < n; s++) {
            frame.splitFrom[s] = frame.splitCount;
            if (s > runEnd) runEnd = frame.runEnd[s];
            frame.splits[s] = splitting && s >= runEnd && splittable(frame, s);
            frame.splitEnd[s] = Integer.MAX_VALUE;
            if (!frame.splits[s]) continue;
            for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
                int next = frame.nextAccess[i];
                if (next < 0) continue;
                int c = frame.accessStep[next], bits = frame.accessBits[next];
                if (c <= s + 1 || bits == 0 || (bits & CgResourceStates.WRITES) != 0 || !splittable(frame, c)
                        || frame.steps[c] instanceof CgRasterPass && frame.accessView[next] instanceof CgGraphTexture
                        && (bits & CgAccess.COLOR_WRITE) != 0) {
                    continue;
                }
                if (frame.accessView[next] instanceof CgGraphBuffer buffer && buffer.resource().kind() == CgGraphBuffer.Kind.HISTORY) {
                    continue;
                }
                frame.split(next);
                frame.splitEnd[s] = Math.min(frame.splitEnd[s], c);
            }
        }
        frame.splitFrom[n] = frame.splitCount;
    }

    /**
     * Each raster step's multi-draw runs ({@link CgFrame.Raster#joinEnd}): direct batches the mesh store joins into one
     * call under one pipeline, bindings and scissor, and indirect batches whose commands one multi-draw reads. After the
     * frame's meshes are placed, which is what decides whether two join.
     *
     * @param multiDraw whether the device draws several commands a call
     */
    static void multiDraws(CgFrame frame, boolean multiDraw) {
        CgMeshStore store = CgMeshStore.get();
        CgFrameBindings table = frame.bindings;
        for (int s = 0; s < frame.stepCount; s++) {
            if (!(frame.steps[s] instanceof CgRasterPass)) continue;
            CgFrame.Raster packed = frame.rasters[s];
            if (packed.joinEnd.length < packed.count) packed.joinEnd = new int[Math.max(packed.count, packed.joinEnd.length * 2)];
            for (int b = 0; b < packed.count; b++) {
                int end = b;
                if (packed.counts[b] != null) {
                    if (multiDraw) while (end + 1 < packed.count && joinableIndirect(table, packed, b, end + 1)) end++;
                } else if (multiDraw && packed.objects[b] == null && packed.buffers[b * CgDrawChunk.BUFFERS] == null
                        && b + 1 < packed.count && joinable(table, packed, b, b + 1)
                        && store.joins(mesh(packed, b), mesh(packed, b))) {
                    while (end + 1 < packed.count && joinable(table, packed, b, end + 1)
                            && store.joins(mesh(packed, end + 1), mesh(packed, b))) {
                        end++;
                    }
                }
                packed.joinEnd[b] = end;
                b = end;
            }
        }
    }

    private static CgMesh mesh(CgFrame.Raster packed, int b) {
        return packed.kind[b] == CgInstanceKind.OBJECT.ordinal() ? packed.mesh[b] : CgExecutor.UNIT_QUAD;
    }

    /**
     * Batch {@code k} drawn directly under {@code b}'s pipeline, bindings and scissor, with no target copy before it: its
     * bindings {@code b}'s {@link CgFrameBindings#share share}, so another material of one arena joins (Bevy's slab key).
     */
    static boolean joinable(CgFrameBindings table, CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] == null && packed.objects[k] == null && packed.buffers[k * CgDrawChunk.BUFFERS] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && table.share(packed.binding[k]) == table.share(packed.binding[b]) && packed.scissor[k] == packed.scissor[b]
                && packed.group[k] == packed.group[b];
    }

    /**
     * Indirect batch {@code k} drawn in one multi-draw after {@code b}: as {@link #joinable}, reading the same object
     * records and buffers, from a mesh the store {@link CgMeshStore#joins joins} with {@code b}'s.
     */
    private static boolean joinableIndirect(CgFrameBindings table, CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] != null && packed.copyBefore[k] == 0
                && packed.pipeline[k] == packed.pipeline[b] && table.share(packed.binding[k]) == table.share(packed.binding[b])
                && packed.scissor[k] == packed.scissor[b] && packed.objects[k] == packed.objects[b]
                && sameBuffers(packed, b, k) && packed.group[k] == packed.group[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    /** Whether batches {@code b} and {@code k} read the same buffers at the same bindings. */
    static boolean sameBuffers(CgFrame.Raster packed, int b, int k) {
        int n = CgDrawChunk.BUFFERS;
        for (int s = 0; s < n; s++) {
            if (packed.buffers[k * n + s] != packed.buffers[b * n + s] || packed.bufferAt[k * n + s] != packed.bufferAt[b * n + s]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Each step's reads of another texture's depth, sampled in place where the device takes transitions and the depth is
     * the graph's and not the step's own ({@link CgFrame#depthFromInPlace}), and, executing again, the steps skipped
     * ({@link CgFrame#skipped}): a step of a simulation, anything writing what outlives the frame, taken once.
     */
    static void steps(CgFrame frame, boolean takes, boolean again) {
        for (int s = 0; s < frame.stepCount; s++) {
            CgPass pass = frame.steps[s];
            frame.skipped[s] = again && doneOnce(frame, s, frame.keepRequested);
            CgGraphTexture from = pass instanceof CgRasterPass raster ? raster.depthFrom()
                    : pass instanceof CgComputePass compute ? compute.depthFrom() : null;
            frame.depthFromInPlace[s] = from != null && takes && from.kind() != CgGraphTexture.Kind.EXTERNAL && samplesDepth(from)
                    && !(pass instanceof CgRasterPass raster && raster.depthTexture() == from);
        }
    }

    /**
     * Each scene copy resolved for this execution: per raster batch what happens before it ({@link CgFrame.Raster#sceneOp},
     * from the copies the builder placed), and per step reading another texture's depth ({@link CgFrame#depthFromOp}), as
     * {@link CgSceneCopies}'s {@code COPY_COLOR}, {@code COPY_DEPTH}, {@code RESOLVE_DEPTH}, {@code REUSE_DEPTH} and
     * {@code SAMPLE_DEPTH}. After {@link #renderPasses} and {@link #steps}.
     *
     * <p>Scene depth is resolved once (Unreal's {@code AddResolveSceneDepthPass}): walking the steps in order, the resolved
     * copy names its source, and a reader of that source takes it as it is until something may write it: a raster step
     * whose draws write the depth, an include or a callback, a release, or the end of the transient it was copied from.
     * A skipped step does none of this.</p>
     */
    static void sceneCopies(CgFrame frame, CgSceneCopies.DepthSource resolved) {
        resolved.forget();
        int n = frame.stepCount;
        for (int s = 0; s < n; s++) {
            CgPass pass = frame.steps[s];
            frame.depthFromOp[s] = 0;
            if (!frame.skipped[s]) {
                if (pass instanceof CgPass.Include || pass instanceof CgPass.Callback) {
                    resolved.forget();   // what it executes may write any depth
                } else if (pass instanceof CgRasterPass raster) {
                    CgFrame.Raster packed = frame.rasters[s];
                    if (raster.depthFrom() != null) {
                        frame.depthFromOp[s] = frame.depthFromInPlace[s] ? CgSceneCopies.SAMPLE_DEPTH
                                : resolved.read(raster.depthFrom());
                    }
                    boolean inPlace = frame.readOnlyDepth[s];
                    for (int b = 0; b < packed.count; b++) {
                        int wants = packed.copyBefore[b], op = 0;
                        if ((wants & CgSceneCopies.COLOR) != 0) op |= CgSceneCopies.COPY_COLOR;
                        if ((wants & CgSceneCopies.DEPTH) != 0) {
                            if (inPlace) op |= CgSceneCopies.SAMPLE_DEPTH;
                            else if ((wants & CgSceneCopies.CLEAN) != 0) op |= resolved.read(raster.depthTexture());
                            else op |= CgSceneCopies.COPY_DEPTH;
                        }
                        packed.sceneOp[b] = op;
                    }
                    if (packed.writesDepth) resolved.written(raster.depthTexture());
                } else if (pass instanceof CgComputePass compute && compute.depthFrom() != null) {
                    frame.depthFromOp[s] = frame.depthFromInPlace[s] ? CgSceneCopies.SAMPLE_DEPTH : resolved.read(compute.depthFrom());
                }
            }
            for (int t = 0; t < frame.transients.size(); t++) {
                if (frame.releaseAfter[t] == s && frame.transients.get(t) instanceof CgGraphTexture texture) resolved.released(texture);
            }
            for (int i = 0; i < frame.released.size(); i++) {
                if (frame.releasedAfter[i] == s) resolved.forget();
            }
        }
    }

    private static boolean doneOnce(CgFrame frame, int s, boolean keepRequested) {
        CgPass pass = frame.steps[s];
        if (pass instanceof CgPass.TextureUpload || pass instanceof CgPass.Readback) return true;
        if (pass instanceof CgPass.BufferFill || pass instanceof CgPass.BufferUpdate || pass instanceof CgPass.BufferCopy) {
            return frame.outlives[s];
        }
        if (pass instanceof CgComputePass compute) return frame.outlives[s] && !compute.runsAgain();
        if (pass.target != null && pass.target.kind() == CgGraphTexture.Kind.HISTORY) return true;   // one version a frame
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
    }

    /** Whether step {@code s} may begin or end a split: a raster, compute or buffer step on the frame's queue. */
    private static boolean splittable(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        if (pass instanceof CgComputePass) return !frame.async[s];
        return pass instanceof CgRasterPass || pass instanceof CgPass.BufferFill || pass instanceof CgPass.BufferUpdate
                || pass instanceof CgPass.BufferCopy;
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
