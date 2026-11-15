package com.crystalgraphics.api.animation;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Whether a clip is additive and against what: Unreal's {@code AdditiveAnimType} and {@code RefPoseType}. An additive
 * clip holds differences from a base pose, so it layers on top of whatever plays: a flinch over any pose, a breath over
 * a stance.
 *
 * <pre>{@code
 * CgAdditive.NONE;                                                   // an ordinary clip
 * CgAdditive.local(CgAdditive.Base.REF_POSE, null, 0);               // differences from the skeleton's rest pose
 * CgAdditive.local(CgAdditive.Base.FRAME, null, 0);                  // from this clip's own first frame
 * CgAdditive.local(CgAdditive.Base.CLIP_FRAME, idle, 12);            // from frame 12 of the idle clip
 * }</pre>
 *
 * <ul>
 *   <li>{@link Type#MESH_SPACE} is an aim offset's sample, played by the aim offset node over a base pose.</li>
 *   <li>A frame past the base clip's last is clamped, as Unreal clamps it ({@code GetSequencePose}).</li>
 * </ul>
 *
 * @param type      local or mesh space, or none
 * @param base      what the differences are from
 * @param baseClip  the clip {@link Base#CLIP_FRAME} reads, else null
 * @param baseFrame the frame {@link Base#FRAME} and {@link Base#CLIP_FRAME} read
 */
public record CgAdditive(Type type, Base base, @Nullable CgAnimSequence baseClip, int baseFrame) {

    /** An ordinary clip. */
    public static final CgAdditive NONE = new CgAdditive(Type.NONE, Base.REF_POSE, null, 0);

    /** Unreal's {@code EAdditiveAnimationType}. */
    public enum Type {
        /** Not additive. */
        NONE,
        /** {@code AAT_LocalSpaceBase}: differences per bone, local to its parent. */
        LOCAL_SPACE,
        /** {@code AAT_RotationOffsetMeshSpace}: rotations in the mesh's space, an aim offset's. */
        MESH_SPACE
    }

    /** Unreal's {@code EAdditiveBasePoseType}. */
    public enum Base {
        /** {@code ABPT_RefPose}: the skeleton's rest pose. */
        REF_POSE,
        /** {@code ABPT_LocalAnimFrame}: a frame of this clip. */
        FRAME,
        /** {@code ABPT_AnimFrame}: a frame of another clip. */
        CLIP_FRAME
    }

    public CgAdditive {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(base, "base");
        if (type != Type.NONE && base == Base.CLIP_FRAME && baseClip == null) {
            throw new IllegalArgumentException("an additive against another clip's frame names the clip");
        }
        if (baseFrame < 0) throw new IllegalArgumentException("additive base frame " + baseFrame);
    }

    /** A local-space additive against {@code base}. */
    public static CgAdditive local(Base base, @Nullable CgAnimSequence baseClip, int baseFrame) {
        return new CgAdditive(Type.LOCAL_SPACE, base, baseClip, baseFrame);
    }

    /** A mesh-space rotation additive against {@code base}: an aim offset's sample. */
    public static CgAdditive meshSpace(Base base, @Nullable CgAnimSequence baseClip, int baseFrame) {
        return new CgAdditive(Type.MESH_SPACE, base, baseClip, baseFrame);
    }

    public boolean isAdditive() {
        return type != Type.NONE;
    }
}
