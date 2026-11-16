package com.crystalgraphics.world.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.world.CgEntityEvent;
import com.crystalgraphics.world.CgEntityFields;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntityState;

/**
 * A host entity as its actor sees it this frame: its id and type, and its pose interpolated to where the host draws it.
 * Read on the client world's thread; refreshed each frame before the actor's {@code tick}.
 *
 * <pre>{@code
 * protected void tick(CgEntity dragon, float dt) {
 *     CgEntityState s = dragon.state();                     // what it is doing: speed, ground, hands
 *     float lookYaw = s.headYaw();
 * }
 *
 * protected void onSpawn(CgEntity dragon) {
 *     dragon.on(CgEntityEvents.LANDED, (e, none) -> body.anim().montages().play(LAND));   // each time it happens
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Angles are Minecraft's, in degrees: yaw 0 faces +z.</li>
 *   <li>Its numbers hold until the next frame's refresh; a field holding a {@code CgEntity} sees them change.</li>
 * </ul>
 */
public final class CgEntity implements CgEntityFields.Source {

    /** What hears one of its events: the entity, and what the event carried. */
    @FunctionalInterface
    public interface Listener<P> {
        void on(CgEntity entity, P payload);
    }


    private final int id;
    private final String type;
    final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    final CgEntityState state = new CgEntityState();
    /** Its owned tags, on its side: the server's counts, or a client's mirror of them. Set at spawn. */
    CgTagCounts tags = new CgTagCounts();
    /** Whether {@link #tags} is a client's mirror held for it, let go when its actor ends. */
    boolean heldTags;
    /** The server's entity, on a server; null on a client, which looks its own up. */
    @Nullable
    Object serverHost;
    private final List<CgEntityEvent<?>> heard = new ArrayList<>();
    private final List<Listener<?>> listeners = new ArrayList<>();
    int flags;
    boolean present;
    long hostFrame = -1;

    CgEntity(int id, String type) {
        this.id = id;
        this.type = type;
    }

    /** Its network id: what {@link CgEntityQuery} and the host know it by. */
    @Override
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
     * What it is doing this frame, for its animation: speed, ground and air, swimming, riding, its hands' items and arm
     * poses, uses and swings, and this frame's events (a jump, a landing, a hit). The same on every version and side.
     */
    @Override
    public CgEntityState state() {
        return state;
    }

    /**
     * The gameplay tags it owns, with counts ({@code CgEntityTags}): on a server its own, on a client what its server last
     * sent. Read them; change them on the server through {@code CgEntityTags.add}.
     */
    @Override
    public CgTagCounts tags() {
        return tags;
    }

    /**
     * The loader's own entity on this side: the server's, or the client's copy looked up by id; null where the client
     * has none loaded. What a mod's entity field reads.
     */
    @Override
    @Nullable
    public Object entity() {
        return serverHost != null ? serverHost : CgEntityQueries.client().entity(id);
    }

    /** Whether its actor runs on the server, reading the server's entity. */
    @Override
    public boolean onServer() {
        return serverHost != null;
    }

    /**
     * Has {@code listener} hear {@code event} each time it happens to this entity, after its state's refresh and before
     * its actor's {@code tick}, on the actor's side, until the actor ends.
     *
     * <pre>{@code
     * entity.on(CgEntityEvents.SWING_STARTED, (e, hand) -> body.anim().montages().play(hand == CgHand.OFF ? JAB : SLASH));
     * }</pre>
     */
    public <P> CgEntity on(CgEntityEvent<P> event, Listener<P> listener) {
        heard.add(Objects.requireNonNull(event, "event"));
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Tells each listener of an event that fired in the last refresh. */
    @SuppressWarnings("unchecked")
    void fireEvents() {
        if (!state.firedAny()) return;
        for (int i = 0; i < heard.size(); i++) {
            CgEntityEvent<Object> event = (CgEntityEvent<Object>) heard.get(i);
            if (state.fired(event)) ((Listener<Object>) listeners.get(i)).on(this, event.payload(state));
        }
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
