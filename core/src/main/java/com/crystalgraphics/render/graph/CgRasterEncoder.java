package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.material.CgShader;
import com.crystalgraphics.render.CgDepthConvention;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgDrawStateCache;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.crystalgraphics.render.canvas.CgCurveBatch;
import com.crystalgraphics.render.canvas.CgQuadBatch;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;

/**
 * One execution's raster passes on the device: what runs outside the render pass first (the pass's snapshot, its
 * instance buffers and tables, its indirect commands, the copies it reads), then, in a device section, the render pass
 * over its attachments and each batch's pipeline, sets and draw through the state cache, a compiled run as one
 * multi-draw. Unreal's raster passes, encoded on {@code FRHICommandList} between {@code BeginRenderPass} and
 * {@code EndRenderPass}.
 */
final class CgRasterEncoder {

    private static final Logger LOGGER = LogManager.getLogger("CgExecutor");
    private static final int KINDS = CgInstanceKind.values().length;
    static final int UNIT_KINDS = (1 << CgInstanceKind.QUAD.ordinal()) | (1 << CgInstanceKind.CURVE.ordinal());
    private static final int OBJECT = CgInstanceKind.OBJECT.ordinal();
    /** Records written to the frame's table, once each, and ids the passes copied to name them: SR1's two counts. */
    private static final int PRIMITIVES = CgTrace.name("graph.primitives"), PRIMITIVE_IDS = CgTrace.name("graph.primitive-ids");
    private static final int TARGET_COPIES = CgTrace.name("graph.target-copies");
    private static final int TARGET_COPY_PIXELS = CgTrace.name("graph.target-copy-pixels");
    private static final int COMMAND_COUNT = CgTrace.name("graph.indirect-commands");
    /** Runs of draws a multi-draw ended only because the next draw binds other textures or properties. */
    private static final int BINDING_BREAKS = CgTrace.name("graph.multi-draw.binding-breaks");
    private static final int DEVICE_PASSES = CgTrace.name("graph.device-passes"),
            MID_PASS_SPLITS = CgTrace.name("graph.device-pass-splits");
    private static final int WRITE_COMMANDS = CgTrace.name("graph.writeCommands");
    private static final int BATCH_PIPELINE = CgTrace.name("graph.batch.pipeline"),
            BATCH_BINDINGS = CgTrace.name("graph.batch.bindings"), BATCH_DRAW = CgTrace.name("graph.batch.draw");
    private static final int DEVICE_PREPARE = CgTrace.name("graph.device.prepare"), DEVICE_PASS = CgTrace.name("graph.device.pass"),
            DEVICE_SECTION = CgTrace.name("graph.device.section"), DEVICE_OPEN = CgTrace.name("graph.device.open"),
            DEVICE_BATCHES = CgTrace.name("graph.device.batches"), DEVICE_END = CgTrace.name("graph.device.end");
    private static final int SETS_KEPT = CgTrace.name("bindings.sets-kept");
    private static final CgIndirect[] INDIRECT_MODES = CgIndirect.values();
    private static final int[] BREAKS = {
            CgTrace.name("graph.multi-draw.breaks.direct"), CgTrace.name("graph.multi-draw.breaks.copy"), CgTrace.name("graph.multi-draw.breaks.pipeline"),
            CgTrace.name("graph.multi-draw.breaks.binding"), CgTrace.name("graph.multi-draw.breaks.scissor"),
            CgTrace.name("graph.multi-draw.breaks.records"), CgTrace.name("graph.multi-draw.breaks.group"),
            CgTrace.name("graph.multi-draw.breaks.mesh")};

    private final CgExecutor execution;
    private final CgGraphDevice device;
    private final CgShaderBuffer[] instanceBuffers = new CgShaderBuffer[KINDS];
    /** The frame's object records at {@code OBJECT_DATA}, uploaded once; {@code instanceBuffers[OBJECT]} holds the ids. */
    private final CgShaderBuffer primitives;
    /** Identity ids at {@code OBJECT_IDS}, for a draw of its own records; kept across frames, {@link #identityIds} of them. */
    private final CgShaderBuffer identityBuffer;
    private int identityIds;
    /** Whether the frame executing has records in its table, which a pass of OBJECT draws binds. */
    private boolean tabled;
    /** Whether the pass drawing now runs its depth reversed: its compares and clear are converted to it. */
    private boolean reversed;
    private final CgDrawStateCache drawCache = new CgDrawStateCache();
    /** What was logged once: a pipeline skipped, a second attachment dropped. */
    private final Set<String> loggedOnce = new HashSet<>();
    private final List<CgFormat> deviceFormats = new ArrayList<>(4);
    private final int[] scissorRect = new int[4], scissorPart = new int[4];
    /** The pass's view textures stated as it executes: scene copies, depth sampled in place. */
    private final CgViewTextures view = new CgViewTextures();
    /**
     * The pass set's parameters: the pass's own (instances, records, tables) in {@link #passBase}, and the batch's over
     * them (its records, its buffers, its mesh's vertex words) in {@link #passBatch}, made again when those change.
     */
    private final CgBindings passBase = new CgBindings(), passBatch = new CgBindings();
    private int passVersion;
    /** What {@link #passBatch} was made for: the batch's records, vertex words and buffers by slot. */
    private Object batchRecords;
    private CgGpuBuffer batchWords;
    private final Object[] batchBuffers = new Object[CgDrawChunk.BUFFERS * 2];
    private final CgIndirectArgs commands = new CgIndirectArgs(this::dispatchCommands);
    private final CgBindings commandBindings = new CgBindings();
    /** The snapshot table and constants the pass's indirect commands are written under, for {@link #dispatchCommands}. */
    private CgBindingTable commandsTable;
    private int commandsConstants;
    private final int[] range = new int[4];
    /** Per indirect batch starting a run of the pass about to draw, the run's last batch: itself when none joins it. */
    private int[] runs = new int[64];
    /** Whether the pass executing samples its target's depth in place, read-only ({@link CgFrameCompiler#renderPasses}). */
    private boolean depthInPlace;
    /** Each batch's program, resolved before the device section: what {@link #batches} draws with. */
    private CgShader[] batchLinked = new CgShader[64];
    /** Each batch's device pipeline, resolved once per pass for each pipeline and topology it draws: what the loop binds. */
    private CgGraphicsPipeline[] batchDevice = new CgGraphicsPipeline[64];
    /** The device's render pass open now, and what opened it, which a copy mid-pass opens again loading. */
    @Nullable
    private CgRenderPass devicePass;
    private CgPassDesc devicePassDesc;
    /**
     * Each set index's last set this pass and what made it, as Unreal's pending state keeps its descriptors until a
     * binding is dirty: a batch of the same layout, snapshot and parameters' version binds it again unwritten. For one
     * pass: between passes a buffer may be made again under its name.
     */
    private final CgBindingLayout[] setLayout = new CgBindingLayout[8];
    private final int[] setSnapshot = new int[8], setVersion = new int[8];
    private final CgBindingSet[] setMade = new CgBindingSet[8];
    /** Each kept set's dynamic offsets: a snapshot's blocks are where they were for the batch that made it. */
    private final int[][] setOffsets = new int[8][CgDeviceSets.MAX_DYNAMIC];
    private final int[] setOffsetCount = new int[8];
    /** Each pipeline's device layout by id, made on its first batch; again where its program was linked again. */
    private CgPipelineLayout[] deviceLayouts = new CgPipelineLayout[64];
    private CgShader[] deviceLayoutsOf = new CgShader[64];
    @Nullable
    private CgRHI deviceLayoutsOn;

