package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.post.CgOutline;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.world.actor.CgEntity;
import com.crystalgraphics.world.actor.CgEntityActor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An entity drawn as a skinned character from a kit's files: its mesh in its default pose, in its prefab's materials.
 * Nothing shows until the kit's materials are ready; the mesh and kit load once per {@link Spec}, off the
 * frame, and every actor of it shares them.
 *
 * <pre>{@code
 * CgKitActor.Spec hero = new CgKitActor.Spec("hero", Paths.get("D:/kits/mykit"), "mykit", "mystudio",
 *         "models/Hero.glb", "skeletons/Hero_Skeleton.yaml", "prefabs/Hero_BP.yaml");
 * CgEntityActors.bindLocalPlayer(hero.factory());        // the client's player, drawn as it
 *
 * CgKitActor.preload(hero);                               // ahead: drawn as soon as it spawns
 * if (CgKitActor.settled(hero)) ...                       // a load task's poll: ready, or failed and logged
 * }</pre>
 *
 * <ul>
 *   <li>The mesh is fitted to the entity's height, feet at the root, in its default pose: no clip plays.</li>
 *   <li>A spec with a missing file fails once, logged, and its actors draw nothing.</li>
 * </ul>
 */
public final class CgKitActor extends CgEntityActor {

    private static final Logger LOGGER = LogManager.getLogger(CgKitActor.class);
    private static final Map<Spec, Loaded> LOADED = new ConcurrentHashMap<>();

    /**
     * A character's files, each path under {@code kit}: {@code namespace} is how the kit's assets are named,
     * {@code shading} the namespace its materials name their shading models by, {@code skeleton} the sockets' asset and {@code prefab} the one naming its materials.
     * {@code outline} is the id of a material of the kit drawn as the engine's outline over the character, marked by
     * {@code stencil} (its custom depth stencil value); null and 0 for none.
     */
    public record Spec(String name, Path kit, String namespace, String shading, String mesh, String skeleton,
                       String prefab, @Nullable String outline, int stencil) {

        /** An actor factory for this character, for {@code CgEntityActors.bind}. */
        public Supplier<CgKitActor> factory() {
            return () -> new CgKitActor(this);
        }
    }

    /** What a spec loaded: the files read on a worker, then the kit's materials. */
    private static final class Loaded {
        volatile CgSkeletalMesh mesh;
        /** The prefab's materials, one a slot, and the outline's after them where there is one. */
        volatile List<String> materials;
        volatile boolean failed;
        CgKitAssets kit;
        /** The slots' skinned forms and the outline's pass, asked for once the materials are ready. */
        CgPipelinePrecache.Request pipelines;

        /**
         * Moves the load along: the kit asked for once the files are read, then the pipelines its draws use. True once
         * all are ready. Render thread.
         */
        boolean advance(Spec s) {
            if (failed || mesh == null) return false;
            if (kit == null) {
                try {
                    kit = CgKitAssets.load(CgVfx.world(), s.namespace(), s.kit(), List.of(), materials, List.of(), List.of(),
                            s.shading());
                } catch (RuntimeException e) {
                    LOGGER.warn("[kit-actor] {}'s kit did not start loading: {}", s.name(), e.toString());
                    failed = true;
                    return false;
                }
            }
            if (kit.failed()) {
                LOGGER.warn("[kit-actor] {}'s kit failed to load: {}", s.name(), kit.failures());
                failed = true;
                return false;
            }
            if (!kit.declared()) return false;
            for (int i = 0; i < materials.size(); i++) if (!kit.material(i).ready()) return false;
            if (pipelines == null) {
                // What the skeletal component and the outline ask for at the spawn, built now: else the first draw is held
                int slots = mesh.mesh().submeshCount();
                pipelines = CgPipelinePrecache.request(p -> {
                    for (int i = 0; i < slots; i++) p.add(CgMaterial.of(kit.material(i).get()), false, true);
                    if (s.outline() != null) p.addPass(CgMaterial.of(kit.material(slots).get()));
                });
            }
            return pipelines.ready();
        }
    }

    private final Spec spec;
    private Loaded loaded;
    private CgSkeletalMeshComponent body;
    @Nullable
    private CgOutline outline;

    private CgKitActor(Spec spec) {
        this.spec = spec;
    }

    /**
     * Starts loading {@code spec}'s files and kit materials now, so its actors draw as they spawn rather than seconds
     * later. Once a spec; render thread.
     */
    public static void preload(Spec spec) {
        loaded(spec);
    }

    /** Whether {@code spec} is ready to draw, or failed: what a load waits on. Render thread; moves its load along. */
    public static boolean settled(Spec spec) {
        Loaded l = loaded(spec);
        return l.advance(spec) || l.failed;
    }

    private static Loaded loaded(Spec spec) {
        return LOADED.computeIfAbsent(spec, s -> {
            Loaded l = new Loaded();
            CgJob.run(() -> read(s, l)).named("kit-actor " + s.name()).onError(e -> {
                LOGGER.warn("[kit-actor] {} failed to load: {}", s.name(), e.detail(), e.cause());
                l.failed = true;
            });
            return l;
        });
    }

    @Override
    protected void onSpawn(CgEntity entity) {
        loaded = loaded(spec);
    }

    @Override
    protected void onRemove(CgEntity entity) {
        if (outline != null) outline.close();
        outline = null;
    }

    @Override
    protected void tick(CgEntity entity, float dt) {
        if (body != null || !entity.present() || !loaded.advance(spec)) return;
        CgKitAssets kit = loaded.kit;
        int slots = loaded.mesh.mesh().submeshCount();
        double height = entity.pose(CgEntityQuery.HEIGHT);
        body = add(scene().skeletal(loaded.mesh).transform(fit(loaded.mesh, height > 0 ? (float) height : 1.8f))
                .materials(s -> CgMaterial.of(kit.material(s).get())).customData(0, 0f, 0f, 0f, 0f));
        if (spec.outline() != null) {
            body.customDepth(spec.stencil(), 0xFF);
            outline = CgOutline.add(CgMaterial.of(kit.material(slots).get()));        }
        LOGGER.info("[kit-actor] {} drawn as {}{}", entity, spec.name(), outline != null ? ", outlined" : "");
    }

    /** The mesh scaled to {@code height}, feet on the root, centred. */
    private static Matrix4f fit(CgSkeletalMesh mesh, float height) {
        CgAABB box = mesh.mesh().bounds(new CgAABB());
        float scale = height / (float) Math.max(box.maxY() - box.minY(), 1e-4);
        return new Matrix4f().scaling(scale).translate((float) -(box.minX() + box.maxX()) / 2f,
                (float) -box.minY(), (float) -(box.minZ() + box.maxZ()) / 2f);
    }

    /** A worker's: the mesh with its sockets, every clip file, and the prefab's materials. */
    private static void read(Spec s, Loaded out) {
        File mesh = s.kit().resolve(s.mesh()).toFile();
        if (!mesh.isFile()) throw new IllegalStateException("no mesh at " + mesh);
        CgSkeletalMesh body = CgKitModels.withSockets(CgMeshLoader.skeletal(mesh.getPath()), s.kit(), s.skeleton());
        List<String> materials = CgKitModels.prefabMaterials(s.kit().resolve(s.prefab()));
        if (materials.size() != body.mesh().submeshCount()) {
            throw new IllegalStateException(s.name() + "'s prefab names " + materials.size() + " materials for "
                    + body.mesh().submeshCount() + " slots");
        }
        List<String> all = new ArrayList<>(materials);
        if (s.outline() != null) all.add(s.outline());
        out.materials = all;
        out.mesh = body;
    }
}
