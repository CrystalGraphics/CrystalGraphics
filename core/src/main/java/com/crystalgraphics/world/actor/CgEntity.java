package com.crystalgraphics.world.actor;

import javax.annotation.Nullable;

import com.crystalgraphics.cue.CgCueManager;
import com.crystalgraphics.cue.CgCueParameters;
import com.crystalgraphics.cue.CgCuePlaying;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.world.CgEntityProperties;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntityTags;

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
    private String type;
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

    /** Reads its pose and flags from the host's entities at {@code partialTick}; whether the host has it. */
    boolean refresh(CgEntityQuery entities, float partialTick) {
        present = entities.pose(id, partialTick, pose);
        if (present) flags = entities.flags(id);
        return present;
    }

    /** Names its type as its actor spawns. */
    void type(String type) {
        this.type = type;
    }

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

    // ── the gameplay handle: CgEntities.of(entity) ──────────────────────────────────────

    /** Whether this side is the server: its calls are the truth. */
    public boolean isAuthority() {
        return serverHost != null;
    }

    /** Whether this is the client's own player: the one client whose calls the server may take. False on a server. */
    public boolean isOwner() {
        return serverHost == null && CgEntityTags.owns(id);
    }

    /** Whether it owns {@code tag}, or any tag under it. */
    public boolean hasTag(CgGameplayTag tag) {
        return tags.has(tag);
    }

    /** Whether it owns any of {@code wanted}. */
    public boolean hasAnyTag(CgGameplayTag... wanted) {
        for (CgGameplayTag tag : wanted) if (tags.has(tag)) return true;
        return false;
    }

    /** Whether it owns every one of {@code wanted}. */
    public boolean hasAllTags(CgGameplayTag... wanted) {
        for (CgGameplayTag tag : wanted) if (!tags.has(tag)) return false;
        return true;
    }

    /** One more of {@code tag}: counted, so two sources may grant one tag. */
    public CgEntity addTag(CgGameplayTag tag) {
        return addTag(tag, 1);
    }

    /** {@code count} more of {@code tag}. The server's, or the owner's client's, asked of the server. */
    public CgEntity addTag(CgGameplayTag tag, int count) {
        if (count <= 0) throw new IllegalArgumentException("add " + count + " of " + tag + ": a count is positive");
        return changeTag(tag, count, "addTag");
    }

    /** One fewer of {@code tag}; nothing where it owns none explicitly. */
    public CgEntity removeTag(CgGameplayTag tag) {
        return removeTag(tag, 1);
    }

    /** {@code count} fewer of {@code tag}, gone at 0. */
    public CgEntity removeTag(CgGameplayTag tag, int count) {
        if (count <= 0) throw new IllegalArgumentException("remove " + count + " of " + tag + ": a count is positive");
        int owned = tags.explicitCount(tag);
        return changeTag(tag, -Math.min(count, owned), "removeTag");
    }

    /** {@code tag}'s own count set to {@code count}: Unreal's {@code SetLooseGameplayTagCount}. */
    public CgEntity setTagCount(CgGameplayTag tag, int count) {
        if (count < 0) throw new IllegalArgumentException("count " + count + " of " + tag + ": a count is not negative");
        return changeTag(tag, count - tags.explicitCount(tag), "setTagCount");
    }

    private CgEntity changeTag(CgGameplayTag tag, int delta, String verb) {
        if (delta == 0) return this;
        if (serverHost != null) {
            if (delta > 0) CgEntityTags.add(serverHost, tag, delta);
            else CgEntityTags.remove(serverHost, tag, -delta);
        } else {
            requireOwner(verb);
            CgEntityTags.ask(id, tag, delta, CgEntities.key());
        }
        return this;
    }

    /**
     * Hears {@code tag}'s count on this entity change, on this side: {@code (before, now)}, the tags under it counted. A
     * client hears the server's change as it arrives, and its own predicted one at once.
     *
     * @return what to {@code cancel()} to stop hearing
     */
    public CgEntityTags.Watch onTagChanged(CgGameplayTag tag, java.util.function.BiConsumer<Integer, Integer> listener) {
        return CgEntityTags.watch(serverHost != null ? serverHost : (Object) id, tag, listener);
    }

    /** Plays the cue {@code tag} once at this entity: Unreal's {@code ExecuteGameplayCue}. */
    public CgEntity executeCue(CgGameplayTag tag) {
        return executeCue(tag, new CgCueParameters());
    }

    /** As {@link #executeCue(CgGameplayTag)} with {@code parameters}, anchored on this entity where they name none. */
    public CgEntity executeCue(CgGameplayTag tag, CgCueParameters parameters) {
        if (serverHost == null) requireOwner("executeCue");
        cues().execute(tag, anchored(parameters));
        return this;
    }

    /** Starts the lasting cue {@code tag} on this entity: Unreal's {@code AddGameplayCue}; the handle ends it. */
    public CgCuePlaying addCue(CgGameplayTag tag) {
        return addCue(tag, new CgCueParameters());
    }

    /**
     * As {@link #addCue(CgGameplayTag)} with {@code parameters}. On the owner's client it plays at once and is asked of
     * the server under the window's key ({@link CgEntities#predicted}), shown only while the server takes it.
     */
    public CgCuePlaying addCue(CgGameplayTag tag, CgCueParameters parameters) {
        if (serverHost != null) return cues().add(tag, anchored(parameters));
        requireOwner("addCue");
        return cues().predict(tag, anchored(parameters), CgEntities.key());
    }

    private CgCueManager cues() {
        return serverHost != null ? CgCueManager.server() : CgCueManager.client();
    }

    private CgCueParameters anchored(CgCueParameters parameters) {
        if (parameters.anchor() == null) parameters.anchor(serverHost != null ? CgAnchor.on(serverHost) : CgAnchor.onEntity(id));
        return parameters;
    }

    /**
     * Raises the entity event {@code name} ({@code hurt}, {@code landed}, ...) as if the game had: its reactions play,
     * on this side's actor, for this frame.
     *
     * @throws IllegalStateException on a client
     * @throws IllegalArgumentException for a name that is no entity event
     */
    public CgEntity raiseEvent(String name) {
        var event = CgReaction.event(name);
        if (serverHost == null) throw new IllegalStateException("raiseEvent(" + name + ") is the server's: " + this + " is on a client");
        CgNetObject motion = properties(CgEntityProperties.MOTION);
        if (motion == null) throw new IllegalStateException(this + " has no motion to raise " + name + " on");
        motion.setBool(event, true);
        return this;
    }

    private void requireOwner(String verb) {
        if (!isOwner()) {
            throw new IllegalStateException(verb + " on " + this + " is the server's or its owner's: this client is neither");
        }
    }

    /** The window's key, for what else a predicted call should undo. */
    static CgPredictionKey window() {
        return CgEntities.key();
    }

    @Override
    public String toString() {
        return type + "#" + id;
    }
}
