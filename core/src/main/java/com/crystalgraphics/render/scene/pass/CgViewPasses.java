package com.crystalgraphics.render.scene.pass;

import com.crystalgraphics.animation.CgBonePalettes;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.compute.ops.CgCull;
import com.crystalgraphics.compute.ops.CgCullSets;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.meshpass.CgMeshBatch;
import com.crystalgraphics.render.meshpass.CgMeshPassCommands;
import com.crystalgraphics.render.meshpass.CgMeshPassType;
import com.crystalgraphics.render.meshpass.CgPrimitiveInfo;
import com.crystalgraphics.render.meshpass.CgPrimitiveRelevance;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgCachedCommands;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgPrimitiveComponent;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.render.scene.visibility.CgInstanceCulling;
import com.crystalgraphics.render.scene.visibility.CgViewVisibility;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A view's mesh passes over one scene this frame: for each pass type, the draw commands of what the view sees, sorted
 * and ready for a raster pass. Unreal's per-view mesh pass setup ({@code FParallelMeshDrawCommandPass} fed by
 * {@code FViewInfo}): a persistent primitive's commands come from its kept ones ({@code CgCachedCommands}), built by the
 * pass's processor only when its row changed; a dynamic primitive's are built for the view, each drawing a frame record
 * of the recording; an instanced component's draw the ids its view's GPU cull kept.
 *
 * <pre>{@code
 * CgViewPasses passes = CgViewPasses.of(frame.view(), scene, frame.camera());
 * passes.cull(rec, pyramid);                              // its instances against the view: before any pass draws them
 * rec.raster("scene.base").color(sceneColour, CgLoad.load()).depth(depth, CgLoad.load(), CgDepthAccess.WRITE)
 *         .add(passes.commands(CgEnginePasses.BASE, rec));
 * rec.raster("scene.velocity").color(velocity, CgLoad.CLEAR).depth(depth, CgLoad.load(), CgDepthAccess.READ)
 *         .add(passes.commands(CgEnginePasses.VELOCITY, rec));
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's. Made at a frame's first ask from the view's visibility of the scene, as that is.</li>
 *   <li>A pass's commands are built once per frame and recording: asking again in the same recording answers the same
 *       list. They name the recording's frame records and palettes, so another recording's ask builds its own.</li>
 *   <li>Without {@link #cull}, an instanced component draws every instance.</li>
 *   <li>A command whose program is not built yet is skipped and its build started ({@link #skipUnready}), counted
 *       {@code scene.passes.unready}: nothing compiles at a draw.</li>
 *   <li>Counted on the {@code crystalgraphics.world} channel: {@code scene.passes.built} (rows a processor ran for),
 *       {@code .kept} (rows taken from kept commands) and {@code .commands}.</li>
 * </ul>
 */
public final class CgViewPasses {

    private static final CgViewState.Key<Map<CgScene, CgViewPasses>> PASSES =
            new CgViewState.Key<>("crystalgraphics.scene.passes", WeakHashMap::new);

    private static final int ZONE = CgTrace.name("scene.passes"), BUILT = CgTrace.name("scene.passes.built"),
            KEPT = CgTrace.name("scene.passes.kept"), COMMANDS = CgTrace.name("scene.passes.commands"),
            UNREADY = CgTrace.name("scene.passes.unready");

    private final CgView view;
    private final CgScene scene;
    private final CgInstanceCulling culling;
    private CgViewVisibility seen;
    private CgCamera camera;
    private double originX, originY, originZ;

    /**
     * Per pass type: its list and the recording and visibility ({@link #made}) it was built for. A recording is reused by
     * a view's next draw in the same frame, so the recording alone never says the list is this draw's.
     */
    private CgDrawCommands[] lists = new CgDrawCommands[16];
    private CgRecording[] listRecordings = new CgRecording[16];
    private int[] listMade = new int[16];
    /** Formed lists, per pass type and form: each its list and the recording and visibility it was built for. */
    private final Map<CgCommandForm, Formed[]> formed = new IdentityHashMap<>();

    private static final class Formed {
        CgDrawCommands list;
        CgRecording recording;
        int made = -1;
    }

    /** Per row: its frame record's slot in {@link #slotsRecording}'s table this frame, -1 until written. */
    private int[] frameSlots = new int[64];
    @Nullable
    private CgRecording slotsRecording;
    /** Per row: its row in the cull of {@link #culledRecording}, -1 for none. */
    private int[] cullRows = new int[64];
    @Nullable
    private CgRecording culledRecording;
    @Nullable
    private CgRecording palettesRecording;
    @Nullable
    private CgGraphBuffer palettes;

    private final CgMeshPassCommands out = new CgMeshPassCommands();
    /** The culled records rows' cull this recording: one dispatch for all, its kept records and counts. */
    private final CgCull recordCull = new CgCull();
    private final CgCullSets recordSets = new CgCullSets();
    private final Matrix4f place = new Matrix4f();
    private int[] recordHandles = new int[64];
    @Nullable
    private CgGraphBuffer recordsOut, recordsKept;
    @Nullable
    private CgRecording recordsRecording;
    private final Batch batch = new Batch();
    private final Matrix4f model = new Matrix4f(), normal = new Matrix4f();
    private boolean skipUnready = true;
    private int built, kept, unready;
    /** Which of its visibility's makings these passes are of: a new one is a new draw. */
    private int made = -1;
    /** Every view's draws left out as unready since the start: render thread. */
    private static long unreadyTotal;

    /**
     * Draws left out since the start because their pipeline was still building ({@code skipUnready}): a precache that
     * worked keeps it still across a move's first play.
     *
     * <pre>{@code
     * long before = CgViewPasses.unreadyDraws();
     * play(move);                                          // ... frames later:
     * assert CgViewPasses.unreadyDraws() == before;
     * }</pre>
     */
    public static long unreadyDraws() {
        return unreadyTotal;
    }

    private CgViewPasses(CgView view, CgScene scene) {
        this.view = view;
        this.scene = scene;
        this.culling = new CgInstanceCulling("crystalgraphics.scene.instances");
    }

    /** {@code view}'s passes over {@code scene} this frame, made through {@code camera} at the frame's first ask. */
    public static CgViewPasses of(CgView view, CgScene scene, CgCamera camera) {
        Map<CgScene, CgViewPasses> all = view.state().get(PASSES);
        CgViewPasses p = all.get(scene);
        if (p == null) all.put(scene, p = new CgViewPasses(view, scene));
        CgViewVisibility seen = CgViewVisibility.of(view, scene, camera);
        if (p.made != seen.computations()) p.newFrame(seen, camera);
        return p;
    }

    private void newFrame(CgViewVisibility seen, CgCamera camera) {
        this.seen = seen;
        this.camera = camera;
        made = seen.computations();
        originX = camera.originX();
        originY = camera.originY();
        originZ = camera.originZ();
        slotsRecording = null;
        culledRecording = null;
        palettesRecording = null;
        built = kept = unready = 0;
        int rows = seen.batches().size();
        if (frameSlots.length < rows) {
            frameSlots = new int[Math.max(rows, frameSlots.length * 2)];
            cullRows = new int[frameSlots.length];
        }
    }

    /** What the view sees of the scene: the rows the passes are built from. */
    public CgViewVisibility visibility() {
        return seen;
    }

    /**
     * Culls the view's instanced rows on the GPU in {@code recording}, through the camera the passes were made with,
     * against the frustum and {@code pyramid} (null for the frustum alone): what every pass of the recording asked after this draws of them. Once per recording.
     */
    public void cull(CgRecording recording, @Nullable CgGraphTexture pyramid) {
        if (culledRecording == recording || recordsRecording == recording) return;
        culling.cull(recording, scene, seen, camera, pyramid);
        Arrays.fill(cullRows, 0, seen.batches().size(), -1);
        for (int r = 0; r < culling.rows(); r++) cullRows[culling.batch(r)] = r;
        culledRecording = culling.rows() > 0 ? recording : null;
        cullRecords(recording, pyramid);
    }

    /** Whether the view has rows {@link #cull} culls: instances, or culled records. */
    public boolean culls() {
        CgMeshBatches rows = seen.batches();
        for (int b = 0, n = rows.size(); b < n; b++) if (rows.instances(b) != null || rows.culled(b)) return true;
        return false;
    }

    /**
     * The view's culled {@code records} rows, all in one dispatch: each record kept where its box, placed by the row's
     * transform and scale, meets the frustum and the pyramid; the row's stated custom data stamped on it.
     */
    private void cullRecords(CgRecording recording, @Nullable CgGraphTexture pyramid) {
        CgMeshBatches rows = seen.batches();
        int n = rows.size();
        if (recordHandles.length < n) recordHandles = new int[Math.max(n, recordHandles.length * 2)];
        recordSets.clear();
        boolean any = false;
        float[] v = rows.values();
        for (int b = 0; b < n; b++) {
            recordHandles[b] = -1;
            if (!rows.culled(b)) continue;
            int at = b * CgMeshBatches.VALUES;
            place.set(v[at], v[at + 1], v[at + 2], 0f, v[at + 3], v[at + 4], v[at + 5], 0f, v[at + 6], v[at + 7], v[at + 8], 0f,
                    (float) (rows.position(b, 0) - originX), (float) (rows.position(b, 1) - originY),
                    (float) (rows.position(b, 2) - originZ), 1f);
            recordCull.mesh(rows.mesh(b)).place(place).pad(rows.cullPad(b))
                    .light(v[at + CgMeshBatches.LIGHT], v[at + CgMeshBatches.LIGHT + 1]).scale(rows.cullScale(b)).ownCustoms();
            int mask = rows.customMask(b);
            for (int k = 0; k < 4; k++) {
                int c = at + CgMeshBatches.CUSTOM + k * 4;
                if ((mask & 1 << k) != 0) recordCull.custom(k, v[c], v[c + 1], v[c + 2], v[c + 3]);
            }
            recordHandles[b] = recordSets.add(recordCull, (CgGraphBuffer) rows.records(b), rows.recordFirst(b), rows.cullCount(b));
            any = true;
        }
        recordsRecording = null;
        if (!any) return;
        long bytes = (long) recordSets.records() * CgGpuOps.cullRecordBytes(), words = recordSets.words() * 4L;
        if (recordsOut == null || recordsOut.size() < bytes) {
            recordsOut = CgGraphBuffer.transientBuffer("cg_scene.culledRecords", CgBufferDesc.of(bytes, CgBufferUsage.STORAGE));
        }
        if (recordsKept == null || recordsKept.size() < words) {
            recordsKept = CgGraphBuffer.transientBuffer("cg_scene.keptRecords", CgBufferDesc.of(words, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        }
        recordCull.view(camera.view(), camera.projection()).pyramid(pyramid);
        CgComputePass pass = recording.compute("scene.cullRecords");
        CgGpuOps.cull(pass, recordCull, recordSets, recordsOut, recordsKept);
        pass.end();
        recordsRecording = recording;
    }

    /** The instance cull this view's passes draw from: its kept ids and counts. */
    public CgInstanceCulling culling() {
        return culling;
    }

    /** Whether a command whose program is not built is skipped (the default) rather than built at its draw. */
    public CgViewPasses skipUnready(boolean skip) {
        skipUnready = skip;
        return this;
    }

    /**
     * {@code type}'s commands for this view in {@code recording}, sorted by each row's key: built at the first ask of
     * the frame and recording.
     */
    public CgDrawCommands commands(CgMeshPassType type, CgRecording recording) {
        int t = type.index();
        if (t >= lists.length) {
            int n = Math.max(t + 1, lists.length * 2);
            lists = Arrays.copyOf(lists, n);
            listRecordings = Arrays.copyOf(listRecordings, n);
            listMade = Arrays.copyOf(listMade, n);
        }
        if (lists[t] != null && listRecordings[t] == recording && listMade[t] == made) return lists[t];
        if (lists[t] == null || lists[t].table() != recording.primitives()) lists[t] = new CgDrawCommands(recording.primitives());
        CgDrawCommands list = lists[t];
        listRecordings[t] = recording;
        listMade[t] = made;
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, ZONE)) {
            build(type, recording, list, null);
        }
        return list;
    }

    /**
     * {@code type}'s commands for this view in {@code recording}, each through what {@code form} makes of its pipeline
     * (left out where it answers null), sorted: built at the first ask of the frame, recording and form. The scene
     * renderer's per-view choices; keep the form in a field, as a list is kept per form.
     */
    public CgDrawCommands commands(CgMeshPassType type, CgRecording recording, CgCommandForm form) {
        int t = type.index();
        Formed[] byType = formed.get(form);
        if (byType == null || byType.length <= t) {
            Formed[] grown = new Formed[Math.max(t + 1, 16)];
            if (byType != null) System.arraycopy(byType, 0, grown, 0, byType.length);
            formed.put(form, byType = grown);
        }
        Formed f = byType[t];
        if (f == null) byType[t] = f = new Formed();
        if (f.list != null && f.recording == recording && f.made == made) return f.list;
        if (f.list == null || f.list.table() != recording.primitives()) f.list = new CgDrawCommands(recording.primitives());
        f.recording = recording;
        f.made = made;
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, ZONE)) {
            build(type, recording, f.list, form);
        }
        return f.list;
    }

    private void build(CgMeshPassType type, CgRecording recording, CgDrawCommands list, @Nullable CgCommandForm form) {
        list.clear();
        CgMeshBatches rows = seen.batches();
        int t = type.index(), before = built, keptBefore = kept;
        for (int b = 0, n = rows.size(); b < n; b++) {
            CgMaterialInterface material = rows.material(b);
            CgPrimitiveInfo primitive = rows.primitive(b);
            CgCachedCommands cache = type.perFrame() ? null : rows.cache(b);
            if (cache == null) {
                out.clear();
                type.processor().add(batch.at(rows, b), primitive, out);
                built++;
                for (int i = 0; i < out.size(); i++) {
                    emit(list, recording, rows, b, formed(form, rows, b, out.pipeline(i)), out.material(i), out.mesh(i), out.submesh(i));
                }
                continue;
            }
            int row = rows.ordinal(b), settings = ((CgPrimitiveComponent<?>) primitive).passSettings();
            CgPipeline forward = CgMeshPassCommands.pipeline(material, CgRenderPassVariant.FORWARD);
            if (cache.valid(row, t, material, rows.mesh(b), rows.submesh(b), forward, settings)) {
                kept++;
            } else {
                out.clear();
                type.processor().add(batch.at(rows, b), primitive, out);
                cache.store(row, t, material, rows.mesh(b), rows.submesh(b), forward, settings, batch.relevance(), out);
                built++;
            }
            for (int i = cache.first(row, t), end = i + cache.count(row, t); i < end; i++) {
                emit(list, recording, rows, b, formed(form, rows, b, cache.pipeline(i)), cache.material(i), cache.mesh(i), cache.submesh(i));
            }
        }
        list.sort();
        CgTrace.add(CgChannels.WORLD, BUILT, built - before);
        CgTrace.add(CgChannels.WORLD, KEPT, kept - keptBefore);
        CgTrace.add(CgChannels.WORLD, COMMANDS, list.size());
    }

    @Nullable
    private static CgPipeline formed(@Nullable CgCommandForm form, CgMeshBatches rows, int b, CgPipeline pipeline) {
        return form == null ? pipeline : form.form(rows, b, pipeline);
    }

    /** One command of row {@code b}: its pipeline and material's bindings now, its objects, its key, its palettes. */
    private void emit(CgDrawCommands list, CgRecording recording, CgMeshBatches rows, int b, @Nullable CgPipeline pipeline,
                      CgMaterialInterface material, CgMesh mesh, int submesh) {
        if (pipeline == null) return;
        if (skipUnready && !pipeline.isBuilt()) {
            pipeline.precache();
            unready++;
            unreadyTotal++;
            CgTrace.add(CgChannels.WORLD, UNREADY, 1);
            return;
        }
        list.add(pipeline, CgMaterialProxy.of(material).bindings(), mesh).range(submesh, rows.firstIndex(b), rows.indexCount(b));
        objects(list, recording, rows, b);
        list.sortKey(rows.key(b));
        int label = ((CgPrimitiveComponent<?>) rows.primitive(b)).debugLabelName();
        if (label >= 0 && CgTrace.isEnabled(CgChannels.GPU_GROUPS)) list.gpuGroup(label);
        if (rows.bones(b) >= 0) list.buffer(CgBindingModel.BONES, palettes(recording));
        for (int s = 0; s < CgDrawChunk.BUFFERS; s++) {
            if (rows.bufferAt(b, s) != null) list.buffer(rows.bufferAt(b, s), rows.buffer(b, s));
        }
    }

    /** Row {@code b}'s objects: its scene record, its instances (culled on the GPU where they were), or a frame record. */
    private void objects(CgDrawCommands list, CgRecording recording, CgMeshBatches rows, int b) {
        CgMeshBatches.Instances instances = rows.instances(b);
        if (instances != null) {
            int r = culledRecording == recording ? cullRows[b] : -1;
            if (r >= 0) {
                list.ids(culling.ids(), culling.first(r), instances.count())
                        .indirect(culling.counts(), culling.word(r) * 4L, CgIndirect.INSTANCES, 1);
                return;
            }
            int[] ids = instances.ids();
            for (int i = 0, n = instances.count(); i < n; i++) list.sceneRecord(ids[i]);
            return;
        }
        // A scene record's id has its top bit set: negative, and -1 alone means none
        int id = rows.objectId(b);
        if (id != CgMeshBatches.NO_OBJECT) {
            list.sceneRecord(id);
            return;
        }
        int h = recordsRecording == recording && rows.culled(b) ? recordHandles[b] : -1;
        if (h >= 0) {
            int capacity = rows.cullCount(b).capacity();
            list.records(recordsOut, recordSets.first(h) + CgGpuOps.cullFirst(0, capacity), capacity)
                    .indirect(recordsKept, recordSets.word(h) * 4L, CgIndirect.INSTANCES, 1);
            return;
        }
        if (rows.records(b) != null) {
            list.records(rows.records(b), rows.recordFirst(b), rows.recordCapacity(b));
        } else {
            list.frameRecord(frameRecord(recording, rows, b));
        }
        if (rows.count(b) != null) list.indirect(rows.count(b), rows.countOffset(b), rows.countMode(b), rows.countFactor(b));
    }

    /**
     * Row {@code b}'s frame record in {@code recording}'s table, written at its first ask: its transform relative to the
     * view's origin, its normal matrix with the light and emission lanes, its custom data (the record a world draw wrote).
     */
    private int frameRecord(CgRecording recording, CgMeshBatches rows, int b) {
        if (slotsRecording != recording) {
            Arrays.fill(frameSlots, 0, rows.size(), -1);
            slotsRecording = recording;
        }
        if (frameSlots[b] >= 0) return frameSlots[b];
        float[] v = rows.values();
        int at = b * CgMeshBatches.VALUES;
        // The basis as the collector wrote it, column by column, then the translation from the view's origin
        model.set(v[at], v[at + 1], v[at + 2], 0f, v[at + 3], v[at + 4], v[at + 5], 0f, v[at + 6], v[at + 7], v[at + 8], 0f,
                (float) (rows.position(b, 0) - originX), (float) (rows.position(b, 1) - originY),
                (float) (rows.position(b, 2) - originZ), 1f);
        model.normal(normal);
        CgPrimitiveTable table = recording.primitives();
        int record = table.add();
        float[] data = table.data();
        model.get(data, record);
        normal.get(data, record + 16);
        if (rows.bones(b) >= 0) data[record + 19] = rows.bones(b);   // CG_OBJECT_SPARE.x: the palette's base
        data[record + 28] = v[at + CgMeshBatches.LIGHT];               // CG_OBJECT_LIGHT
        data[record + 29] = v[at + CgMeshBatches.LIGHT + 1];
        data[record + 30] = v[at + CgMeshBatches.EMISSION] - 1f;       // CG_OBJECT_EMISSION, less 1
        System.arraycopy(v, at + CgMeshBatches.CUSTOM, data, record + 32, 16);
        return frameSlots[b] = CgPrimitiveTable.slot(record);
    }

    private CgGraphBuffer palettes(CgRecording recording) {
        if (palettesRecording != recording) {
            palettes = CgBonePalettes.record(recording);
            palettesRecording = recording;
        }
        return palettes;
    }

    /** Rows a processor ran for this frame, rows taken from kept commands, and commands skipped as unbuilt. */
    public int built() {
        return built;
    }

    public int kept() {
        return kept;
    }

    public int unready() {
        return unready;
    }

    /** A row as a processor reads it; its relevance worked out at the first ask. */
    private static final class Batch implements CgMeshBatch {
        private CgMeshBatches rows;
        private int b, relevance;
        private boolean relevanceKnown;

        Batch at(CgMeshBatches rows, int b) {
            this.rows = rows;
            this.b = b;
            relevanceKnown = false;
            return this;
        }

        @Override
        public CgMesh mesh() {
            return rows.mesh(b);
        }

        @Override
        public CgMaterialInterface material() {
            return rows.material(b);
        }

        @Override
        public int submesh() {
            return rows.submesh(b);
        }

        @Override
        public int relevance() {
            if (!relevanceKnown) {
                relevance = CgPrimitiveRelevance.of(rows.material(b));
                relevanceKnown = true;
            }
            return relevance;
        }

        @Override
        public boolean skinned() {
            return rows.bones(b) >= 0 || rows.mesh(b).format().isSkinned();
        }

        @Override
        public int flags() {
            return rows.flags(b);
        }

        @Override
        public boolean separate() {
            return rows.layer(b) == CgSortLayer.AFTER_DOF;
        }
    }
}
