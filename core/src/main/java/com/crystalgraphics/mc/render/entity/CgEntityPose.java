package com.crystalgraphics.mc.render.entity;

import java.util.Objects;

/**
 * How an entity is posed where it is drawn: its body's yaw, its head's yaw and pitch and a tilt of the whole model, in
 * degrees from facing the viewer, and what its model animates from, the walk cycle's speed and the arm's swing. What
 * Minecraft's own renderer reads, set for one draw and put back after, so a live entity keeps its own.
 *
 * <pre>{@code
 * CgEntityPose.FRONT                                        // facing the viewer
 * CgEntityPose.facing(30, 0)                                // body and head turned 30 degrees
 * CgEntityPose.FRONT.body(20).head(45, -10)                 // the head turned past the body, looking up
 * CgEntityPose.followingPointer(pointerX - midX, pointerY - midY)   // the inventory's: looks at the pointer
 * }</pre>
 *
 * Its model's own animation, from inputs Minecraft's {@code setupAnim} reads:
 *
 * <pre>{@code
 * pose.walking(0.6f)          // the walk cycle at 0.6 of a block a tick, legs and arms swinging
 * pose.swinging(0.3f)         // an arm 0.3 through its swing
 * pose.ownMotion()            // walk and swing back to the entity's own
 * a.lerp(b, t)                // between two poses: a tween, a turntable
 * }</pre>
 *
 * <ul>
 *   <li>Positive yaw turns the entity to the viewer's left, as the inventory turns it toward a pointer on that side;
 *       positive pitch looks down, as Minecraft's does.</li>
 *   <li>{@link #walkSpeed} and {@link #swing} are NaN for the entity's own: a live entity walks as it walks. A walking
 *       pose's cycle is where its speed has carried it over the picture's age, as Minecraft advances it each tick.</li>
 *   <li>Crouching, swimming and sleeping are the entity's own state, which resizes it: set them on an entity of yours
 *       in a {@code CgEntityHook}.</li>
 * </ul>
 */
public record CgEntityPose(float bodyYaw, float headYaw, float pitch, float tilt, float walkSpeed, float swing) {

    /** Facing the viewer, moving as the entity moves. */
    public static final CgEntityPose FRONT = new CgEntityPose(0, 0, 0, 0, Float.NaN, Float.NaN);

    /**
     * Looking at a pointer {@code dx, dy} units from the middle of the entity's box (right and down positive), as the
     * inventory's player does: vanilla's {@code renderEntityInInventoryFollowsMouse} angles, ported.
     */
    public static CgEntityPose followingPointer(float dx, float dy) {
        return FRONT.following(dx, dy);
    }

    /** Body and head turned {@code yaw}, the head pitched. */
    public static CgEntityPose facing(float yaw, float pitch) {
        return new CgEntityPose(yaw, yaw, pitch, 0, Float.NaN, Float.NaN);
    }

    /** This pose's angles replaced by the pointer's of {@link #followingPointer}, its motion kept. */
    public CgEntityPose following(float dx, float dy) {
        double turn = Math.atan(-dx / 40.0), lift = Math.atan(-dy / 40.0);
        // + 0f: no -0.0, so a pointer on the middle equals FRONT
        return new CgEntityPose((float) (turn * 20) + 0f, (float) (turn * 40) + 0f, (float) (-lift * 20) + 0f,
                (float) (lift * 20) + 0f, walkSpeed, swing);
    }

    public CgEntityPose body(float yaw) {
        return new CgEntityPose(yaw, headYaw, pitch, tilt, walkSpeed, swing);
    }

    public CgEntityPose head(float yaw, float pitch) {
        return new CgEntityPose(bodyYaw, yaw, pitch, tilt, walkSpeed, swing);
    }

    /** The whole model tilted toward the viewer, as the inventory tilts its player toward a pointer above it. */
    public CgEntityPose tilt(float degrees) {
        return new CgEntityPose(bodyYaw, headYaw, pitch, degrees, walkSpeed, swing);
    }