    CgRasterEncoder(CgExecutor execution, CgGraphDevice device, int depth) {
        this.execution = execution;
        this.device = device;
        CgShaderBufferRegistry registry = CgShaderBufferRegistry.get();
        instanceBuffers[CgInstanceKind.QUAD.ordinal()] = registry.getOrCreateInternal("CgGraphQuadInstances" + depth,
                CgInstanceKind.QUAD.format(), CgQuadBatch.SLOT, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.CURVE.ordinal()] = registry.getOrCreateInternal("CgGraphCurveInstances" + depth,
                CgInstanceKind.CURVE.format(), CgCurveBatch.SLOT, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.OBJECT.ordinal()] = registry.getOrCreateInternal("CgGraphObjectIds" + depth,
                CgInstanceKind.OBJECT.format(), CgBindingModel.OBJECT_IDS, CgBufferLifetime.FRAME);
        primitives = registry.getOrCreateInternal("CgGraphPrimitives" + depth, CgPrimitiveTable.FORMAT,
                CgBindingModel.OBJECTS, CgBufferLifetime.FRAME);
        identityBuffer = registry.getOrCreateInternal("CgGraphIdentityIds" + depth, CgInstanceKind.OBJECT.format(),
                CgBindingModel.OBJECT_IDS, CgBufferLifetime.RETAINED);
    }

    /** The frame's instances of each kind and its record table, uploaded once before its first pass. */
    void upload(CgFrame frame) {
        for (int k = 0; k < KINDS; k++) {
            if (frame.instanceFloats[k] > 0) instanceBuffers[k].uploadRaw(frame.instances[k], frame.instanceFloats[k]);
        }
        tabled = frame.primitiveFloats > 0;
        if (tabled) primitives.uploadRaw(frame.primitives, frame.primitiveFloats);
        if (frame.identityIds > identityIds) uploadIdentity(frame.identityIds);
        CgTrace.add(CgChannels.GL, PRIMITIVES, frame.primitives());
        CgTrace.add(CgChannels.GL, PRIMITIVE_IDS, frame.instanceFloats[OBJECT]);
    }

    /** The identity ids reaching {@code n} at least, uploaded where they reached less: rarely, as a draw's records grow. */
    private void uploadIdentity(int n) {
        int ids = Math.max(n, identityIds * 2);
        if (device.identity.length < ids * 2) {
            // (i, 0): record i, material slot 0, which an arena bound as one slot reads
            float[] grown = new float[ids * 2];
            for (int i = 0; i < ids; i++) grown[i * 2] = Float.intBitsToFloat(i);
            device.identity = grown;
        }
        identityBuffer.uploadRaw(device.identity, ids * 2);
        identityIds = ids;
    }

