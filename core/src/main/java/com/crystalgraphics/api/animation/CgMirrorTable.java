package com.crystalgraphics.api.animation;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which bone of a skeleton mirrors onto which, and across which axis: Unreal's {@code UMirrorDataTable}. A pose is
 * mirrored with {@link CgPose#mirror}; a clip authored for the right arm then plays on the left.
 *
 * <pre>{@code
 * CgMirrorTable mirror = CgMirrorTable.byName(skeleton, CgMirrorTable.Axis.X).build();   // r_/l_, _l/_r, right/left...
 * pose.mirror(mirror);
 * }</pre>
 *
 * <p>Pairs by hand, or Unreal's defaults with bones of your own mirrored onto themselves:</p>
 *
 * <pre>{@code
 * CgMirrorTable gecko = CgMirrorTable.byName(skeleton, CgMirrorTable.Axis.X).self("body").self("model").build();
 * CgMirrorTable custom = CgMirrorTable.builder(skeleton, CgMirrorTable.Axis.X).pair("hand_a", "hand_b").build();
 * }</pre>
 *
 * <ul>
 *   <li>A bone in no pair and not mirrored onto itself is left as it is, as Unreal leaves a bone its table lacks: a
 *       centre bone {@link #byName} does not recognise needs {@link Builder#self}.</li>
 *   <li>Immutable; made once per skeleton and shared. Mirroring allocates nothing.</li>
 * </ul>
 */
public final class CgMirrorTable {

    /** The skeleton's axis a pose is mirrored across: its coordinate negated. */
    public enum Axis { X, Y, Z }

    /**
     * Unreal's default find-and-replace expressions, in order, the first match winning (AnimationSettings.cpp:31-44,
     * {@code UMirrorDataTable::GetMirrorName}): prefixes, suffixes, then infixes; the last maps centre bones onto
     * themselves.
     */
    private static final String[][] PREFIX = {
            {"r_", "l_"}, {"l_", "r_"}, {"R_", "L_"}, {"L_", "R_"}};
    private static final String[][] SUFFIX = {
            {"_l", "_r"}, {"_r", "_l"}, {"_R", "_L"}, {"_L", "_R"}};
    private static final String[][] PREFIX_WORD = {
            {"right", "left"}, {"left", "right"}, {"Right", "Left"}, {"Left", "Right"}};
    private static final String[][] INFIX = {
            {"_l_", "_r_"}, {"_r_", "_l_"}, {"_L_", "_R_"}, {"_R_", "_L_"},
            {"_left_", "_right_"}, {"_right_", "_left_"}, {"_Left_", "_Right_"}, {"_Right_", "_Left_"}};
    private static final Pattern CENTRE = Pattern.compile("((?:^[sS]pine|^[rR]oot|^[pP]elvis|^[nN]eck|^[hH]ead|^ik_hand_gun).*)");

    private final CgSkeleton skeleton;
    private final Axis axis;
    /** Per bone, the bone it mirrors with; -1 for none. */
    final int[] source;
    /** Per bone, its rest rotation in the skeleton's space (x, y, z, w): what mirroring rotates through. */
    final float[] restRotation;

    private CgMirrorTable(CgSkeleton skeleton, Axis axis, int[] source) {
        this.skeleton = skeleton;
        this.axis = axis;
        this.source = source;
        int n = skeleton.boneCount();
        restRotation = new float[n * 4];
        float[] rest = skeleton.rest();
        for (int b = 0; b < n; b++) {
            int at = b * CgSkeleton.REST + 3;
            int parent = skeleton.parent(b);
            if (parent < 0) {
                System.arraycopy(rest, at, restRotation, b * 4, 4);
            } else {
                mul(restRotation, parent * 4, rest, at, restRotation, b * 4);
            }
        }
    }

    /** An empty table to fill pair by pair. */
    public static Builder builder(CgSkeleton skeleton, Axis axis) {
        return new Builder(skeleton, axis);
    }

    /** Every bone paired by Unreal's default find-and-replace of its name; centre bones mirrored onto themselves. */
    public static Builder byName(CgSkeleton skeleton, Axis axis) {
        Builder b = new Builder(skeleton, axis);
        for (int bone = 0; bone < skeleton.boneCount(); bone++) {
            String name = skeleton.name(bone);
            String mirrored = mirrorName(name);
            if (mirrored == null) continue;
            int other = skeleton.indexOf(mirrored);
            if (other >= 0) b.pair(bone, other);
        }
        return b;
    }

    /** {@code name}'s mirror by Unreal's default expressions, or null where none matches. */
    static String mirrorName(String name) {
        for (String[] r : PREFIX) if (name.startsWith(r[0])) return r[1] + name.substring(r[0].length());
        for (String[] r : SUFFIX) if (name.endsWith(r[0])) return name.substring(0, name.length() - r[0].length()) + r[1];
        for (String[] r : PREFIX_WORD) if (name.startsWith(r[0])) return r[1] + name.substring(r[0].length());
        for (String[] r : PREFIX_WORD) if (name.endsWith(r[0])) return name.substring(0, name.length() - r[0].length()) + r[1];
        for (String[] r : INFIX) {
            int at = name.lastIndexOf(r[0]);
            if (at > 0 && at + r[0].length() < name.length()) return name.substring(0, at) + r[1] + name.substring(at + r[0].length());
        }
        Matcher m = CENTRE.matcher(name);
        return m.find() ? name : null;
    }

    public CgSkeleton skeleton() {
        return skeleton;
    }

    public Axis axis() {
        return axis;
    }

    /** The bone {@code bone} mirrors with: itself for a centre bone, -1 where it is left as it is. */
    public int mirrorOf(int bone) {
        return source[bone];
    }

    /** {@code out = a * b}, quaternions as (x, y, z, w). */
    static void mul(float[] a, int ai, float[] b, int bi, float[] out, int oi) {
        float ax = a[ai], ay = a[ai + 1], az = a[ai + 2], aw = a[ai + 3];
        float bx = b[bi], by = b[bi + 1], bz = b[bi + 2], bw = b[bi + 3];
        out[oi] = aw * bx + ax * bw + ay * bz - az * by;
        out[oi + 1] = aw * by - ax * bz + ay * bw + az * bx;
        out[oi + 2] = aw * bz + ax * by - ay * bx + az * bw;
        out[oi + 3] = aw * bw - ax * bx - ay * by - az * bz;
    }

    /** Fills a table pair by pair. */
    public static final class Builder {
        private final CgSkeleton skeleton;
        private final Axis axis;
        private final int[] source;

        private Builder(CgSkeleton skeleton, Axis axis) {
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
            this.axis = Objects.requireNonNull(axis, "axis");
            source = new int[skeleton.boneCount()];
            Arrays.fill(source, -1);
        }

        /** Bones {@code a} and {@code b} mirror onto each other; the same name twice mirrors one onto itself. */
        public Builder pair(String a, String b) {
            return pair(bone(a), bone(b));
        }

        /** Bone {@code name} mirrors onto itself: a centre bone. */
        public Builder self(String name) {
            int b = bone(name);
            return pair(b, b);
        }

        private Builder pair(int a, int b) {
            source[a] = b;
            source[b] = a;
            return this;
        }

        private int bone(String name) {
            int b = skeleton.indexOfIgnoringCase(name);
            if (b < 0) throw new IllegalArgumentException("mirror table: the skeleton has no bone " + name);
            return b;
        }

        public CgMirrorTable build() {
            return new CgMirrorTable(skeleton, axis, source.clone());
        }
    }
}
