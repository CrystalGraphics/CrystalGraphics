package com.crystalgraphics.asset.kind;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.crystalgraphics.object.CgType;

/**
 * The format's 20 asset kinds ({@code docs/vfx/assets.md} § The kinds), each declared once with its structs, elements
 * and dispatch families, read by the codec, validation, the editor and replication alike.
 *
 * <pre>{@code
 * CgAssetKinds.register();                                    // at init, both sides: declares every kind
 * CgType kind = CgAssetKinds.byFolder("vfx/systems");          // the system kind: a file's kind is its folder
 * for (CgType k : CgAssetKinds.all()) k.id();                 // crystalgraphics:system, …
 * }</pre>
 *
 * <ul>
 *   <li>Register before any asset loads, on both sides: a dedicated server declares every kind too.</li>
 *   <li>A mod adds members to the families here (renderers, tracks, camera nodes…) through
 *       {@link CgType#member(String, CgType)}, before {@code CgTypes.freeze()}.</li>
 * </ul>
 */
public final class CgAssetKinds {

    private CgAssetKinds() {
    }

    private static volatile List<CgType> all;

    /** Declares every kind and its structs in the engine's registry. Safe to call more than once. */
    public static synchronized void register() {
        if (all != null) return;
        CgCommonStructs.register();
        CgCameraKinds.register();
        CgParticleKinds.register();
        CgLookKinds.register();
        CgBodyKinds.register();
        CgSequenceKinds.register();
        CgGameplayKinds.register();
        CgFieldKinds.register();
        CgModuleTypes.register();
        all = Collections.unmodifiableList(Arrays.asList(
                CgParticleKinds.SYSTEM, CgParticleKinds.EMITTER, CgParticleKinds.MODULE, CgParticleKinds.EFFECT_TYPE,
                CgParticleKinds.ENDING, CgFieldKinds.FIELD, CgLookKinds.MATERIAL, CgLookKinds.TEXTURE, CgLookKinds.MESH,
                CgLookKinds.PARAMETERS, CgLookKinds.CURVE_KIND, CgBodyKinds.PREFAB, CgBodyKinds.SKELETON,
                CgBodyKinds.SPRINGS, CgBodyKinds.RIG, CgBodyKinds.RETARGETER, CgSequenceKinds.SEQUENCE,
                CgCameraKinds.CAMERA_RIG, CgCameraKinds.SHAKE, CgGameplayKinds.CUE));
    }

    /** The 20 kinds, particles first. */
    public static List<CgType> all() {
        register();
        return all;
    }

    /** The kind whose folder is {@code folder} ({@code vfx/systems}), or null. */
    public static CgType byFolder(String folder) {
        for (CgType kind : all()) {
            if (kind.folder().equals(folder)) return kind;
        }
        return null;
    }
}
