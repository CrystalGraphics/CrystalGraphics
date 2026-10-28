package com.crystalgraphics.vfx.particle;

import java.util.List;

import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;

/**
 * Where an emitter spawns on its effect's skeleton: Cascade's {@code LocationBoneSocket}, the spec's
 * {@code socket_location}. Each spawn takes one of the named points, in turn or at random, and is placed at it plus
 * that point's offset. A name is a {@code CgEntityAttachments} point of the entity the effect follows, looked up as
 * written (a host or a rig declares them), or a {@code position} user parameter of the effect, read each step: a point
 * whoever plays it sets, as Niagara's position user parameters are.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("aura").sockets(new CgVfxSockets("socket", List.of("hand_r", "hand_l"),
 *         new float[][]{{0f, 0.1f, 0f}, {0f, 0.1f, 0f}}, true, false, 0f));
 * // sparks thrown with a quarter of the hand's velocity
 * new CgVfxSockets("socket", List.of("hand_r"), new float[][]{{0f, 0f, 0f}}, true, false, 0.25f);
 *
 * // a host names the points its model has
 * CgEntityAttachments.declare("hand_r", CgEntityAttachments.RIGHT_HAND);
 *
 * // between two points its player sets: user parameters point_0 and point_1 (bit 0 and bit 1)
 * new CgVfxSockets("socket", List.of("point_0", "point_1"), new float[][]{{0f, 0f, 0f}, {0f, 0f, 0f}}, false, false, 0f, 0b11);
 * effect.setPoint(POINT_0, x, y, z);
 * }</pre>
 *
 * <ul>
 *   <li>A point nothing answers spawns nothing, nor does any point of an effect that follows no entity: Unreal spawns
 *       nothing without its skeletal mesh ({@code ParticleModules_Location.cpp:1561-1567}). A user point answers
 *       while its parameter is set, so one still at its NaN default spawns nothing.</li>
 *   <li>A user point names a vector parameter of its effect's look: a system's asset fails at load on one it does not
 *       declare.</li>
 *   <li>In turn is the instance's spawn count round the points; at random, {@code trunc(r (n - 0.5))}, as Cascade
 *       draws it (:1449).</li>
 *   <li>An offset is in the effect's frame: the points carry no rotation yet.</li>
 *   <li>Following, a particle is put back at its point every step, wherever its velocity took it, as Cascade's
 *       {@code bUpdatePositionEachFrame} does (:1632-1686).</li>
 * </ul>
 *
 * @param element    its element's id: what its random pick is keyed by
 * @param names      the points, at most {@link #MAX}
 * @param offsets    each point's offset in blocks, the universal offset included
 * @param sequential in turn rather than at random
 * @param follow     whether its particles stay on their points as they move
 * @param inherit    the share of its point's velocity a particle is born with ({@code bInheritBoneVelocity} and
 *                   {@code InheritVelocityScale}): the point's move over the last step
 * @param users      a bit per point that is a user parameter, bit {@code i} for {@code names.get(i)}
 */
public record CgVfxSockets(String element, List<String> names, float[][] offsets, boolean sequential, boolean follow,
                           float inherit, int users) {

    /** The most points one emitter names: the kits' most is 24. */
    public static final int MAX = 32;

    public CgVfxSockets {
        names = List.copyOf(names);
        if (names.isEmpty() || names.size() > MAX) throw new IllegalArgumentException(element + ": " + names.size() + " points, not 1 to " + MAX);
        if (offsets.length != names.size()) throw new IllegalArgumentException(element + ": " + offsets.length + " offsets for " + names.size() + " points");
        if (names.size() < Integer.SIZE && users >>> names.size() != 0) throw new IllegalArgumentException(element + ": user bits past its " + names.size() + " points");
    }

    /** Points that are all the followed entity's. */
    public CgVfxSockets(String element, List<String> names, float[][] offsets, boolean sequential, boolean follow, float inherit) {
        this(element, names, offsets, sequential, follow, inherit, 0);
    }

    /** Whether point {@code i} is a user parameter. */
    public boolean user(int i) {
        return (users >>> i & 1) != 0;
    }

    /** What answers a point's place: the effect, from the entity it follows. */
    @FunctionalInterface
    public interface Source {
        /** Point {@code name}'s place in the world into {@code out}; false where nothing answers it. Render thread. */
        boolean point(String name, double[] out);
    }

    /** Its random pick's key ({@code CgVfxFormCompiler.key}). */
    public int key() {
        return CgVfxFormCompiler.key(element, "points");
    }

    /** The same points with every offset times {@code s}. */
    public CgVfxSockets scaled(float s) {
        float[][] o = new float[offsets.length][];
        for (int i = 0; i < o.length; i++) o[i] = new float[]{offsets[i][0] * s, offsets[i][1] * s, offsets[i][2] * s};
        return new CgVfxSockets(element, names, o, sequential, follow, inherit, users);
    }
}
