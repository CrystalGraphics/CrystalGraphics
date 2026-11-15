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
 * warp_windows: [ { target: opponent, start: 0.2, end: 0.9 } ]       # a rush that lands on its opponent
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
 * @param warpWindows    its warp windows
 */
public record CgClipDefinition(String id, List<CgSyncMarker> markers, CgRootMotion rootMotion, CgAdditive.Type additiveType,
                               CgAdditive.Base additiveBase, @Nullable String baseClip, int baseFrame, @Nullable String notifies,
                               List<CgWarpWindow> warpWindows) {

    public static final CgAssetCompiler<CgClipDefinition> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved());

    public CgClipDefinition {
        markers = Collections.unmodifiableList(new ArrayList<>(markers));
        warpWindows = List.copyOf(warpWindows);
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
        return new CgClipSettings(markers, rootMotion, additive, notifies, warpWindows);
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
        List<CgWarpWindow> windows = new ArrayList<>();
        if (tree.get("warp_windows") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) windows.add(window(id + ".warp_windows[" + i + "]", (Map<?, ?>) list.get(i)));
        }
        return new CgClipDefinition(id, markers, rootMotion, type, base, baseClip, baseFrame, notifies, windows);
    }

    private static CgWarpWindow window(String at, Map<?, ?> w) {
        if (!(w.get("target") instanceof String target)) throw new CgAssetException(at + ".target: a warp target's name");
        CgWarpWindow.Rotation method = CgWarpWindow.Rotation.valueOf(String.valueOf(w.get("method") == null ? "slerp" : w.get("method"))
                .toUpperCase(Locale.ROOT));
        return new CgWarpWindow(target, number(w, "start", 0f), number(w, "end", 0f), !Boolean.FALSE.equals(w.get("translation")),
                !Boolean.FALSE.equals(w.get("ignore_height")), !Boolean.FALSE.equals(w.get("rotation")), Boolean.TRUE.equals(w.get("facing")),
                method, number(w, "rotation_time", 1f), number(w, "max_rotation_rate", 0f), number(w, "max_speed_ratio", 0f),
                number(w, "yaw_offset", 0f));
    }

    private static float number(Map<?, ?> m, String key, float dflt) {
        return m.get(key) instanceof Number n ? n.floatValue() : dflt;
    }
}
