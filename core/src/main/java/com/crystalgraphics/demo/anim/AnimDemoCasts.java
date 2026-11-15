package com.crystalgraphics.demo.anim;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.mesh.geckolib.CgGeckoAnimation;
import com.crystalgraphics.api.mesh.geckolib.CgGeckoModel;
import com.crystalgraphics.api.state.CgCullState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.world.actor.kit.CgKitActor;
import com.crystalgraphics.world.actor.kit.CgKitAssets;
import com.crystalgraphics.world.actor.kit.CgKitModels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The demo's characters: the engine's bundled humanoid bodies, GeckoLib models with their clips, and any kit
 * character a {@link CgKitActor.Spec} names, with the clips of its {@code clips} folders. Files are read on a worker;
 * a kit's materials load after, and a kit character joins once they and their pipelines are ready. One whose files or
 * materials fail is logged and left out.
 *
 * <pre>{@code
 * AnimDemoCasts casts = AnimDemoCasts.load(specs);   // render thread
 * if (casts.settled()) build(casts.all());           // each frame until it answers true
 * }</pre>
 */
final class AnimDemoCasts {

    private static final Logger LOGGER = LogManager.getLogger(AnimDemoCasts.class);
    private static final String HUMANOID = "kk:humanoid/";
    private static final List<String> BODIES = List.of("model", "skinny_model", "female_model", "skinny_female_model");
    private static final List<String> HUMANOID_CLIPS = List.of("grabs/default", "styles/default/idle",
            "styles/default/movement", "styles/default/melee", "styles/default/defense", "styles/default/guarding",
            "styles/default/hurt", "styles/default/release", "styles/default/vanilla");

    /** A kit character on its way: files read on a worker, then its kit's materials and their pipelines. */
    private static final class Kit {
        final CgKitActor.Spec spec;
        volatile CgSkeletalMesh mesh;
        volatile List<AnimDemoCast.Clip> clips;
        volatile List<String> materials;
        volatile boolean failed;
        CgKitAssets assets;
        CgPipelinePrecache.Request pipelines;
        AnimDemoCast cast;

        Kit(CgKitActor.Spec spec) {
            this.spec = spec;
        }

        /** Moves its load along; true once it has a cast or has failed. Render thread. */
        boolean settled() {
            if (cast != null || failed) return true;
            if (assets.failed()) {
                LOGGER.warn("[anim-demo] {}'s kit materials failed, left out: {}", spec.name(), assets.failures());
                failed = true;
                return true;
            }
            if (mesh == null || !assets.declared()) return false;
            for (int i = 0; i < materials.size(); i++) if (!assets.material(i).ready()) return false;
            if (pipelines == null) {
                pipelines = CgPipelinePrecache.request(p -> {
                    for (int i = 0; i < materials.size(); i++) p.add(CgMaterial.of(assets.material(i).get()), false, true);
                });
            }
            if (!pipelines.ready()) return false;
            CgMaterial[] slots = new CgMaterial[materials.size()];
            for (int i = 0; i < slots.length; i++) slots[i] = CgMaterial.of(assets.material(i).get());
            cast = new AnimDemoCast(spec.name(), spec.name(), mesh, clips, standing(mesh), s -> slots[s]);
            return true;
        }
    }

    private volatile List<AnimDemoCast> humanoids;
    private volatile boolean humanoidsFailed;
    private final List<Kit> kits = new ArrayList<>();

    private AnimDemoCasts() {
    }

    /** Starts loading the humanoids and every character of {@code specs}. Render thread. */
    static AnimDemoCasts load(List<CgKitActor.Spec> specs) {
        AnimDemoCasts casts = new AnimDemoCasts();
        CgMaterial look = CgMaterial.of(CgMaterialDefinition.of("kk:humanoid",
                        "crystalgraphics:shaders/materials/entity.shader")
                .cull(CgCullState.NONE)   // GeckoLib's entities draw as entityCutoutNoCull
                .texture("_MainTex", CgTextures.of(HUMANOID + "textures/base.png"), CgSampler.Filter.NEAREST,
                        CgSampler.Address.CLAMP)
                .build());
        CgJob.run(() -> casts.humanoids = humanoids(look)).named("anim-demo humanoids").onError(e -> {
            LOGGER.warn("[anim-demo] the humanoids failed to load: {}", e.detail(), e.cause());
            casts.humanoidsFailed = true;
        });
        for (CgKitActor.Spec spec : specs) {
            Kit kit = new Kit(spec);
            try {
                // The prefab names the materials: their load starts now, beside the mesh's and the clips' reads
                kit.materials = CgKitModels.prefabMaterials(spec.kit().resolve(spec.prefab()));
                kit.assets = CgKitAssets.load(CgVfx.world(), spec.namespace(), spec.kit(), List.of(), kit.materials,
                        List.of(), List.of(), spec.shading());
            } catch (RuntimeException e) {
                LOGGER.warn("[anim-demo] {} failed to load, left out: {}", spec.name(), e.toString());
                continue;
            }
            casts.kits.add(kit);
            CgJob.run(() -> read(kit)).named("anim-demo " + spec.name()).onError(e -> {
                LOGGER.warn("[anim-demo] {} failed to load, left out: {}", spec.name(), e.detail(), e.cause());
                kit.failed = true;
            });
        }
        return casts;
    }

