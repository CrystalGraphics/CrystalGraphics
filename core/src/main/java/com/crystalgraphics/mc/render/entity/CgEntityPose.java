package com.crystalgraphics.mc.render.entity;

/**
 * How an entity picture turns its entity, in degrees from facing the viewer: the body's yaw, the head's, the head's
 * pitch, and a tilt of the whole model, as the inventory's player is turned.
 *
 * <pre>{@code
 * CgEntityPose.followingPointer(pointerX - midX, pointerY - midY)   // the inventory's: looks at the pointer
 * CgEntityPose.facing(30, 0)                                        // turned 30 degrees, looking ahead
 * CgEntityPose.of(spin, spin + 20, -10)                             // a turntable, its head ahead of its body
 * }</pre>
 *
 * <ul>
 *   <li>Positive yaw turns the entity to the viewer's left, as the inventory turns it toward a pointer on that side.</li>
 *   <li>Positive pitch looks down, as Minecraft's does.</li>
 *   <li>Before 1.19.4 Minecraft draws an entity only toward a pointer: a yaw a pointer cannot give (past about 60
 *       degrees) is the furthest it can.</li>
 * </ul>
 */
public record CgEntityPose(float bodyYaw, float headYaw, float pitch, float tilt) {

    /** Facing the viewer. */
    public static final CgEntityPose FRONT = new CgEntityPose(0, 0, 0, 0);

    /**
     * Looking at a pointer {@code dx, dy} units from the middle of the entity's box (right and down positive), as the
     * inventory's player does: vanilla's {@code renderEntityInInventoryFollowsMouse} angles, ported.
     */
    public static CgEntityPose followingPointer(float dx, float dy) {
        double turn = Math.atan(-dx / 40.0), lift = Math.atan(-dy / 40.0);
        // + 0f: no -0.0, so a pointer on the middle equals FRONT
        return new CgEntityPose((float) (turn * 20) + 0f, (float) (turn * 40) + 0f, (float) (-lift * 20) + 0f,
                (float) (lift * 20) + 0f);
    }

    /** Body and head turned {@code yaw}, the head pitched. */
    public static CgEntityPose facing(float yaw, float pitch) {
        return new CgEntityPose(yaw, yaw, pitch, 0);
    }

    public static CgEntityPose of(float bodyYaw, float headYaw, float pitch) {
        return new CgEntityPose(bodyYaw, headYaw, pitch, 0);
    }
}