    /** The walk cycle at {@code blocksPerTick}; 0 stands still. */
    public CgEntityPose walking(float blocksPerTick) {
        return new CgEntityPose(bodyYaw, headYaw, pitch, tilt, Math.max(0, blocksPerTick), swing);
    }

    /** An arm {@code progress} through its swing, 0 to 1. */
    public CgEntityPose swinging(float progress) {
        return new CgEntityPose(bodyYaw, headYaw, pitch, tilt, walkSpeed, Math.max(0, Math.min(1, progress)));
    }

    /** Walk and swing as the entity's own. */
    public CgEntityPose ownMotion() {
        return new CgEntityPose(bodyYaw, headYaw, pitch, tilt, Float.NaN, Float.NaN);
    }

    public boolean ownWalk() {
        return Float.isNaN(walkSpeed);
    }

    public boolean ownSwing() {
        return Float.isNaN(swing);
    }

    /**
     * Between this pose at {@code t = 0} and {@code to} at 1: each angle the short way round, walk and swing straight,
     * the entity's own where either side has it.
     */
    public CgEntityPose lerp(CgEntityPose to, float t) {
        Objects.requireNonNull(to, "to");
        return new CgEntityPose(angle(bodyYaw, to.bodyYaw, t), angle(headYaw, to.headYaw, t), angle(pitch, to.pitch, t),
                angle(tilt, to.tilt, t), walkSpeed + (to.walkSpeed - walkSpeed) * t, swing + (to.swing - swing) * t);
    }

    private static float angle(float a, float b, float t) {
        float d = ((b - a) % 360 + 540) % 360 - 180;
        return a + d * t;
    }

    /**
     * What {@link #toString} writes, read back: {@code front}, or any of {@code body <deg>}, {@code head <yaw> <pitch>},
     * {@code tilt <deg>}, {@code walk <speed>}, {@code swing <progress>} in any order, the rest at {@link #FRONT}'s.
     *
     * <pre>{@code
     * CgEntityPose.parse("body 30 head 45 -10 walk 0.6");
     * }</pre>
     *
     * @throws IllegalArgumentException for a word that is none of these, or a missing number
     */
    public static CgEntityPose parse(String text) {
        String[] w = Objects.requireNonNull(text, "text").trim().split("\\s+");
        if (w.length == 1 && (w[0].isEmpty() || w[0].equals("front"))) return FRONT;
        CgEntityPose p = FRONT;
        for (int i = 0; i < w.length; i++) {
            switch (w[i]) {
                case "body": p = p.body(number(w, ++i)); break;
                case "head": p = p.head(number(w, ++i), number(w, ++i)); break;
                case "tilt": p = p.tilt(number(w, ++i)); break;
                case "walk": p = p.walking(number(w, ++i)); break;
                case "swing": p = p.swinging(number(w, ++i)); break;
                default: throw new IllegalArgumentException("not a pose word: " + w[i] + " in '" + text + "'");
            }
        }
        return p;
    }

    private static float number(String[] w, int i) {
        if (i >= w.length) throw new IllegalArgumentException("a number is missing after '" + w[i - 1] + "'");
        try {
            return Float.parseFloat(w[i]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + w[i]);
        }
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        if (bodyYaw != 0) b.append(" body ").append(num(bodyYaw));
        if (headYaw != 0 || pitch != 0) b.append(" head ").append(num(headYaw)).append(' ').append(num(pitch));
        if (tilt != 0) b.append(" tilt ").append(num(tilt));
        if (!ownWalk()) b.append(" walk ").append(num(walkSpeed));
        if (!ownSwing()) b.append(" swing ").append(num(swing));
        return b.length() == 0 ? "front" : b.substring(1);
    }

    private static String num(float f) {
        return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
    }
}
