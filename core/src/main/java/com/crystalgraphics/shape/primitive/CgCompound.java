package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Several shapes as one, each placed in the compound's own space: multi-part hitboxes, a set of hurtboxes, a block's
 * several boxes. A hit on it says which child it was ({@code hit.shape()}).
 *
 * <pre>{@code
 * static final CgShape HAMMER = CgCompound.builder()
 *         .add(CgCapsule.of(0.6, 0.1), 0, 0, 0.6)                       // the haft
 *         .add(CgBox.of(0.3, 0.2, 0.2), new CgPose().at(0, 0, 1.3))     // the head, at its end
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>The poses are copied as they are added: changing one afterwards changes nothing.</li>
 *   <li>Not convex, even when every child is: its support is the hull of them all.</li>
 * </ul>
 */
public final class CgCompound extends CgShape {

    private final CgShape[] shapes;
    private final CgPose[] poses;

    private CgCompound(CgShape[] shapes, CgPose[] poses) {
        this.shapes = shapes;
        this.poses = poses;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return shapes.length;
    }

    public CgShape shape(int i) {
        return shapes[i];
    }

    /** Child {@code i}'s place in the compound, copied into {@code out}. */
    public CgPose pose(int i, CgPose out) {
        return out.set(poses[i]);
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        double best = Double.NEGATIVE_INFINITY, bx = 0, by = 0, bz = 0;
        for (int i = 0; i < shapes.length; i++) {
            CgPose p = poses[i];
            shapes[i].support(p.m00() * dx + p.m10() * dy + p.m20() * dz, p.m01() * dx + p.m11() * dy + p.m21() * dz,
                    p.m02() * dx + p.m12() * dy + p.m22() * dz, out);
            double x = p.m00() * out.x + p.m01() * out.y + p.m02() * out.z + p.x;
            double y = p.m10() * out.x + p.m11() * out.y + p.m12() * out.z + p.y;
            double z = p.m20() * out.x + p.m21() * out.y + p.m22() * out.z + p.z;
            double dot = x * dx + y * dy + z * dz;
            if (dot > best) {
                best = dot;
                bx = x;
                by = y;
                bz = z;
            }
        }
        return out.set(bx, by, bz);
    }

    @Override
    public boolean isConvex() {
        return false;
    }

    @Override
    public String toString() {
        return "CgCompound[" + shapes.length + " shapes]";
    }

    public static final class Builder {

        private final List<CgShape> shapes = new ArrayList<>();
        private final List<CgPose> poses = new ArrayList<>();

        private Builder() {
        }

        public Builder add(CgShape shape, CgPose pose) {
            shapes.add(Objects.requireNonNull(shape, "shape"));
            poses.add(new CgPose(Objects.requireNonNull(pose, "pose")));
            return this;
        }

        /** A child moved, not turned. */
        public Builder add(CgShape shape, double x, double y, double z) {
            return add(shape, new CgPose().at(x, y, z));
        }

        /** A child at the compound's origin. */
        public Builder add(CgShape shape) {
            return add(shape, new CgPose());
        }

        /** @throws IllegalStateException with no child added */
        public CgCompound build() {
            if (shapes.isEmpty()) throw new IllegalStateException("a compound needs at least one shape");
            return new CgCompound(shapes.toArray(new CgShape[0]), poses.toArray(new CgPose[0]));
        }
    }
}
