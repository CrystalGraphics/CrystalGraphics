package com.crystalgraphics.asset.registry;

import java.nio.file.Path;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * One asset the registry knows, without its file read: Unreal's {@code FAssetData}. A YAML asset has a kind; a binary
 * (a glTF, a PNG, an FGA grid) has none and keeps its folder and extension in its id.
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
 */
@Value
@Accessors(fluent = true)
public class CgAssetEntry {

    /** {@code namespace:path}: a YAML asset's path without its kind's folder or extension, a binary's with both. */
    String id;
    /** Its kind; null for a binary. */
    @Nullable CgType kind;
    /** Where its bytes come from. */
    CgAssetSource source;
    /** Its file; null for a builder's asset. */
    @Nullable Path file;
    /** The id a moved asset now has; null for any other. */
    @Nullable String redirect;
    /** A builder's asset's definition, made when it is first compiled; null for a file's, or a builder's tree. */
    @Nullable Supplier<?> definition;

    /** The namespace before the id's colon. */
    public String namespace() {
        return id.substring(0, id.indexOf(':'));
    }

    /** Whether it is a binary rather than a YAML asset of a kind. */
    public boolean binary() {
        return kind == null;
    }
}
