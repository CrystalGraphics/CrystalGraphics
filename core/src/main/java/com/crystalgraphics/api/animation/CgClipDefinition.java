package com.crystalgraphics.api.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A {@code clip} asset compiled: a clip's settings as {@code clips/<the clip's id path>.yaml} writes them, Unreal's
 * {@code UAnimSequence} properties. Whoever resolves a clip id to its keys applies it, as a mesh takes a skeleton
 * asset's sockets.
 *
 * <pre>{@code
 * CgClipDefinition.register();                                            // once, at init
 * CgAssetLoad load = pipeline.request(CgBodyKinds.CLIP, "kakarot:Art/ANM/cpl/C001A/FLD/C001A_FLD_Run01_02LP");
 * CgClipDefinition clip = (CgClipDefinition) load.definition();
 * CgAnimSequence run = keys.with(clip.settings(id -> provider.clip(id)));   // its base clip, if any, by id
 * }</pre>
 *
 * <pre>{@code
 * # clips/mymod/hero/lunge.yaml
 * _v: 1
 * root_motion: { enabled: true, lock: ref_pose }
 * sync_markers: [ { name: foot_l, at: 0.1 }, { name: foot_r, at: 0.45 } ]
 * }</pre>
 *
 * <ul>
 *   <li>A clip with no file keeps {@link CgClipSettings#NONE}.</li>
 *   <li>{@code additive.type: mesh_space} is an aim offset's sample: the aim offset node plays it.</li>
 *   <li>{@code base: clip_frame} names its clip in {@code base_clip}; {@link #settings} fails naming it when the
 *       resolver has none.</li>
 * </ul>
 *
 * @param id             its asset id: the clip's id
 * @param markers        its sync markers
 * @param rootMotion     its root motion
 * @param additiveType   whether it is additive
 * @param additiveBase   against what
 * @param baseClip       {@code clip_frame}'s clip id, else null
 * @param baseFrame      the base frame
 * @param notifies       its notify sequence's id, else null for the default
 */
public record CgClipDefinition(String id, List<CgSyncMarker> markers, CgRootMotion rootMotion, CgAdditive.Type additiveType,
                               CgAdditive.Base additiveBase, @Nullable String baseClip, int baseFrame, @Nullable String notifies) {

    public static final CgAssetCompiler<CgClipDefinition> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved());

    public CgClipDefinition {
        markers = Collections.unmodifiableList(new ArrayList<>(markers));
    }

    /** Registers {@link #COMPILER} as the clip kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.CLIP, COMPILER);
    }

    /**
     * Its settings, {@code clips} resolving {@code base_clip} to the clip it names.
     *
     * @throws CgAssetException where a {@code clip_frame} base's clip does not resolve
     */
    public CgClipSettings settings(Function<String, CgAnimSequence> clips) {
        CgAdditive additive = CgAdditive.NONE;
        if (additiveType != CgAdditive.Type.NONE) {
            CgAnimSequence base = null;
            if (additiveBase == CgAdditive.Base.CLIP_FRAME) {
                base = clips.apply(baseClip);
                if (base == null) throw new CgAssetException(id + ": additive base_clip " + baseClip + " names no clip");
            }
            additive = new CgAdditive(additiveType, additiveBase, base, baseFrame);
        }
        return new CgClipSettings(markers, rootMotion, additive, notifies);
    }

    /** {@code tree}'s settings. */
    public static CgClipDefinition compile(String id, Map<?, ?> tree) {
        List<CgSyncMarker> markers = new ArrayList<>();
        if (tree.get("sync_markers") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                markers.add(new CgSyncMarker(String.valueOf(m.get("name")), ((Number) m.get("at")).floatValue()));
            }
        }
        CgRootMotion rootMotion = CgRootMotion.NONE;
        if (tree.get("root_motion") instanceof Map<?, ?> r) {
            rootMotion = new CgRootMotion(Boolean.TRUE.equals(r.get("enabled")),
                    r.get("root_bone") == null ? null : String.valueOf(r.get("root_bone")),
                    CgRootMotion.Lock.valueOf(String.valueOf(r.get("lock") == null ? "ref_pose" : r.get("lock")).toUpperCase(Locale.ROOT)),
                    Boolean.TRUE.equals(r.get("force_root_lock")));
        }
        CgAdditive.Type type = CgAdditive.Type.NONE;
        CgAdditive.Base base = CgAdditive.Base.REF_POSE;
        String baseClip = null;
        int baseFrame = 0;
        if (tree.get("additive") instanceof Map<?, ?> a) {
            type = CgAdditive.Type.valueOf(String.valueOf(a.get("type") == null ? "none" : a.get("type")).toUpperCase(Locale.ROOT));
            base = CgAdditive.Base.valueOf(String.valueOf(a.get("base") == null ? "ref_pose" : a.get("base")).toUpperCase(Locale.ROOT));
            baseClip = a.get("base_clip") == null ? null : String.valueOf(a.get("base_clip"));
            baseFrame = a.get("base_frame") instanceof Number n ? n.intValue() : 0;
            if (type != CgAdditive.Type.NONE && base == CgAdditive.Base.CLIP_FRAME && baseClip == null) {
                throw new CgAssetException(id + ": additive base clip_frame names no base_clip");
            }
        }
        String notifies = tree.get("notifies") == null ? null : String.valueOf(tree.get("notifies"));
        return new CgClipDefinition(id, markers, rootMotion, type, base, baseClip, baseFrame, notifies);
    }
}