    /** Step {@code s}: limited to its damage unless the frame executes whole. */
    void raster(CgFrame frame, int s, CgRasterPass pass) {
        CgFrame.Raster packed = frame.rasters[s];
        int[] damage = frame.wholePasses ? null : pass.damage();
        if (damage != null && (damage[2] == 0 || damage[3] == 0)) {
            // Nothing changed in it: the target keeps what the last execution left.
            CgTrace.add(CgChannels.GL, "graph.passes.undamaged", 1);
            return;
        }
        if (damage != null) CgTrace.add(CgChannels.GL, "graph.damage-kpx", (long) damage[2] * damage[3] / 1000L);
        if (packed.indirects > 0 && execution.gpuCounts) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, WRITE_COMMANDS)) {
                writeCommands(frame.bindings, packed);   // a dispatch never sits inside a render pass
            }
        }
        draw(frame, s, pass, packed, damage);
    }

    /** Whether batch {@code b}'s instances name records by ids the frame builder wrote, each with its material's slot. */
    private static boolean slotsInIds(CgFrame.Raster packed, int b) {
        return packed.kind[b] == OBJECT && packed.objects[b] == null;
    }

    private static CgMesh mesh(CgFrame.Raster packed, int b) {
        return packed.kind[b] == OBJECT ? packed.mesh[b] : CgExecutor.UNIT_QUAD;
    }

    private static boolean join(CgMeshStore store, CgFrame.Raster packed, int k) {
        return store.join(mesh(packed, k), packed.instances[k], packed.submesh[k], packed.rangeFirst[k],
                packed.rangeCount[k], packed.first[k]);
    }

    /** Counts the first reason indirect batch {@code k} does not join {@code b}'s run, by the compiler's joinableIndirect terms. */
    private static void countBreak(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        int why = packed.counts[k] == null ? 0 : packed.copyBefore[k] != 0 ? 1
                : packed.pipeline[k] != packed.pipeline[b] ? 2 : table.share(packed.binding[k]) != table.share(packed.binding[b]) ? 3
                : packed.scissor[k] != packed.scissor[b] ? 4
                : packed.objects[k] != packed.objects[b] || !CgFrameCompiler.sameBuffers(packed, b, k) ? 5
                : packed.group[k] != packed.group[b] ? 6 : 7;
        CgTrace.add(CgChannels.GL, BREAKS[why], 1);
    }

    /**
     * Indirect batch {@code b}'s first instance: where its records start, or with {@link CgMeshStore#SHARED_RECORD} the
     * one record every instance reads, an INSTANCES draw of the frame's.
     */
    private static int firstInstance(CgFrame.Raster packed, int b) {
        boolean shared = packed.objects[b] == null && (packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal();
        return shared ? packed.first[b] | CgMeshStore.SHARED_RECORD : packed.first[b];
    }

    /** Whether batch {@code k} would join {@code b}'s run but for its bindings, shares compared: what bindless would join. */
    private static boolean breaksOnBinding(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        return packed.objects[b] == null && packed.counts[k] == null && packed.objects[k] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && table.share(packed.binding[k]) != table.share(packed.binding[b]) && packed.scissor[k] == packed.scissor[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    /**
     * The depth {@code target}'s passes draw with when it draws with another's ({@code depth}), {@code width} x
     * {@code height}; null where that one lends none or is another size, drawn without depth, logged once.
     */
    @Nullable
    private CgGpuTexture besideDepth(CgGraphTexture target, CgGraphStorage depth, int width, int height) {
        String refused = depth.depth() == null ? depth + " has no depth to draw beside"
                : !depth.lendsAttachments() ? depth + " lends no attachment to a target of ours" : null;
        if (refused != null) {
            if (loggedOnce.add("beside " + target.name())) LOGGER.warn("{} draws without depth: {}", target, refused);
            return null;
        }
        int w = depth.depth().desc().width(), h = depth.depth().desc().height();
        if (w != width || h != height) {
            long sizes = (long) width << 48 | (long) height << 32 | (long) w << 16 | h;
            if (sizes != device.besideMismatch) {
                device.besideMismatch = sizes;
                LOGGER.warn("{} is {}x{}, beside a depth of {}x{}: drawn without it", target, width, height, w, h);
            }
            return null;
        }
        return depth.depth();
    }

    /** Throws where {@code pass} cannot be drawn: no storage, or draws counted on the CPU. */
    private void check(CgRasterPass pass, CgFrame.Raster packed) {
        CgGraphStorage storage = device.storage(CgExecutor.targetOf(pass));
        if (storage.color(0) == null && storage.depth() == null) throw new IllegalStateException(pass + " draws into " + storage + ", which has nothing attached");
        // A device runs compute, so its draws are counted on the GPU (gpuCounts)
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] != null && !execution.gpuCounts) throw new IllegalStateException(pass + " counts draws on the CPU beside a device");
        }
    }

    /** The device texture at {@code storage}'s colour slot {@code slot}, or its depth where {@code slot} is -1. */
    private static CgGpuTexture deviceTexture(CgGraphStorage storage, int slot) {
        CgGpuTexture texture = slot < 0 ? storage.depth() : storage.color(slot);
        if (texture == null) throw new IllegalStateException(storage + (slot < 0 ? "'s depth" : "'s colour " + slot) + " has no device texture");
        return texture;
    }

    /**
     * The device texture of {@code pass}'s second attachment, drawn {@code width} x {@code height} beside
     * {@code storage}; null for none. One the device could not attach (another size; beside a target that lends none)
     * is dropped when optional, logged once, and throws when not.
     */
    @Nullable
    private CgGpuTexture second(CgRasterPass pass, CgGraphStorage storage, int width, int height) {
        CgGraphTexture own = pass.attachment();
        CgGraphTexture attachment = own != null ? own : execution.runAttachment;
        if (attachment == null) return null;
        CgGraphStorage extra = device.storage(attachment);
        String refused = !storage.lendsAttachments() ? storage + " lends no attachment to draw beside"
                : storage.color(0) == null ? storage + " has no colour to draw beside"
                : extra.width() != width || extra.height() != height
                ? attachment + " is " + extra.width() + "x" + extra.height() + ", beside a target of " + width + "x" + height
                : null;
        if (refused == null) return deviceTexture(extra, 0);
        if (!pass.attachmentOptional()) throw new IllegalStateException(pass + ": " + refused);
        if (loggedOnce.add("second " + pass.name())) LOGGER.warn("{} draws without its second attachment: {}", pass, refused);
        return null;
    }

    /**
     * {@code pass} drawn: what runs outside a render pass first; then, in a device section, a render pass over its
     * target's attachments, each batch's pipeline, sets and draw through the state cache.
     */
    private void draw(CgFrame frame, int s, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        CgTrace.add(CgChannels.GL, DEVICE_PASSES, 1);
        long prepared = CgTrace.stamp(CgChannels.GL);
        check(pass, packed);
        reversed = CgDepthConvention.reversed();
        CgGraphTexture target = CgExecutor.targetOf(pass);
        CgGraphStorage storage = device.storage(target);
        int level = pass.level;
        int w = storage.levelWidth(level), h = storage.levelHeight(level);
        if (packed.count > 0) {
            // Before the render pass, as a copy runs outside one: the copies are what the view set reads
            view.clear();
            depthInPlace = frame.readOnlyDepth[s];
            if (pass.depthFrom() != null) {
                view.put(CgBindingModel.SCENE_DEPTH, execution.depthFrom(pass, pass.depthFrom(), pass.depthFromCopy(),
                        frame.depthFromInPlace[s]));
            }
            if (pass.colorFrom() != null) copyColorFrom(pass);
            if ((packed.copyBefore[0] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                copyTarget(pass, packed.copyBefore[0], packed.copyRect, 0);
            }
            passBase.clear();
            for (int k = 0; k < KINDS; k++) if ((packed.kinds & (1 << k)) != 0) state(instanceBuffers[k]);
            if (tabled && (packed.kinds & (1 << OBJECT)) != 0) state(primitives);
            packed.palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), CgPassConstants.height(pass.constants));
            if ((packed.kinds & UNIT_KINDS) != 0 || packed.tables) {
                state(packed.clips.prepare());
                state(packed.shapes.prepare());
                state(packed.palette.prepare());
            }
            batchRecords = null;
            batchWords = null;
            Arrays.fill(batchBuffers, null);
            passBatch.clear();
            copy(passBase, passBatch, false);
            passVersion++;
            execution.deviceSets.prepare(frame.bindings);
            linkBatches(packed);
        }
        CgLoad load = pass.load;
        boolean clearColor = load.clearsColor();
        CgPassDescs descs = device.passDescs;
        descs.begin(w, h);
        deviceFormats.clear();
        // A second attachment is colour 1, after the target's colour 0.
        CgGpuTexture second = second(pass, storage, w, h);
        int colors = storage.color(0) != null ? 1 : 0;
        for (int i = 0; i < colors + (second != null ? 1 : 0); i++) {
            CgGpuTexture texture = i == colors ? second : deviceTexture(storage, i);
            // The pass's level and layer of the target, from where an imported one is attached; a second attachment's 0
            descs.color(texture, i == colors ? 0 : storage.colorLevel() + level,
                    i == colors ? 0 : storage.colorLayer() + pass.layer,
                    clearColor && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD,
                    load.r(), load.g(), load.b(), load.a());
            deviceFormats.add(texture.desc().format());
        }
        CgFormat depthFormat = null;
        CgGraphTexture depthOf = pass.depthBeside();
        CgGraphStorage depthStorage = depthOf != null ? device.storage(depthOf) : storage;
        // A depth only where the pass names one: its own beside its colour, or its target's
        CgGpuTexture depthTexture = depthOf != null ? besideDepth(target, depthStorage, w, h)
                : pass.depthNamed() ? storage.depth() : null;
        boolean clearDepth = depthTexture != null && load.clearsDepth();
        boolean clearStencil = depthTexture != null && load.clearsStencil();
        // A load's depth is the engine's, far at 0: mirrored for a target drawn in a host's standard depth
        float clearDepthValue = CgDepthConvention.stored(load.depth(), reversed);
        if (depthTexture != null) {
            int depthLayer = depthStorage == storage ? storage.depthLayer() + pass.layer : depthStorage.depthLayer();
            // Under damage loaded and cleared over the damage alone, as colour is
            descs.depth(depthTexture, level, depthLayer,
                    clearDepth && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearDepthValue,
                    clearStencil && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, load.stencil(),
                    depthInPlace);
            depthFormat = depthTexture.desc().format();
        }
        CgPassDesc desc = descs.end();
        int samples = desc.samples();
        CgRenderState passState = pass.state != null ? pass.state : CgRenderState.DEFAULT;
        CgTrace.zoneDone(CgChannels.GL, DEVICE_PREPARE, prepared);
        long passed = CgTrace.stamp(CgChannels.GL);
        long t = passed;
        CgDeviceSection.begin();
        CgTrace.zoneDone(CgChannels.GL, DEVICE_SECTION, t);
        CgRenderPass rp = null;
        try {
            CgRHI rhi = CgRHI.get();
            devicePassDesc = desc;
            t = CgTrace.stamp(CgChannels.GL);
            rp = open(rhi, devicePassDesc);
            CgTrace.zoneDone(CgChannels.GL, DEVICE_OPEN, t);
            if (clearColor && damage != null) {
                for (int i = 0; i < desc.colors().size(); i++) {
                    rp.clearColor(i, load.r(), load.g(), load.b(), load.a(), damage[0], damage[1], damage[2], damage[3]);
                }
            }
            if ((clearDepth || clearStencil) && damage != null) {
                rp.clearDepthStencil(clearDepth, clearDepthValue, clearStencil, load.stencil(),
                        damage[0], damage[1], damage[2], damage[3]);
            }
            if (packed.count > 0) {
                t = CgTrace.stamp(CgChannels.GL);
                batches(frame, pass, packed, damage, rhi, passState, depthFormat, samples, w, h);
                CgTrace.zoneDone(CgChannels.GL, DEVICE_BATCHES, t);
            }
            rp = null;
            CgRenderPass ending = devicePass;
            devicePass = null;
            t = CgTrace.stamp(CgChannels.GL);
            ending.end();
            CgTrace.zoneDone(CgChannels.GL, DEVICE_END, t);
        } finally {
            // Ended on a throw too, or the section's end throws over it for the open pass
            if (devicePass != null) {
                devicePass.end();
                devicePass = null;
            } else if (rp != null) {
                rp.end();
            }
            depthInPlace = false;
            long ended = CgTrace.stamp(CgChannels.GL);
            CgDeviceSection.end();
            CgTrace.zoneDone(CgChannels.GL, DEVICE_SECTION, ended);
            CgTrace.zoneDone(CgChannels.GL, DEVICE_PASS, passed);
            if (pass.targetCopy() != null) pass.targetCopy().release(device.pool);
        }
    }

    /**
     * Every batch's program, resolved before the render pass opens: a program whose compile has landed is built at its
     * first resolve, and the draw loop then only reads what is resolved.
     */
    private void linkBatches(CgFrame.Raster packed) {
        if (batchLinked.length < packed.count) batchLinked = new CgShader[Math.max(packed.count, batchLinked.length * 2)];
        int id = -1;
        CgShader linked = null;
        for (int b = 0; b < packed.count; b++) {
            if (packed.pipeline[b] != id) {
                id = packed.pipeline[b];
                linked = CgPipeline.byId(id).program();
            }
            batchLinked[b] = linked;
        }
    }

    /**
     * Every batch's device pipeline, once for each run of batches of one pipeline and topology: the pass's formats and
     * state are the same for all of them, so a lookup per batch would answer the same pipeline again.
     */
    private void resolveDevices(CgFrame.Raster packed, CgRenderState passState, @Nullable CgFormat depthFormat, int samples) {
        if (batchDevice.length < packed.count) batchDevice = new CgGraphicsPipeline[Math.max(packed.count, batchDevice.length * 2)];
        int id = -1;
        CgGraphicsPipelineDesc.Topology topology = null;
        CgGraphicsPipeline device = null;
        for (int b = 0; b < packed.count; b++) {
            if (batchLinked[b] == null) {
                batchDevice[b] = null;
                continue;
            }
            CgGraphicsPipelineDesc.Topology t = CgMeshStore.topology(mesh(packed, b));
            if (packed.pipeline[b] != id || t != topology) {
                id = packed.pipeline[b];
                topology = t;
                device = CgPipeline.byId(id).device(passState, t, reversed, deviceFormats, depthFormat, samples);
            }
            batchDevice[b] = device;
        }
    }

    /** Begins {@code desc} as {@link #devicePass}, its viewport the target's, with the state cache emptied. */
    private CgRenderPass open(CgRHI rhi, CgPassDesc desc) {
        CgRenderPass rp = rhi.encoder().beginPass(desc);
        devicePass = rp;
        rp.setViewport(0, 0, desc.width(), desc.height(), 0, 1);
        drawCache.reset();
        return rp;
    }

    /**
     * Batch {@code b}'s target copy, between render passes as Unreal RDG splits one around a copy: the pass ended, the
     * copy made, the pass begun again loading what it drew. Its binds are what the next sets read.
     */
    private CgRenderPass copyMidPass(CgRHI rhi, CgRasterPass pass, CgFrame.Raster packed, int b) {
        CgRenderPass ending = devicePass;
        devicePass = null;
        ending.end();
        CgDeviceSection.end();
        copyTarget(pass, packed.copyBefore[b], packed.copyRect, b * 4);
        CgDeviceSection.begin();
        CgPassDesc d = devicePassDesc;
        List<CgPassDesc.Color> colors = new ArrayList<>(d.colors().size());
        for (CgPassDesc.Color c : d.colors()) {
            colors.add(new CgPassDesc.Color(c.view(), CgPassDesc.LoadOp.LOAD, c.r(), c.g(), c.b(), c.a(), c.store()));
        }
        CgPassDesc.Depth depth = d.depth() == null ? null : new CgPassDesc.Depth(d.depth().view(), CgPassDesc.LoadOp.LOAD,
                d.depth().clearDepth(), CgPassDesc.LoadOp.LOAD, d.depth().clearStencil(), d.depth().store(), d.depth().readOnly());
        CgTrace.add(CgChannels.GL, MID_PASS_SPLITS, 1);
        return open(rhi, new CgPassDesc(d.label(), colors, depth, d.width(), d.height()));
    }

    private void batches(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage,
                         CgRHI rhi, CgRenderState passState, @Nullable CgFormat depthFormat,
                         int samples, int w, int h) {
        CgRenderPass rp = devicePass;
        CgMeshStore store = CgMeshStore.get();
        CgBindingTable table = frame.bindings;
        CgDeviceSets deviceSets = execution.deviceSets;
        int boundPipeline = -1, boundScissor = CgRasterPass.INHERIT, slot = 0;
        CgPipeline pipeline = null;
        CgShader linked = null;
        CgPipelineLayout layout = null;
        Arrays.fill(setLayout, null);
        resolveDevices(packed, passState, depthFormat, samples);
        if (damage == null) rp.setScissor(0, 0, w, h);
        else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
        for (int b = 0; b < packed.count; b++) {
            if (b > 0 && (packed.copyBefore[b] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                rp = copyMidPass(rhi, pass, packed, b);
                Arrays.fill(setLayout, null);
                boundScissor = CgRasterPass.INHERIT;
                if (damage == null) rp.setScissor(0, 0, w, h);
                else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
            }
            int command = packed.counts[b] != null ? slot : -1;
            int end, commandCount = 0;
            if (command >= 0) {
                end = runs[b];
                for (int k = b; k <= end; k++) commandCount += commandsOf(packed, k);
                slot += commandCount;
            } else {
                end = packed.joinEnd[b];
                if (end > b) for (int k = b; k <= end; k++) join(store, packed, k);   // the compiled run, into the store
                if (execution.multiDraw && end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)
                        && breaksOnBinding(table, packed, b, end + 1)) {
                    CgTrace.add(CgChannels.GL, BINDING_BREAKS, 1);
                }
            }
            if (packed.scissor[b] != boundScissor) {
                boundScissor = packed.scissor[b];
                if (boundScissor == CgRasterPass.NO_SCISSOR) {
                    if (damage == null) rp.setScissor(0, 0, w, h);
                    else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
                } else if (boundScissor >= 0) {
                    scissorRect(pass, packed.palette, boundScissor);
                    if (damage != null) cutToDamage(damage);
                    rp.setScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3]);
                }
            }
            long t = CgTrace.stamp(CgChannels.GL_DETAIL);
            if (packed.pipeline[b] != boundPipeline) {
                boundPipeline = packed.pipeline[b];
                pipeline = CgPipeline.byId(boundPipeline);
                linked = batchLinked[b];
                layout = linked == null ? null : deviceLayout(rhi, boundPipeline, linked);
            }
            CgMesh mesh = mesh(packed, b);
            CgGraphicsPipeline device = batchDevice[b];
            if (device == null) {
                if (loggedOnce.add("skipped " + pipeline)) {
                    LOGGER.warn("device raster: {} skipped in {}: {}", pipeline, pass.name(),
                            linked == null ? "its program did not link" : "no device pipeline");
                }
                if (end > b && command < 0) store.dropJoined();
                CgTrace.add(CgChannels.GL, "graph.batches.skipped", end - b + 1);
                b = end;
                continue;
            }
            drawCache.pipeline(rp, device);
            CgStencilState stencil = pipeline.state().getStencil();
            if (stencil != null && stencil.enabled()) drawCache.stencilReference(rp, stencil.ref());
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_PIPELINE, t);
            t = CgTrace.stamp(CgChannels.GL_DETAIL);
            int binding = packed.binding[b];
            // The words its vertex stage pulls from, which a joined run's meshes share
            batchParameters(packed, b, store.vertexWords(mesh));
            // A batch whose ids carry its material's slot binds the arena whole, made from its share; any other its slot
            boolean whole = slotsInIds(packed, b);
            int shared = whole ? table.share(binding) : binding;
            for (int set = 0; set < layout.sets().size(); set++) {
                if (set == CgRHI.BINDLESS_SET) {
                    drawCache.set(rp, set, rhi.bindlessSet());
                    continue;
                }
                CgBindingLayout bl = layout.sets().get(set);
                if (bl.slots().isEmpty()) continue;
                int snapshot = set == CgBindingModel.VIEW ? packed.constants : shared;
                int memo = set == CgBindingModel.VIEW ? snapshot : snapshot * 2 + (whole ? 1 : 0);
                int version = set == CgBindingModel.VIEW ? view.version() : set == CgBindingModel.PASS ? passVersion : 0;
                if (set < setLayout.length && setLayout[set] == bl && setSnapshot[set] == memo
                        && setVersion[set] == version) {
                    CgTrace.add(CgChannels.GL, SETS_KEPT, 1);
                    drawCache.set(rp, set, setMade[set], setOffsets[set], setOffsetCount[set]);
                    continue;
                }
                CgBindings parameters = set == CgBindingModel.VIEW ? view.bindings(rhi) : set == CgBindingModel.PASS ? passBatch : null;
                CgBindingSet made = deviceSets.set(rhi, table, snapshot, layout, set, parameters, whole);
                if (set < setLayout.length) {
                    setLayout[set] = bl;
                    setSnapshot[set] = memo;
                    setVersion[set] = version;
                    setMade[set] = made;
                    System.arraycopy(deviceSets.offsets(), 0, setOffsets[set], 0, deviceSets.offsetCount());
                    setOffsetCount[set] = deviceSets.offsetCount();
                }
                drawCache.set(rp, set, made, deviceSets.offsets(), deviceSets.offsetCount());
            }
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_BINDINGS, t);
            t = CgTrace.stamp(CgChannels.GL_DETAIL);
            if (end > b || command >= 0 && packed.eachFrom[b] >= 0 && eachJoins(packed, b)) {
                if (command >= 0) {
                    int name = commands.buffer();
                    store.drawIndirectJoinedOn(rp, drawCache, mesh, CgDeviceNames.buffer(name),
                            commands.offset(command), commandCount, commands.stride());
                } else {
                    store.drawJoinedOn(rp, drawCache);
                }
                CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
                b = end;
                continue;
            }
            if (command >= 0) {
                int name = commands.buffer();
                int each = packed.eachFrom[b] >= 0 ? commandCount : 1;
                for (int k = 0; k < each; k++) {
                    store.drawIndirectOn(rp, drawCache, mesh, packed.submesh[b], CgDeviceNames.buffer(name),
                            commands.offset(command + k));
                }
                CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
                continue;
            }
            store.drawOn(rp, drawCache, mesh, packed.instances[b], packed.first[b], packed.submesh[b], packed.rangeFirst[b],
                    packed.rangeCount[b]);
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
        }
    }

    /** {@code buffer} landed and stated at its slot among the pass's parameters. */
    private void state(CgShaderBuffer buffer) {
        buffer.flush();
        buffer.describe(passBase, buffer.slot().binding());
    }

    /**
     * Batch {@code b}'s pass parameters: the pass's, with its own records (and identity ids), its buffers by slot and its
     * mesh's vertex words over them. Made again only when one of those changes, which moves {@link #passVersion}.
     */
    private void batchParameters(CgFrame.Raster packed, int b, @Nullable CgGpuBuffer words) {
        int first = b * CgDrawChunk.BUFFERS;
        boolean same = packed.objects[b] == batchRecords && words == batchWords;
        for (int s = 0; s < CgDrawChunk.BUFFERS && same; s++) {
            same = packed.bufferAt[first + s] == batchBuffers[s * 2] && packed.buffers[first + s] == batchBuffers[s * 2 + 1];
        }
        if (same) return;
        batchRecords = packed.objects[b];
        batchWords = words;
        passBatch.clear();
        if (packed.objects[b] != null) {
            whole(passBatch, CgBindingModel.OBJECTS.binding(), records(packed.objects[b]));
            identityBuffer.describe(passBatch, CgBindingModel.OBJECT_IDS.binding());
        }
        for (int s = 0; s < CgDrawChunk.BUFFERS; s++) {
            CgBindingModel.Slot at = packed.bufferAt[first + s];
            batchBuffers[s * 2] = at;
            batchBuffers[s * 2 + 1] = packed.buffers[first + s];
            if (at == null) continue;
            if (at.set() != CgBindingModel.PASS) throw new IllegalStateException("a draw's own buffer at " + at + ": draws name pass slots alone");
            if (passBatch.indexOf(at.binding()) < 0) whole(passBatch, at.binding(), records(packed.buffers[first + s]));
        }
        if (words != null) whole(passBatch, CgBindingModel.VERTICES.binding(), words);
        copy(passBase, passBatch, true);
        passVersion++;
    }

    /** {@code from}'s buffers into {@code to}; where {@code keepTaken}, not over a binding {@code to} already holds. */
    private static void copy(CgBindings from, CgBindings to, boolean keepTaken) {
        int n = from.count();
        for (int i = 0; i < n; i++) {
            if (keepTaken && to.indexOf(from.binding(i)) >= 0) continue;
            to.buffer(from.binding(i), from.type(i), from.buffer(i), from.offset(i), from.size(i));
        }
    }

    /** {@code buffer} whole at storage binding {@code binding}. */
    private static void whole(CgBindings out, int binding, CgGpuBuffer buffer) {
        out.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, buffer, 0, buffer.size());
    }

    /** The device buffer a batch's own records are in. */
    private CgGpuBuffer records(CgBufferHandle records) {
        int id = records instanceof CgGraphBuffer graph ? device.bufferStorage(graph, false) : records.bufferId();
        return CgDeviceNames.buffer(id);
    }

    private CgPipelineLayout deviceLayout(CgRHI rhi, int id, CgShader linked) {
        if (deviceLayoutsOn != rhi) {
            Arrays.fill(deviceLayouts, null);
            Arrays.fill(deviceLayoutsOf, null);
            deviceLayoutsOn = rhi;
        }
        if (id >= deviceLayouts.length) {
            int n = Integer.highestOneBit(id) << 1;
            deviceLayouts = Arrays.copyOf(deviceLayouts, n);
            deviceLayoutsOf = Arrays.copyOf(deviceLayoutsOf, n);
        }
        if (deviceLayoutsOf[id] != linked) {
            deviceLayouts[id] = CgDeviceStates.layout(rhi, linked.explicit());
            deviceLayoutsOf[id] = linked;
        }
        return deviceLayouts[id];
    }

    /** Copies another target's colour whole, for a pass reading it ({@link CgRasterPass#readsColor(CgGraphTexture)}). */
    private void copyColorFrom(CgRasterPass pass) {
        CgTargetCopy copy = pass.colorFromCopy();
        copy.copyColor(device.storage(pass.colorFrom()), device.pool);
        view.put(CgBindingModel.SCENE_COLOR, copy.color);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /**
     * The pass's indirect commands, one per indirect batch in batch order, written before the pass begins; each
     * count's own barrier came with the pass's access list. Where draws join, the compiled runs ({@link #runs}) are
     * written in a multi-draw's form.
     */
    private void writeCommands(CgBindingTable table, CgFrame.Raster packed) {
        int args = commands.reserve(packed.indirects, device.forgetBuffer);
        execution.transitions.buffer(args, CgAccess.COMPUTE_WRITE);
        commandsTable = table;
        commandsConstants = packed.constants;
        if (runs.length < packed.count) runs = new int[Math.max(packed.count, runs.length * 2)];
        int slot = 0;
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] == null) continue;
            int end = packed.joinEnd[b];
            if (execution.multiDraw && end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)) countBreak(table, packed, b, end + 1);
            runs[b] = end;
            for (int k = b; k <= end; k++) slot += writeCommand(packed, k, slot);
            b = end;
        }
        commands.flush();
        commandsTable = null;
        execution.transitions.buffer(args, CgAccess.INDIRECT);
        CgTrace.add(CgChannels.GL, COMMAND_COUNT, packed.indirects);
    }

    /** The commands {@link #writeCommands} queued, written by their kernel through the device's compute pass. */
    private void dispatchCommands() {
        if (!commands.stage()) return;
        CgRHI rhi = CgRHI.get();
        CgKernelProgram program = commands.program();
        CgComputePipeline pipeline = program.devicePipeline(rhi);   // made outside the section
        CgBindings k = commandBindings.clear();
        CgComputeEncoder.storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("COUNT"), commands.stagedCount, 0, 0);
        CgComputeEncoder.storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("ARGS"), commands.argsBuffer(),
                commands.stagedArgs, commands.stagedArgsBytes);
        k.buffer(CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("PARAMS"), CgBindingLayout.Type.STORAGE_BUFFER,
                commands.params().gpuBuffer(), commands.stagedParams, commands.stagedParamsBytes);
        CgDeviceCompute compute = device.compute;
        CgDeviceSets deviceSets = execution.deviceSets;
        compute.begin("graph.commands");
        try {
            CgPipelineLayout layout = program.deviceLayout();
            compute.pass(rhi);
            compute.pipeline(pipeline, layout);
            for (int set = 0; set < layout.sets().size(); set++) {
                if (set == CgRHI.BINDLESS_SET || layout.sets().get(set).slots().isEmpty()) continue;
                CgBindingSet s = deviceSets.set(rhi, commandsTable, commandsConstants, layout, set,
                        set == CgBindingModel.MATERIAL ? k : null);
                compute.set(set, s, deviceSets.offsets(), deviceSets.offsetCount());
            }
            program.dispatchOn(compute.pass(rhi), commands.stagedElements, 1, 1);
        } finally {
            compute.close();
        }
    }

    /**
     * Batch {@code b}'s commands from {@code slot}, answering how many: one, or one per record of a batch of
     * {@code indirectEach}, each with its first instance.
     */
    private int writeCommand(CgFrame.Raster packed, int b, int slot) {
        CgBufferHandle count = packed.counts[b];
        int countId;
        if (count instanceof CgGraphBuffer graph) {
            countId = device.bufferStorage(graph, false);
        } else {
            countId = count.bufferId();
            execution.transitions.buffer(countId, CgAccess.COMPUTE_READ);
        }
        CgMeshStore store = CgMeshStore.get();
        boolean drawn = store.range(packed.mesh[b], packed.submesh[b], packed.rangeFirst[b], packed.rangeCount[b], range);
        if (!drawn) Arrays.fill(range, 0);   // a mesh with nothing to draw: a command of nothing
        CgIndirect mode = INDIRECT_MODES[packed.countModes[b] & 3];
        int factor = packed.countModes[b] >>> 2;
        if (packed.eachFrom[b] >= 0) {
            int n = packed.instances[b];
            for (int k = 0; k < n; k++) {
                long at = packed.eachOffsets[packed.eachFrom[b] + k];
                commands.write(slot + k, countId, at, mode, factor, range, 1, -1, packed.first[b] + k);
            }
            return n;
        }
        commands.write(slot, countId, packed.countOffsets[b], mode, factor, range, packed.instances[b],
                packed.objects[b] != null ? packed.instances[b] : -1, firstInstance(packed, b));
        return 1;
    }

    /** Commands batch {@code b} draws: one, or one per record of a batch of {@code indirectEach}. */
    private static int commandsOf(CgFrame.Raster packed, int b) {
        return packed.eachFrom[b] >= 0 ? packed.instances[b] : 1;
    }

    /** Whether batch {@code b} of {@code indirectEach} draws its commands as one multi-draw: where draws join its mesh. */
    private boolean eachJoins(CgFrame.Raster packed, int b) {
        CgMesh mesh = mesh(packed, b);
        return execution.multiDraw && CgMeshStore.get().joins(mesh, mesh);
    }

    /** {@link #scissorRect} cut by a pass's damage. */
    private void cutToDamage(int[] damage) {
        int x0 = Math.max(scissorRect[0], damage[0]), y0 = Math.max(scissorRect[1], damage[1]);
        int x1 = Math.min(scissorRect[0] + scissorRect[2], damage[0] + damage[2]);
        int y1 = Math.min(scissorRect[1] + scissorRect[3], damage[1] + damage[3]);
        scissorRect[0] = x0;
        scissorRect[1] = y0;
        scissorRect[2] = Math.max(0, x1 - x0);
        scissorRect[3] = Math.max(0, y1 - y0);
    }

    /** Scissor {@code index} and every one it is inside, cut together, into {@link #scissorRect}. */
    private void scissorRect(CgRasterPass pass, CgPalette palette, int index) {
        int[] rects = pass.scissorRects();
        int x0 = Integer.MIN_VALUE, y0 = Integer.MIN_VALUE, x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE;
        for (int s = index; s >= 0; s = pass.scissorParent(s)) {
            int node = pass.scissorNode(s);
            int[] rect = scissorPart;
            if (node < 0) System.arraycopy(rects, s * 4, rect, 0, 4);
            else palette.scissorOf(node, pass.scissorBoxes(), s * 4, rect);
            x0 = Math.max(x0, rect[0]);
            y0 = Math.max(y0, rect[1]);
            x1 = Math.min(x1, rect[0] + rect[2]);
            y1 = Math.min(y1, rect[1] + rect[3]);
        }
        scissorRect[0] = x0;
        scissorRect[1] = y0;
        scissorRect[2] = Math.max(0, x1 - x0);
        scissorRect[3] = Math.max(0, y1 - y0);
    }

    /**
     * Copies what {@code bits} name from the pass's target for the draws sampling it, colour in the rect at {@code at}
     * of {@code rects}, and binds the copies.
     */
    private void copyTarget(CgRasterPass pass, int bits, int[] rects, int at) {
        CgTargetCopy copy = pass.targetCopy();
        CgGraphStorage colour = device.storage(CgExecutor.targetOf(pass)), depth = device.storage(pass.depthTexture());
        if (depthInPlace && (bits & CgTargetCopy.DEPTH) != 0) {
            // Read-only in this pass: sampled where it is, no copy.
            view.put(CgBindingModel.SCENE_DEPTH, depth.sampledDepth());
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        if ((bits & (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) == (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) {
            // The target's depth as the pass found it: what an earlier pass may have copied already.
            copy.borrowDepth(execution.sharedDepth(depth, pass));
            view.put(CgBindingModel.SCENE_DEPTH, copy.depth);
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        bits &= ~CgTargetCopy.CLEAN;
        long pixels = copy.copy(colour, depth, bits, rects, at, device.pool);
        if ((bits & CgTargetCopy.COLOR) != 0) view.put(CgBindingModel.SCENE_COLOR, copy.color);
        if ((bits & CgTargetCopy.DEPTH) != 0) view.put(CgBindingModel.SCENE_DEPTH, copy.depth);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        CgTrace.add(CgChannels.GL, TARGET_COPY_PIXELS, pixels);
    }

    void delete() {
        commands.delete(device.forgetBuffer);
    }
}
