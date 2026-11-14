package com.crystalgraphics.world.actor;

import com.crystalgraphics.platform.world.CgEntityQuery;

/**
 * A host entity as its actor sees it this frame: its id and type, and its pose interpolated to where the host draws it.
 * Read on the client world's thread; refreshed each frame before the actor's {@code tick}.
 *
 * <pre>{@code
 * protected void tick(CgEntity dragon, float dt) {
 *     boolean flying = !dragon.flag(CgEntityQuery.ON_GROUND);
 *     double speed = Math.hypot(dragon.pose(CgEntityQuery.VELOCITY_X), dragon.pose(CgEntityQuery.VELOCITY_Z));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Angles are Minecraft's, in degrees: yaw 0 faces +z.</li>
 *   <li>Its numbers hold until the next frame's refresh; a field holding a {@code CgEntity} sees them change.</li>
 * </ul>
 */
public final class CgEntity {

    private final int id;
    private final String type;
    final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    int flags;
    boolean present;
    long hostFrame = -1;

    CgEntity(int id, String type) {
        this.id = id;
        this.type = type;
    }

    /** Its network id: what {@link CgEntityQuery} and the host know it by. */
    public int id() {
        return id;
    }

    /** Its type's id, {@code mymod:wyvern}: what its actor was registered by. */
    public String type() {
        return type;
    }

    public double x() {
        return pose[CgEntityQuery.X];
    }

    public double y() {
        return pose[CgEntityQuery.Y];
    }

    public double z() {
        return pose[CgEntityQuery.Z];
    }

    /** Its body's yaw, degrees: what a living entity's model turns by. */
    public float bodyYaw() {
        return (float) pose[CgEntityQuery.BODY_YAW];
    }

    /** Its head's yaw and pitch, degrees. */
    public float headYaw() {
        return (float) pose[CgEntityQuery.HEAD_YAW];
    }

    public float pitch() {
        return (float) pose[CgEntityQuery.PITCH];
    }

    /** Any value of its pose, by {@link CgEntityQuery}'s indices: velocity, size, limb swing. */
    public double pose(int index) {
        return pose[index];
    }

    /** Its state bits ({@link CgEntityQuery#ALIVE}, {@link CgEntityQuery#SNEAKING}, ...). */
    public int flags() {
        return flags;
    }

    public boolean flag(int bit) {
        return (flags & bit) != 0;
    }

    /**
     * Whether the host answered its pose this frame. False only in {@code onSpawn} where the host announces an entity
     * before adding it to its level (Forge's join event), its pose zero until the first frame; a frame it is missing
     * from removes its actor.
     */
    public boolean present() {
        return present;
    }

    /**
     * The client world frame ({@code CgWorld.frames()}) in which the host's renderer last reached this entity and left
     * its model to the actor; -1 before the first. Behind the current frame while the host culls it (out of view, too
     * far): what an actor reads to do as the host does.
     */
    public long hostFrame() {
        return hostFrame;
    }

    @Override
    public String toString() {
        return type + "#" + id;
    }
}
