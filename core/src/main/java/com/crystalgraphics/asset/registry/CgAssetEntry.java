package com.crystalgraphics.asset.registry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetReferences;
import com.crystalgraphics.object.CgType;

/**
 * One asset the registry knows: Unreal's {@code FAssetData} with its package's dependency data. A YAML asset has a
 * kind and the references its file names; a binary (a glTF, a PNG, an FGA grid) has neither and keeps its folder
 * and extension in its id.
 *
 * <pre>{@code
 * CgAssetEntry e = registry.entry(CgParticleKinds.SYSTEM, "kakarot_kit:Art/EFF/e/skill/a/Cpl001/Particles/PS_skill_cpl001_kameha_bullet_01_00_LP");
 * e.kind();       // crystalgraphics:system
 * e.source();     // DEV
 * e.file();       // .../assets/kakarot_kit/vfx/systems/Art/EFF/.../PS_skill_cpl001_kameha_bullet_01_00_LP.yaml
 *
 * registry.binary("kakarot_kit:textures/Art/EFF/e/cmn/Textures/T_cmn_noise_03_00_E.png").binary();   // true
 * }</pre>
 *
 * <ul>
 *   <li>An id is unique within its kind: a cue and a sequence may share one.</li>
 *   <li>A redirect's entry is the old id with {@link #redirect()} set; {@link CgAssetRegistry#resolve} follows it.</li>
 *   <li>Immutable: a changed file is a new entry, announced as {@code CHANGED}.</li>
 * </ul>
 *
 * @param id         {@code namespace:path}: a YAML asset's path without its kind's folder or extension, a binary's with
 *                   both
 * @param kind       its kind; null for a binary
 * @param source     where its bytes come from
 * @param file       its file; null for a builder's asset
 * @param redirect   the id a moved asset now has; null for any other
 * @param definition a builder's asset's definition, made when it is first compiled; null for a file's, or a builder's
 *                   tree
 * @param references the assets its file names, hard and soft, its parent first: Unreal's package dependencies; empty
 *                   for a binary or a builder's asset
 * @param modified   its file's modification time, in milliseconds; 0 for a builder's asset
 * @param searchable its kind's {@code SEARCHABLE} properties as its file writes them, read by the scan without loading
 *                   it (Unreal's {@code FAssetData} tags): a cue's {@code tag}; empty for a binary or a builder's asset
 */
public record CgAssetEntry(String id, @Nullable CgType kind, CgAssetSource source, @Nullable Path file,
        @Nullable String redirect, @Nullable Supplier<?> definition, List<CgAssetReferences.Reference> references,
        long modified, Map<String, String> searchable) {

    /** The namespace before the id's colon. */
    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    /** Whether it is a binary rather than a YAML asset of a kind. */
    public boolean binary() {
        return kind == null;
    }
}
