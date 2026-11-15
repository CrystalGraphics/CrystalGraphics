package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * A point a control reaches for: a location in a {@link CgBoneSpace}, relative to a bone where the space is a bone's,
 * each coordinate a constant or a float variable. Unreal's effector and joint target (a location, its
 * {@code EBoneControlSpace} and an {@code FBoneSocketTarget}).
 *
 * <pre>{@code
 * CgIkTarget.world(FOOT_X, FOOT_Y, FOOT_Z);                       // a point in the world the game sets each frame
 * CgIkTarget.of(CgBoneSpace.BONE, "calf_l", 0f, 0f, 0.4f);       // ahead of the knee, in its own space
 * CgIkTarget.of(CgBoneSpace.COMPONENT, null, 0.2f, 1f, 0.5f);     // a fixed point in the skeleton's space
 * }</pre>
 */
public final class CgIkTarget {

    private final CgBoneSpace space;
    @Nullable
    private final String bone;
    private final float[] constants;
    private final CgAnimVariable<Float>[] variables;

    @SuppressWarnings("unchecked")
    private CgIkTarget(CgBoneSpace space, @Nullable String bone, float x, float y, float z, @Nullable CgAnimVariable<Float> vx,
                       @Nullable CgAnimVariable<Float> vy, @Nullable CgAnimVariable<Float> vz) {
        this.space = Objects.requireNonNull(space, "space");
        this.bone = bone;
        this.constants = new float[]{x, y, z};
        this.variables = new CgAnimVariable[]{vx, vy, vz};
        if ((space == CgBoneSpace.BONE || space == CgBoneSpace.PARENT_BONE) && bone == null) {
            throw new IllegalArgumentException("a target in " + space + " names its bone");
        }
    }

    public static CgIkTarget of(CgBoneSpace space, @Nullable String bone, float x, float y, float z) {
        return new CgIkTarget(space, bone, x, y, z, null, null, null);
    }

    /** Its coordinates from variables; a null one is 0. */
    public static CgIkTarget of(CgBoneSpace space, @Nullable String bone, @Nullable CgAnimVariable<Float> x,
                                @Nullable CgAnimVariable<Float> y, @Nullable CgAnimVariable<Float> z) {
        return new CgIkTarget(space, bone, 0f, 0f, 0f, x, y, z);
    }

    /** A point in the world, its coordinates from variables. */
    public static CgIkTarget world(CgAnimVariable<Float> x, CgAnimVariable<Float> y, CgAnimVariable<Float> z) {
        return of(CgBoneSpace.WORLD, null, x, y, z);
    }

    public CgBoneSpace space() {
        return space;
    }

    /** Its bone in {@code skeleton}, or -1 for none; failing by name where the skeleton lacks it. */
    int bone(CgSkeleton skeleton, Object node) {
        return bone == null ? -1 : CgSkeletalControlNode.bone(skeleton, bone, node);
    }

    /** Its transform in the skeleton's space, into {@code out}: {@code GetTargetTransform}. */
    Matrix4f resolve(CgAnimGraphInstance g, CgAnimPose pose, int bone, Matrix4f out) {
        out.translation(value(g, 0), value(g, 1), value(g, 2));
        return space.toComponent(out, pose, bone, g.placement());
    }

    private float value(CgAnimGraphInstance g, int c) {
        return variables[c] != null ? g.get(variables[c]) : constants[c];
    }

    void reads(Consumer<CgAnimVariable<?>> each) {
        for (CgAnimVariable<Float> v : variables) if (v != null) each.accept(v);
    }

    /** Its data form: {@code { space, bone, location: [x, y, z] }}, a coordinate a number or a variable's name. */
    Map<String, Object> tree() {
        Map<String, Object> out = new HashMap<>();
        out.put("space", space.id());
        if (bone != null) out.put("bone", bone);
        List<Object> location = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) location.add(variables[c] != null ? variables[c].name() : constants[c]);
        out.put("location", location);
        return out;
    }
}
