package com.crystalgraphics.vfx.look;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgKitFiles;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.render.post.settings.CgPostBlock;
import com.crystalgraphics.render.post.settings.CgPostSettings;

/**
 * A kit's own look, its {@code look.yaml}: the game's tonemapper, grade and bloom its effects were authored under, and
 * the exposure of its own draws. Read as the kit registers; a system of the kit takes both
 * ({@code CgVfxSystemInstance}): its light is graded by the kit's post apart from the frame ({@code CgVfxEffect.grade}),
 * and only what it adds is laid over the frame, so the host's world keeps its own look.
 *
 * <pre>{@code
 * CgVfxKitLook look = CgVfxKitLook.of("spzero_kit");                  // null: the kit has no look of its own
 * VFX.play(new CgVfxSystemInstance<>(ref, x, y, z).kitLook(null));    // a play under the default look instead
 * }</pre>
 *
 * <ul>
 *   <li>Only its tonemapper, grade and bloom are applied: a vignette, flash or impact frame in its post would grade the
 *       whole screen and is left out.</li>
 *   <li>It needs the HDR scene: without it the kit's draws keep the frame's look, its exposure still theirs.</li>
 *   <li>A file that does not validate throws as the kit registers, naming the file.</li>
 * </ul>
 *
 * @param settings the {@code post} block
 * @param exposure {@code effect_exposure}: what the kit's draws' light is scaled by
 */
public record CgVfxKitLook(CgPostSettings settings, float exposure) {

    /** The file at a kit's root. */
    public static final String FILE = "look.yaml";
    private static final Map<String, CgVfxKitLook> BY_KIT = new ConcurrentHashMap<>();
    /** A kit's look with post materials, by the look and the materials' ids. */
    private static final Map<List<Object>, CgPostSettings> WITH_POST = new ConcurrentHashMap<>();

    /** The look of the kit {@code namespace}; null for a kit without one, or none. */
    @Nullable
    public static CgVfxKitLook of(@Nullable String namespace) {
        return namespace == null ? null : BY_KIT.get(namespace);
    }

    /**
     * The grade of the kit {@code namespace}'s look, for a primitive of the kit: its pixels toned by the kit's post
     * alone, the world around it keeping the frame's look. Null for a kit without one.
     *
     * <pre>{@code
     * goku.grade(CgVfxKitLook.grade("kk"));   // a kit character graded as its game grades it
     * }</pre>
     */
    @Nullable
    public static CgPostSettings grade(@Nullable String namespace) {
        CgVfxKitLook look = of(namespace);
        return look == null ? null : look.settings();
    }

    /**
     * The kit {@code namespace}'s look with {@code post}, the kit's post materials drawn on the primitive alone (a
     * stage's character colour), made once for each kit and list. The kit's look alone where {@code post} is empty; null
     * for a kit without a look.
     *
     * <pre>{@code
     * List<CgMaterialDefinition> stage = List.of(kit.material(chara).get());   // kk:Art/BG/Area02/PostProcess/Area02_CharaColor
     * goku.grade(CgVfxKitLook.grade("kk", stage));                              // toned by Kakarot, coloured by its arena
     * }</pre>
     *
     * <ul>
     *   <li>Each material draws at its file's {@code Location}; only {@code SURFACES} ones reach the primitive.</li>
     *   <li>Leave out a material that grades the whole screen (a gradient, a vignette): it is the stage's, not the
     *       primitive's.</li>
     * </ul>
     */
    @Nullable
    public static CgPostSettings grade(@Nullable String namespace, List<CgMaterialDefinition> post) {
        CgVfxKitLook look = of(namespace);
        if (look == null || post.isEmpty()) return look == null ? null : look.settings();
        List<Object> key = new ArrayList<>(post.size() + 1);
        key.add(look);
        for (CgMaterialDefinition m : post) key.add(m.id());
        return WITH_POST.computeIfAbsent(key, k -> {
            CgPostSettings settings = look.settings().copy();
            for (CgMaterialDefinition m : post) settings.blendable(CgMaterial.of(m).instance(), 1f);
            return settings;
        });
    }

    /** Reads {@code folder}'s look as the asset registry registers the kit. */
    public static void registerKit(String namespace, Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            BY_KIT.remove(namespace);
            return;
        }
        BY_KIT.put(namespace, read(file));
    }

    /** {@code file}, validated against {@link CgKitFiles#LOOK}. */
    public static CgVfxKitLook read(Path file) {
        Map<?, ?> root;
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> m)) throw new IllegalArgumentException(file + ": expected a map");
            root = m;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.LOOK, root, CgValidation.Usecase.LOAD);
        if (!v.valid()) throw new IllegalArgumentException(file + ": " + v.issues());
        return new CgVfxKitLook(CgPostBlock.settings((Map<?, ?>) root.get("post")), number(root.get("effect_exposure"), 1f));
    }

    private static float number(@Nullable Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }
}
