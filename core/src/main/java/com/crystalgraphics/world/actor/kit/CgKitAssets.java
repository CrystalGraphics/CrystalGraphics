package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgShadingModels;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.kind.CgStageKinds;
import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadHandle;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.render.scene.prefab.CgPrefabAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.render.post.stage.CgStageDefinition;
import com.crystalgraphics.util.io.CgIO;

import javax.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A kit's systems and materials loaded from its files by id for a scene or an actor to play: the kit scanned under its namespace,
 * one bundle loaded to {@code READY} at move priority, each system precached against the scene's {@link CgVfxSystem}.
 *
 * <pre>{@code
 * CgKitAssets kit = CgKitAssets.load(vfx, "kakarot_kit", folder,
 *         List.of("Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_shot_01_00"));
 * // each frame, until ready() or failed():
 * if (kit.ready()) vfx.spawn(kit.ref(0), x, y, z);
 *
 * // a character's materials, its moves' sequences and a stage too, its shading models registered first under their own namespace
 * CgKitAssets kit = CgKitAssets.load(vfx, "kakarot_kit", folder, List.of(), materialIds,
 *         List.of("kakarot_kit:Blueprints/Character/C001A/BTL/SKL/AM_C001A_S_SKL001_01"),
 *         List.of("kakarot_kit:Area02"), "kakarot");
 * CgMaterial body = CgMaterial.of(kit.material(4).get());
 * sequencer.play(kit.sequence(0).get(), Map.of("caster", goku));   // the systems it spawns came with it
 * }</pre>
 *
 * <ul>
 *   <li>The kit is scanned on the workers: {@link #ref}, {@link #material} and {@link #sequence} answer null until
 *       {@link #declared()}, a frame or more later. A namespace the game mounted already (a mod's, the engine's own
 *       {@code kk}) is not scanned again: its refs are declared at once.</li>
 *   <li>Made in a scene's {@code init}, its scan and load are the loading screen's tasks ({@code <namespace>:scan},
 *       {@code :load}), so the bundle is ready before the hide; what a scene asks for after the hide loads in play.</li>
 *   <li>The kit's folder answers for its namespace's files ({@code CgIO.mount}): {@code <namespace>:textures/...}
 *       is read from its {@code textures} folder, wherever the kit is.</li>
 *   <li>A system's id is its path under the kit's {@code vfx/systems}, without {@code .yaml}; a material's and a
 *       sequence's are whole, as a prefab names them ({@code kakarot_kit:Art/MDL/...}).</li>
 * </ul>
 */
public final class CgKitAssets {

    private final List<String> ids;
    private final CgAssetRef<CgVfxSystemDefinition>[] refs;
    private final CgAssetRef<CgMaterialDefinition>[] materials;
    private final CgAssetRef<CgSequenceDefinition>[] sequences;
    private final CgAssetRef<CgStageDefinition>[] stages;
    /** The bundle's load, once the kit's scan has registered its ids; null before. */
    @Nullable
    private CgLoadHandle handle;
    @Nullable
    private String scanFailure;

    @SuppressWarnings("unchecked")
    private CgKitAssets(CgVfxSystem vfx, String namespace, Path kit, List<String> ids, List<String> materialIds,
                              List<String> sequenceIds, List<String> stageIds, @Nullable String shading) {
        if (!Files.isDirectory(kit)) throw new IllegalStateException("no " + namespace + " at " + kit.toAbsolutePath());
        this.ids = List.copyOf(ids);
        if (shading != null) CgShadingModels.registerKit(shading, kit);
        // Its textures and shaders read from its own folder, with no resource root holding assets/<namespace>
        CgIO.mount(namespace, kit);
        CgVfxSystemAssets.register();
        CgPrefabAssets.register();
        // Mounted on the process's assets, as a mod's: the world's cue manager routes the kit's cues from that registry
        CgVfxAssets assets = CgVfxAssets.instance();
        // The engine's world has its preparers; a scene's own system warms what it plays too
        if (vfx != CgVfx.world()) CgVfx.preparers(assets, () -> vfx);
        refs = new CgAssetRef[ids.size()];
        materials = new CgAssetRef[materialIds.size()];
        sequences = new CgAssetRef[sequenceIds.size()];
        stages = new CgAssetRef[stageIds.size()];
        // A namespace the game mounted is registered already; any other's thousands of files are read on the workers
        // while the loading screen draws, and declared once registered
        if (CgAssetMounts.folder(namespace) != null) {
            begin(assets, namespace, materialIds, sequenceIds, stageIds);
        } else {
            assets.registry().scanAsync(namespace, kit, CgAssetSource.DEV)
                    .then(() -> begin(assets, namespace, materialIds, sequenceIds, stageIds))
                    .onError(e -> scanFailure = "scanning " + namespace + ": " + e.detail());
        }
    }

    /** Its refs declared and their bundle's load started. */
    private void begin(CgVfxAssets assets, String namespace, List<String> materialIds, List<String> sequenceIds,
                       List<String> stageIds) {
        for (int i = 0; i < refs.length; i++) refs[i] = assets.declare(CgParticleKinds.SYSTEM, namespace + ":" + ids.get(i));
        for (int i = 0; i < materials.length; i++) materials[i] = assets.declare(CgLookKinds.MATERIAL, materialIds.get(i));
        for (int i = 0; i < sequences.length; i++) sequences[i] = assets.declare(CgSequenceKinds.SEQUENCE, sequenceIds.get(i));
        for (int i = 0; i < stages.length; i++) stages[i] = assets.declare(CgStageKinds.STAGE, stageIds.get(i));
        List<CgAssetRef<?>> all = new ArrayList<>(List.of(refs));
        all.addAll(List.of(materials));
        all.addAll(List.of(sequences));
        all.addAll(List.of(stages));
        handle = assets.load(all, CgLoadPriority.MOVE);
    }

    /** Starts loading {@code ids} of the kit at {@code kit} (its folder, holding {@code vfx/systems}). */
    public static CgKitAssets load(CgVfxSystem vfx, String namespace, Path kit, List<String> ids) {
        return new CgKitAssets(vfx, namespace, kit, ids, List.of(), List.of(), List.of(), null);
    }

    /**
     * As {@link #load(CgVfxSystem, String, Path, List)}, and {@code materialIds}, {@code sequenceIds} and {@code stageIds} too, after
     * registering the kit's {@code shading.yaml} under {@code shading} (the namespace its materials name their models by).
     */
    public static CgKitAssets load(CgVfxSystem vfx, String namespace, Path kit, List<String> ids, List<String> materialIds,
                                  List<String> sequenceIds, List<String> stageIds, String shading) {
        return new CgKitAssets(vfx, namespace, kit, ids, materialIds, sequenceIds, stageIds, shading);
    }

    /** Whether the kit's scan has registered it and its refs are declared: {@link #material} and the rest answer then. */
    public boolean declared() {
        return handle != null;
    }

    public boolean ready() {
        return handle != null && handle.ready();
    }

    public boolean failed() {
        return scanFailure != null || handle != null && handle.failed();
    }

    /** Each asset that failed, with why: what a scene prints when {@link #failed()}. */
    public String failures() {
        if (scanFailure != null) return scanFailure;
        List<String> why = new ArrayList<>();
        for (CgAssetRef<?> r : refs) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        for (CgAssetRef<?> r : materials) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        for (CgAssetRef<?> r : sequences) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        for (CgAssetRef<?> r : stages) if (r.failure() != null) why.add(String.valueOf(r.failure()));
        return String.join("\n  ", why);
    }

    public int size() {
        return refs.length;
    }

    public String id(int i) {
        return ids.get(i);
    }

    public CgAssetRef<CgVfxSystemDefinition> ref(int i) {
        return refs[i];
    }

    /** Material {@code i} of those asked for, in their order. */
    public CgAssetRef<CgMaterialDefinition> material(int i) {
        return materials[i];
    }

    /** Sequence {@code i} of those asked for, in their order. */
    public CgAssetRef<CgSequenceDefinition> sequence(int i) {
        return sequences[i];
    }

    /** Stage {@code i} of those asked for, in their order. */
    public CgAssetRef<CgStageDefinition> stage(int i) {
        return stages[i];
    }
}
