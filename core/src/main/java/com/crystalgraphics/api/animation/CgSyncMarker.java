package com.crystalgraphics.api.animation;

import java.util.Objects;

/**
 * A named moment in a clip that clips of one sync group line up on: Unreal's {@code FAnimSyncMarker}. A run and a
 * walk both marking {@code foot_l} and {@code foot_r} blend with their feet in step, whatever their lengths.
 *
 * <pre>{@code
 * CgAnimSequence run = mesh.sequence("run").with(CgClipSettings.NONE.withMarkers(List.of(
 *         new CgSyncMarker("foot_l", 0.1f), new CgSyncMarker("foot_r", 0.5f))));
 * }</pre>
 *
 * <p>From files: a GeckoLib {@code timeline} key {@code sync=foot_l;}, a glTF animation's {@code extras.markers}
 * entry {@code { "name": "sync=foot_l", "time": 0.1 }}, or a {@code clip} asset's {@code sync_markers}.</p>
 *
 * @param name what clips share: a group syncs on the names every member has
 * @param time seconds from the clip's start, within its length
 */
public record CgSyncMarker(String name, float time) {

    public CgSyncMarker {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("a sync marker has a name");
        if (!(time >= 0f) || Float.isInfinite(time)) throw new IllegalArgumentException("sync marker " + name + " at " + time);
    }
}