    /** Whether every cast has loaded or failed; moves the kits' loads along. Render thread. */
    boolean settled() {
        boolean all = humanoids != null || humanoidsFailed;
        for (Kit k : kits) all &= k.settled();
        return all;
    }

    /** Every cast that loaded: the kits' characters first, then the humanoids. */
    List<AnimDemoCast> all() {
        List<AnimDemoCast> all = new ArrayList<>();
        for (Kit k : kits) if (k.cast != null) all.add(k.cast);
        if (humanoids != null) all.addAll(humanoids);
        return all;
    }

    /** The default humanoid, or null where they failed. */
    @Nullable
    AnimDemoCast humanoid() {
        return humanoids == null || humanoids.isEmpty() ? null : humanoids.get(0);
    }

    /** The kits' characters that loaded. */
    List<AnimDemoCast> kitCasts() {
        List<AnimDemoCast> out = new ArrayList<>();
        for (Kit k : kits) if (k.cast != null) out.add(k.cast);
        return out;
    }

    // ── Worker reads ──

    private static List<AnimDemoCast> humanoids(CgMaterial look) {
        List<AnimDemoCast> out = new ArrayList<>();
        for (String body : BODIES) {
            CgGeckoModel geo = CgGeckoModel.load(HUMANOID + "geo/" + body + ".geo.json");
            List<AnimDemoCast.Clip> clips = new ArrayList<>();
            for (String file : HUMANOID_CLIPS) {
                String stem = file.substring(file.lastIndexOf('/') + 1);
                for (CgGeckoAnimation a : geo.animations(HUMANOID + "animations/core/" + file + ".animation.json")) {
                    clips.add(new AnimDemoCast.Clip(stem + "/" + a.name(), a.sequence(), a.loop() == CgGeckoAnimation.Loop.LOOP));
                }
            }
            String name = "Humanoid " + body.replace("_model", "").replace("model", "").replace('_', ' ').trim();
            // A GeckoLib model faces -z
            out.add(new AnimDemoCast(name.trim(), "humanoid", geo.mesh(), clips, new Matrix4f().rotationY((float) Math.PI), s -> look));
        }
        return out;
    }

    /** A kit character's mesh with its sockets, its clip folders' {@code .glb} clips and its prefab's materials. */
    private static void read(Kit kit) {
        CgKitActor.Spec s = kit.spec;
        File meshFile = s.kit().resolve(s.mesh()).toFile();
        if (!meshFile.isFile()) throw new IllegalStateException("no mesh at " + meshFile);
        CgSkeletalMesh mesh = CgKitModels.withSockets(CgMeshLoader.skeletal(meshFile.getPath()), s.kit(), s.skeleton());
        if (kit.materials.size() != mesh.mesh().submeshCount()) {
            throw new IllegalStateException(s.name() + "'s prefab names " + kit.materials.size() + " materials for "
                    + mesh.mesh().submeshCount() + " slots");
        }
        List<File> files = new ArrayList<>();
        for (String folder : s.clips()) {
            File[] in = s.kit().resolve(folder).toFile().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".glb"));
            if (in == null) throw new IllegalStateException(s.name() + "'s clips folder " + folder + " is missing");
            files.addAll(Arrays.asList(in));
        }
        files.sort(null);
        List<List<CgAnimSequence>> read = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) read.add(null);
        CgJob.forEach(files.size(), i -> read.set(i, CgMeshLoader.sequences(files.get(i).getAbsolutePath())));
        List<AnimDemoCast.Clip> clips = new ArrayList<>();
        for (CgAnimSequence c : mesh.sequences()) clips.add(new AnimDemoCast.Clip(c.name(), c, true));
        for (List<CgAnimSequence> sequences : read) {
            // A file moving one bone (a locator's) is a prop's, not the character's
            for (CgAnimSequence c : sequences) if (c.trackCount() > 1) clips.add(new AnimDemoCast.Clip(c.name(), c, true));
        }
        kit.clips = clips;
        kit.mesh = mesh;
    }

    /** As authored, its feet on the floor, centred. */
    private static Matrix4f standing(CgSkeletalMesh mesh) {
        CgAABB box = mesh.mesh().bounds(new CgAABB());
        return new Matrix4f().translation((float) -(box.minX() + box.maxX()) / 2f, (float) -box.minY(),
                (float) -(box.minZ() + box.maxZ()) / 2f);
    }
}
