package com.crystalgraphics.vfx.look;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgKitFiles;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.volume.CgPostBlock;
import com.crystalgraphics.render.post.volume.CgPostSettings;

/**
 * A kit's own look, its {@code look.yaml}: the exposure of its own draws, and the post its game grades them with (the
 * game's tonemapper, bloom and grading). Read as the kit registers; a system of the kit takes its exposure
 * ({@code CgVfxSystemInstance}).
 *
 * <pre>{@code
 * CgVfxKitLook look = CgVfxKitLook.of("spzero_kit");                  // null: the kit has no look of its own
 * VFX.play(new CgVfxSystemInstance<>(ref, x, y, z).kitLook(null));    // a play under the default look instead
 * }</pre>
 *
 * <ul>
 *   <li>Its post is read and validated but not yet applied: a screen-wide volume graded the host's world and every other
 *       kit's effects with it, so the frame keeps the engine's look.</li>
 *   <li>A file that does not validate throws as the kit registers, naming the file.</li>
 * </ul>
 *
 * @param settings the {@code post} block
 * @param exposure {@code effect_exposure}: what the kit's draws' light is scaled by
 * @param radius   the volume's radius, in blocks
 * @param blend    how far past it the volume fades out, in blocks
 */
public record CgVfxKitLook(CgPostSettings settings, float exposure, float radius, float blend) {

    /** The file at a kit's root. */
    public static final String FILE = "look.yaml";
    private static final Map<String, CgVfxKitLook> BY_KIT = new ConcurrentHashMap<>();

    /** The look of the kit {@code namespace}; null for a kit without one, or none. */
    @Nullable
    public static CgVfxKitLook of(@Nullable String namespace) {
        return namespace == null ? null : BY_KIT.get(namespace);
    }

    /** Reads {@code folder}'s look, its lengths in {@code units}, as the asset registry registers the kit. */
    public static void registerKit(String namespace, Path folder, CgUnits units) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            BY_KIT.remove(namespace);
            return;
        }
        BY_KIT.put(namespace, read(file, units));
    }

    /** {@code file}, validated against {@link CgKitFiles#LOOK}, its lengths from {@code units} into blocks. */
    public static CgVfxKitLook read(Path file, CgUnits units) {
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
        Map<?, ?> volume = (Map<?, ?>) root.get("volume");
        float metres = (float) units.metres();
        return new CgVfxKitLook(CgPostBlock.settings((Map<?, ?>) root.get("post")), number(root.get("effect_exposure"), 1f),
                number(volume.get("radius"), 0f) * metres, number(volume.get("blend"), 0f) * metres);
    }

    private static float number(@Nullable Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }
}
