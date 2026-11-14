package com.crystalgraphics.api.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * What a clip carries besides its keys: its sync markers, its root motion, whether it is additive, and the notify
 * sequence that plays with it. Unreal's {@code UAnimSequence} settings, which a {@code clip} asset
 * ({@code clips/<clip id>.yaml}) writes and {@link CgClipDefinition} compiles.
 *
 * <pre>{@code
 * CgAnimSequence lunge = mesh.sequence("lunge").with(CgClipSettings.NONE.withRootMotion(CgRootMotion.of(CgRootMotion.Lock.REF_POSE)));
 * CgAnimSequence run = mesh.sequence("run").with(CgClipSettings.NONE.withMarkers(List.of(
 *         new CgSyncMarker("foot_l", 0.1f), new CgSyncMarker("foot_r", 0.5f))));
 * CgAnimSequence fromFile = mesh.sequence("lunge").with(clipDefinition.settings(id -> mesh.sequence(id)));
 * }</pre>
 *
 * @param markers    its sync markers, earliest first
 * @param rootMotion its root motion
 * @param additive   whether it is additive, against what
 * @param notifies   its notify sequence's id, or null for the default: the sequence registered under the clip's own id
 */
public record CgClipSettings(List<CgSyncMarker> markers, CgRootMotion rootMotion, CgAdditive additive, @Nullable String notifies) {

    /** Today's defaults: no markers, no root motion, not additive, the default notify sequence. */
    public static final CgClipSettings NONE = new CgClipSettings(List.of(), CgRootMotion.NONE, CgAdditive.NONE, null);

    public CgClipSettings {
        List<CgSyncMarker> sorted = new ArrayList<>(Objects.requireNonNull(markers, "markers"));
        sorted.sort(Comparator.comparingDouble(CgSyncMarker::time));
        markers = Collections.unmodifiableList(sorted);
        Objects.requireNonNull(rootMotion, "rootMotion");
        Objects.requireNonNull(additive, "additive");
    }

    public CgClipSettings withMarkers(List<CgSyncMarker> markers) {
        return new CgClipSettings(markers, rootMotion, additive, notifies);
    }

    public CgClipSettings withRootMotion(CgRootMotion rootMotion) {
        return new CgClipSettings(markers, rootMotion, additive, notifies);
    }

    public CgClipSettings withAdditive(CgAdditive additive) {
        return new CgClipSettings(markers, rootMotion, additive, notifies);
    }

    public CgClipSettings withNotifies(@Nullable String notifies) {
        return new CgClipSettings(markers, rootMotion, additive, notifies);
    }
}
