package com.crystalgraphics.world.actor;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * A host entity as its actor sees it this frame: its id and type, and its pose interpolated to where the host draws it.
 * Read on the client world's thread; refreshed each frame before the actor's {@code tick}.
 *
 * <pre>{@code
 * protected void tick(CgEntity dragon, float dt) {
 *     CgNetObject motion = dragon.properties(CgEntityProperties.MOTION);   // what it is doing: speed, ground, hands
 *     if (motion.get(CgEntityProperties.LANDED)) body.anim().montages().play(LAND);
 *     float ki = dragon.properties(Ki.TYPE).get(Ki.CURRENT);                // a mod's own, the same way
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
    /** Its owned tags, on its side: the server's counts, or a client's mirror of them. Set at spawn. */
    CgTagCounts tags = new CgTagCounts();
    /** Whether {@link #tags} is a client's mirror held for it, let go when its actor ends. */
    boolean heldTags;
    /** The server's entity, on a server; null on a client, which looks its own up. */
    @Nullable
    Object serverHost;
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


    /**
     * The gameplay tags it owns, with counts ({@code CgEntityTags}): on a server its own, on a client what its server last
     * sent. Read them; change them on the server through {@code CgEntityTags.add}.
     */
    public CgTagCounts tags() {
        return tags;
    }

    /**
     * The loader's own entity on this side: the server's, or the client's copy looked up by id; null where the client
     * has none loaded. What a mod's entity field reads.
     */
    @Nullable
    public Object entity() {
        return serverHost != null ? serverHost : CgEntityQueries.client().entity(id);
    }

    /** Whether its actor runs on the server, reading the server's entity. */
    public boolean onServer() {
        return serverHost != null;
    }

    /**
     * Its object of {@code type}, a per-holder type on entities: its properties, the engine's
     * ({@link CgEntityProperties#MOTION}) or a mod's, on this side. A local type's is made if absent; a sent type's is
     * the server's on the server and this client's copy on a client, null until it arrives.
     */
    @Nullable
    public CgNetObject properties(CgNetType type) {
        if (serverHost != null) return type.isLocal() ? type.of(serverHost) : type.find(serverHost);
        return type.isLocal() ? type.of(id) : type.find(id);
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
