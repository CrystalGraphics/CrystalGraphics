package com.crystalgraphics.world;

import com.crystalgraphics.platform.service.CgEntityQuery;

/**
 * Points on an entity an effect attaches to — its feet, chest, head, eyes and hands — worked out from a
 * {@link CgEntityQuery#pose}: its box, eye height, body yaw and look, in the proportions of Minecraft's humanoid model.
 * The same on every host, since no host is asked for a model part.
 *
 * <pre>{@code
 * entities.pose(me, frame.partialTick(), pose);
 * CgEntityAttachments.point(CgEntityAttachments.HANDS_FORWARD, pose, at);   // where a two-handed beam leaves
 * CgEntityAttachments.forward(pose, aim);                                  // and the way the entity looks
 * }</pre>
 *
 * <ul>
 *   <li>Absolute world coordinates, into a {@code double[3]} the caller keeps.</li>
 *   <li>Hands are where a player's are drawn: at the sides, or held out along the look for the {@code _FORWARD} points.
 *       A mob of another shape gets the same proportions of its own box, which is close for anything upright.</li>
 * </ul>
 */
public final class CgEntityAttachments {

    public static final int FEET = 0, CENTRE = 1, CHEST = 2, HEAD = 3, EYES = 4, RIGHT_HAND = 5, LEFT_HAND = 6,
            RIGHT_HAND_FORWARD = 7, LEFT_HAND_FORWARD = 8, HANDS_FORWARD = 9;

    // A player's model, as shares of its height: shoulders at 22 of 32 pixels, 5 out from the middle, the hand 11 below.
    private static final double SHOULDER = 22.0 / 32.0, SHOULDER_OUT = 5.0 / 32.0, ARM = 11.0 / 32.0;

    private CgEntityAttachments() {
    }

    /** The attachment point {@code which} of the entity posed by {@code pose}, into {@code out}. */
    public static double[] point(int which, double[] pose, double[] out) {
        double x = pose[CgEntityQuery.X], y = pose[CgEntityQuery.Y], z = pose[CgEntityQuery.Z];
        double h = pose[CgEntityQuery.HEIGHT];
        switch (which) {
            case FEET: return set(out, x, y, z);
            case CENTRE: return set(out, x, y + h * 0.5, z);
            case CHEST: return set(out, x, y + h * 0.6, z);
            case HEAD: return set(out, x, y + Math.min(pose[CgEntityQuery.EYE_HEIGHT] + h * 0.03, h), z);
            case EYES: return set(out, x, y + pose[CgEntityQuery.EYE_HEIGHT], z);
            default: break;
        }
        // The body's right, from its yaw: yaw 0 faces +z, so its right hand is toward -x.
        double body = Math.toRadians(pose[CgEntityQuery.BODY_YAW]);
        double rightX = -Math.cos(body), rightZ = -Math.sin(body);
        double side = which == LEFT_HAND || which == LEFT_HAND_FORWARD ? -1.0 : which == HANDS_FORWARD ? 0.0 : 1.0;
        double sx = x + rightX * SHOULDER_OUT * h * side, sy = y + SHOULDER * h, sz = z + rightZ * SHOULDER_OUT * h * side;
        if (which == RIGHT_HAND || which == LEFT_HAND) return set(out, sx, sy - ARM * h, sz);
        // Held out along the look; both hands meet a little lower and nearer, in front of the chest.
        forward(pose, out);
        double reach = which == HANDS_FORWARD ? ARM * h * 0.9 : ARM * h, drop = which == HANDS_FORWARD ? h * 0.06 : 0.0;
        return set(out, sx + out[0] * reach, sy - drop + out[1] * reach, sz + out[2] * reach);
    }

    /** The way the entity looks, a unit vector, into {@code out}. */
    public static double[] forward(double[] pose, double[] out) {
        double yaw = Math.toRadians(pose[CgEntityQuery.YAW]), pitch = Math.toRadians(pose[CgEntityQuery.PITCH]);
        return set(out, -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    private static double[] set(double[] out, double x, double y, double z) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        return out;
    }
}
