package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Turns a world draw into the commands a pass records for it, as Unreal's {@code BuildMeshDrawCommands} and
 * {@code FinalizeCommand} do for a mesh pass processor: its pipeline checked built, its material's snapshot, its mesh
 * or set of instances, its range and count, and its object record, written once a firing into the recording's primitive
 * table and named by slot after. Every pass's processor draws through it. Render thread.
 *
 * <pre>{@code
 * commands.modelOf(i, view);
 * commands.model.normal(commands.normal);
 * commands.passDraw(chunks, material.pipeline(CgInstanceKind.OBJECT), material, recording, i);
 * }</pre>
 *
 * <p>{@link #model} and {@link #normal} are the draw's for {@link #writeInstance}: set them first, through
 * {@link #modelOf}.</p>
 */
final class CgMeshDrawCommands {

    final CgWorldDraws draws;

    /** The frame's bone palettes, recorded by its first world stage; {@link #palettesFrame} the ring frame it was. */
    CgGraphBuffer palettes;
    long palettesFrame = -1;
    /** A draw's records far to near this stage: each key its distance's bits over its index. */
    long[] recordOrder = new long[64];
    final Matrix4f recordModel = new Matrix4f();
    /** The view {@link CgWorldRenderer#prepare} last took: what records are placed and ordered under. */
    CgHostView recordView;
    // Per recording, reused: nothing here allocates per frame once warm.
    final Matrix4f model = new Matrix4f();
    final Matrix4f normal = new Matrix4f();
    final Vector3f eye = new Vector3f();
    final Vector3f forward = new Vector3f();
    final IdentityHashMap<CgMaterial, Integer> bindings = new IdentityHashMap<>();
    final IdentityHashMap<CgRenderState, CgRenderState> depthOnly = new IdentityHashMap<>();
    boolean skipUnready = CgWorldRenderer.SKIP_UNREADY.get();
    long unreadyDraws;
    /** Pipelines a held draw asked for, polled once a frame until built: Unreal's precache requests. */
    final List<CgPipeline> held = new ArrayList<>();
    final Set<CgPipeline> heldSet = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Per draw: the slot its record has in the firing's table, and which firing and table wrote it there. The table is
     * the recording's, reset between firings, so a slot is good only in the firing that wrote it.
     */
    int[] primitiveSlots = new int[64], primitiveFirings = new int[64];
    CgPrimitiveTable[] primitiveTables = new CgPrimitiveTable[64];
    int firing;
    static final int UNREADY_DRAWS = CgTrace.name("world.unready-draws");

    CgMeshDrawCommands(CgWorldDraws draws) {
        this.draws = draws;
    }

    /** Draw {@code i}'s model matrix, camera-relative: its position minus the view's, in doubles, then its transform. */
    void modelOf(int i, CgHostView view) {
        model.set(draws.transforms, i * 16);
        model.m30(model.m30() + (float) (draws.positions[i * 3] - view.x()))
                .m31(model.m31() + (float) (draws.positions[i * 3 + 1] - view.y()))
                .m32(model.m32() + (float) (draws.positions[i * 3 + 2] - view.z()));
    }

    /**
     * Whether a draw through {@code pipeline} is skipped, its program not built while {@link #skipUnready} is on: counted,
     * and its compile started. Every pass a material draws in asks it: forward, emission, distortion, half size, volumes.
     */
    boolean unready(CgPipeline pipeline, int i) {
        if (!skipUnready) return false;
        // The form the draw runs: a skinned mesh draws every pass skinned (CgChunkBuilder.draw).
        if (skinnedDraw(i)) pipeline = pipeline.as(CgPipelineForm.SKINNED);
        if (pipeline.isBuilt()) return false;
        // Asked for here, started in settle(): a pipeline held by a thousand draws spends the warm budget once a frame.
        if (heldSet.add(pipeline)) held.add(pipeline);
        unreadyDraws++;
        CgTrace.add(CgChannels.GL, UNREADY_DRAWS, 1);
        return true;
    }

    /** 1 where draw {@code i}'s mesh is {@code SKINNED_WIDE}, whose vertex the skinned factory reads at 16-bit indices. */
    private float wideBones(int i) {
        CgMesh m = draws.lods[i] != null ? draws.lods[i].finest() : draws.meshes[i];
        return m != null && m.format().equals(CgVertexFormat.SKINNED_WIDE) ? 1f : 0f;
    }

    /** Whether draw {@code i}'s mesh is skinned, which picks every pipeline's skinned form. */
    boolean skinnedDraw(int i) {
        CgMesh m = draws.lods[i] != null ? draws.lods[i].finest() : draws.meshes[i];
        return m != null && m.format().isSkinned();
    }

    /** Starts or polls each held pipeline once, dropping those built. */
    void precacheHeld() {
        for (int i = held.size() - 1; i >= 0; i--) {
            CgPipeline p = held.get(i);
            if (!CgWorldRenderer.built(p)) continue;
            heldSet.remove(p);
            held.set(i, held.get(held.size() - 1));
            held.remove(held.size() - 1);
        }
    }

    /** Draw {@code i} through {@code pipeline} into a world pass, at its key and within its screen rect. */
    void passDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterial link, CgRecording recording, int i) {
        if (unready(pipeline, i)) return;
        if (draws.sets[i] != null) {
            drawSet(chunks, pipeline, bindingOf(link, recording), i);
            return;
        }
        chunks.draw(pipeline, bindingOf(link, recording), draws.meshes[i]).sortKey(draws.keys[i]);
        group(chunks, i);
        if (!Float.isNaN(draws.screens[i * 4])) chunks.bounds(draws.screens[i * 4], draws.screens[i * 4 + 1], draws.screens[i * 4 + 2], draws.screens[i * 4 + 3]);
        writeInstance(chunks, i);
    }

    /** Draw {@code i} through {@code pipeline} into the emission pass, sorted by its key. */
    void emissionDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterial link, CgRecording recording, int i) {
        if (unready(pipeline, i)) return;
        if (draws.sets[i] != null) {
            drawSet(chunks, pipeline, bindingOf(link, recording), i);
            return;
        }
        chunks.draw(pipeline, bindingOf(link, recording), draws.meshes[i]).sortKey(draws.keys[i]);
        group(chunks, i);
        writeInstance(chunks, i);
    }

    /** Set {@code i}'s levels, each an indirect draw of the records its cull kept at that level this stage. */
    void drawSet(CgChunkBuilder chunks, CgPipeline pipeline, int binding, int i) {
        int capacity = draws.setCounts[i].capacity(), levels = draws.lods[i] != null ? draws.lods[i].levelCount() : 1;
        for (int l = 0; l < levels; l++) {
            chunks.draw(pipeline, binding, draws.lods[i] != null ? draws.lods[i].level(l) : draws.meshes[i]).sortKey(draws.keys[i]);
            group(chunks, i);
            if (!Float.isNaN(draws.screens[i * 4])) chunks.bounds(draws.screens[i * 4], draws.screens[i * 4 + 1], draws.screens[i * 4 + 2], draws.screens[i * 4 + 3]);
            if (draws.ranges[i * 3] >= 0) chunks.range(draws.ranges[i * 3], draws.ranges[i * 3 + 1], draws.ranges[i * 3 + 2]);
            chunks.objects(draws.culled[i], draws.culledFirst[i] + CgGpuOps.cullFirst(l, capacity), capacity)
                    .indirect(draws.culledCounts[i], (draws.culledWord[i] + l) * 4L, CgIndirect.INSTANCES, 1);
            readBuffers(chunks, i);
        }
    }

    /** Draw {@code i}'s buffers read in place of engine buffers, every slot, and a skinned draw's palettes. */
    void readBuffers(CgChunkBuilder chunks, int i) {
        for (int s = i * CgDrawChunk.BUFFERS, end = s + CgDrawChunk.BUFFERS; s < end; s++) {
            if (draws.buffers[s] != null) chunks.buffer(draws.bufferAt[s], draws.buffers[s]);
        }
        if (draws.boneBases[i] >= 0) chunks.buffer(CgBindingPoints.BONE_PALETTES, palettes);
    }

    void group(CgChunkBuilder chunks, int i) {
        if (draws.gpuGroups[i] != null && CgTrace.isEnabled(CgChannels.GPU_GROUPS)) chunks.gpuGroup(draws.gpuGroups[i]);
    }

    /**
     * Draw {@code i}'s range, count and object record into the draw just begun, under {@link #model} and {@link #normal}:
     * the record written the first time a pass of the firing draws it, named by its slot after (Unreal's GPUScene).
     */
    void writeInstance(CgChunkBuilder chunks, int i) {
        if (draws.ranges[i * 3] >= 0) chunks.range(draws.ranges[i * 3], draws.ranges[i * 3 + 1], draws.ranges[i * 3 + 2]);
        if (draws.recordCounts[i] > 0) {
            writeRecords(chunks, i);
            return;
        }
        if (draws.counts[i] != null) chunks.indirect(draws.counts[i], draws.countOffsets[i], draws.countModes[i], draws.countFactors[i]);
        readBuffers(chunks, i);
        CgPrimitiveTable table = chunks.primitives();
        if (written(i, table)) {
            chunks.primitive(primitiveSlots[i]);
            return;
        }
        int at = chunks.instance();
        keep(i, table, CgPrimitiveTable.slot(at));
        float[] data = chunks.data();
        model.get(data, at);
        normal.get(data, at + 16);
        if (draws.boneBases[i] >= 0) {
            data[at + 19] = draws.boneBases[i];   // CG_OBJECT_SPARE.x: the skinned factory's palette base
            data[at + 23] = wideBones(i);         // .y: CG_BONES_WIDE, the vertex's width
        }
        data[at + 28] = draws.lights[i * 2];   // CG_OBJECT_LIGHT: the normal matrix's unused column
        data[at + 29] = draws.lights[i * 2 + 1];
        data[at + 30] = draws.emissions[i] - 1f;   // CG_OBJECT_EMISSION, less 1 so a record left 0 means 1
        System.arraycopy(draws.customs, i * 16, data, at + 32, 16);
    }

    /**
     * Draw {@code i}'s records of {@link Draw#indirectEach}, far to near under this stage's view, each under the draw's
     * transform at its own position, with {@link #normal} the draw's normal matrix.
     */
    void writeRecords(CgChunkBuilder chunks, int i) {
        chunks.indirectEach(draws.counts[i], draws.countModes[i], draws.countFactors[i]);
        readBuffers(chunks, i);
        int first = draws.recordFirsts[i], n = draws.recordCounts[i];
        if (recordOrder.length < n) recordOrder = new long[Math.max(n, recordOrder.length * 2)];
        double vx = recordView.x(), vy = recordView.y(), vz = recordView.z();
        for (int k = 0; k < n; k++) {
            int r = (first + k) * 3;
            float d = Math.max(0f, (float) (draws.recordPositions[r] - vx - eye.x) * forward.x
                    + (float) (draws.recordPositions[r + 1] - vy - eye.y) * forward.y
                    + (float) (draws.recordPositions[r + 2] - vz - eye.z) * forward.z);
            recordOrder[k] = (long) (0x7FFFFFFF - Float.floatToIntBits(d)) << 32 | k;   // far first
        }
        Arrays.sort(recordOrder, 0, n);
        // Written far to near once a firing: a later pass names them in that order, as the view is the firing's
        CgPrimitiveTable table = chunks.primitives();
        if (written(i, table)) {
            for (int o = 0; o < n; o++) {
                chunks.primitive(primitiveSlots[i] + o).countAt(draws.recordOffsets[first + (int) recordOrder[o]]);
            }
            return;
        }
        keep(i, table, table.count());
        for (int o = 0; o < n; o++) {
            int r = first + (int) recordOrder[o];
            recordModel.set(draws.transforms, i * 16);
            recordModel.m30(recordModel.m30() + (float) (draws.recordPositions[r * 3] - vx))
                    .m31(recordModel.m31() + (float) (draws.recordPositions[r * 3 + 1] - vy))
                    .m32(recordModel.m32() + (float) (draws.recordPositions[r * 3 + 2] - vz));
            int at = chunks.instance();
            float[] data = chunks.data();
            recordModel.get(data, at);
            normal.get(data, at + 16);
            if (draws.boneBases[i] >= 0) {
                data[at + 19] = draws.boneBases[i];
                data[at + 23] = wideBones(i);
            }
            data[at + 28] = draws.lights[i * 2];
            data[at + 29] = draws.lights[i * 2 + 1];
            data[at + 30] = draws.emissions[i] - 1f;
            System.arraycopy(draws.recordCustoms, r * 16, data, at + 32, 16);
            chunks.countAt(draws.recordOffsets[r]);
        }
    }

    /** Whether draw {@code i}'s record is in {@code table} from this firing: its slot is then {@code primitiveSlots[i]}. */
    boolean written(int i, CgPrimitiveTable table) {
        return i < primitiveSlots.length && primitiveFirings[i] == firing && primitiveTables[i] == table;
    }

    /** Notes draw {@code i}'s record, or the first of its records, at {@code slot} of {@code table} this firing. */
    void keep(int i, CgPrimitiveTable table, int slot) {
        if (i >= primitiveSlots.length) {
            int n = Math.max(i + 1, primitiveSlots.length * 2);
            primitiveSlots = Arrays.copyOf(primitiveSlots, n);
            primitiveFirings = Arrays.copyOf(primitiveFirings, n);
            primitiveTables = Arrays.copyOf(primitiveTables, n);
        }
        primitiveSlots[i] = slot;
        primitiveFirings[i] = firing;
        primitiveTables[i] = table;
    }

    /** A material's depth pass, its depth prepass, or its forward pass writing depth alone. */
    CgPipeline depthPipeline(CgMaterial material) {
        if (material.hasDepthPass()) return material.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT);
        CgPipeline forward = material.pipeline(CgInstanceKind.OBJECT);
        if (forward == null) return null;
        if (material.hasDepthPrepass()) return forward.as(CgPipelineForm.DEPTH);
        CgRenderState own = material.getPassRenderState(CgRenderPassVariant.FORWARD);
        CgRenderState colourless = depthOnly.get(own);
        if (colourless == null) depthOnly.put(own, colourless = own.withColorMask(CgColorMask.NONE));
        return forward.withState(colourless);
    }

    /** A material's snapshot in this recording, taken once per stage. */
    int bindingOf(CgMaterial material, CgRecording recording) {
        Integer id = bindings.get(material);
        if (id == null) bindings.put(material, id = material.captureBindings(recording.bindings()));
        return id;
    }
}
