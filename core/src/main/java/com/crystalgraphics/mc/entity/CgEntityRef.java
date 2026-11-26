package com.crystalgraphics.mc.entity;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.actor.CgEntity;

/**
 * Which entity a view shows, as a document saves it: one made from data, the local player, any player by name or UUID
 * with their skin, or a live entity in the level. {@link #resolve} finds or makes the game's entity it names; a picture
 * of the game's own object takes the object instead.
 *
 * <pre>{@code
 * CgEntityRef sheep = CgEntityRef.of(CgEntityData.parse("minecraft:sheep{Color:2b}"));
 * CgEntityRef me = CgEntityRef.player();
 * CgEntityRef notch = CgEntityRef.profile("Notch");                 // a name or a UUID's text
 * CgEntityRef that = CgEntityRef.live(entity.getUUID());            // as long as it is in the client's level
 * CgEntityRef boss = CgEntityRef.of(CgEntities.of(bossId));         // the same, from a gameplay handle
 *
 * switch (ref.kind()) { case DATA -> ref.data(); case PROFILE -> ref.profile(); ... }
 *
 * Object entity = ref.resolve(previous);   // render thread: the game's entity, kept while it still is the one named
 * }</pre>
 *
 * <ul>
 *   <li>A profile's skin is fetched by the host, as the game fetches one; until it arrives the default skin shows.</li>
 *   <li>A live entity that has left the level shows nothing; it is never kept alive by a view.</li>
 * </ul>
 */
public final class CgEntityRef {

    /** What a reference names. */
    public enum Kind {
        /** An entity made from {@link CgEntityData}, client-side, never in the level. */
        DATA,
        /** The local player. */
        PLAYER,
        /** A player by name or UUID, as the game shows one it has not met: a mannequin with their skin. */
        PROFILE,
        /** An entity in the client's level, by UUID. */
        LIVE
    }

    private static final CgEntityRef PLAYER = new CgEntityRef(Kind.PLAYER, null, null, null);

    private final Kind kind;
    @Nullable private final CgEntityData data;
    @Nullable private final String profile;
    @Nullable private final UUID uuid;

    private CgEntityRef(Kind kind, @Nullable CgEntityData data, @Nullable String profile, @Nullable UUID uuid) {
        this.kind = kind;
        this.data = data;
        this.profile = profile;
        this.uuid = uuid;
    }

    public static CgEntityRef of(CgEntityData data) {
        return new CgEntityRef(Kind.DATA, Objects.requireNonNull(data, "data"), null, null);
    }

    public static CgEntityRef player() {
        return PLAYER;
    }

    /** A player by name ({@code Notch}) or by UUID's text. */
    public static CgEntityRef profile(String nameOrUuid) {
        String p = Objects.requireNonNull(nameOrUuid, "nameOrUuid").trim();
        if (p.isEmpty()) throw new IllegalArgumentException("a profile needs a name or a UUID");
        return new CgEntityRef(Kind.PROFILE, null, p, null);
    }

    public static CgEntityRef live(UUID entity) {
        return new CgEntityRef(Kind.LIVE, null, null, Objects.requireNonNull(entity, "entity"));
    }

    /**
     * A live reference to {@code entity}, by its UUID: what a document keeps of it, which a network id is not.
     *
     * @throws IllegalStateException where this side has not got the entity loaded
     */
    public static CgEntityRef of(CgEntity entity) {
        Object host = Objects.requireNonNull(entity, "entity").entity();
        if (host == null) throw new IllegalStateException(entity + " is not loaded on this side");
        return live(Objects.requireNonNull(CgEntityQueries.uuid(host), "its UUID"));
    }

    /**
     * The game's entity this names, on the client: the local player and a live entity found in its level
     * ({@code CgEntityQueries.client()}), one of data or a profile made once ({@link CgClientEntities}) and never added to
     * it. {@code previous} is what this answered last for the same holder: kept while it is still the entity named, so
     * a made entity is made once. Null where there is none. Render thread.
     */
    @Nullable
    public Object resolve(@Nullable Object previous) {
        switch (kind) {
            case PLAYER: {
                CgEntityQuery client = CgEntityQueries.client();
                int id = client.localPlayer();
                return id < 0 ? null : client.entity(id);
            }
            case LIVE:
                if (previous != null && CgEntityQueries.alive(previous) && uuid.equals(CgEntityQueries.uuid(previous))) {
                    return previous;
                }
                return CgEntityQueries.client().entity(uuid);
            default:
                return previous != null ? previous : CgClientEntities.make(this);
        }
    }

    /**
     * What {@link #toString} writes, read back: what a document stores a ref as.
     *
     * <pre>{@code
     * CgEntityRef.parse("@s");                    // the player
     * CgEntityRef.parse("profile Notch");         // a profile, with its skin
     * CgEntityRef.parse("entity 0f8e...");        // a live entity, by UUID
     * CgEntityRef.parse("sheep{Color:2b}");       // made from /summon data
     * }</pre>
     *
     * @throws IllegalArgumentException where the text is none of these
     */
    public static CgEntityRef parse(String text) {
        String t = Objects.requireNonNull(text, "text").trim();
        if (t.equals("@s")) return PLAYER;
        if (t.startsWith("profile ")) return profile(t.substring("profile ".length()));
        if (t.startsWith("entity ")) return live(UUID.fromString(t.substring("entity ".length()).trim()));
        return of(CgEntityData.parse(t));
    }

    public Kind kind() {
        return kind;
    }

    /** The data a {@link Kind#DATA} reference makes its entity from; null for the others. */
    @Nullable
    public CgEntityData data() {
        return data;
    }

    /** A {@link Kind#PROFILE} reference's name or UUID text; null for the others. */
    @Nullable
    public String profile() {
        return profile;
    }

    /** A {@link Kind#LIVE} reference's entity; null for the others. */
    @Nullable
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgEntityRef)) return false;
        CgEntityRef other = (CgEntityRef) o;
        return kind == other.kind && Objects.equals(data, other.data) && Objects.equals(profile, other.profile)
                && Objects.equals(uuid, other.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, data, profile, uuid);
    }

    @Override
    public String toString() {
        switch (kind) {
            case DATA: return String.valueOf(data);
            case PLAYER: return "@s";
            case PROFILE: return "profile " + profile;
            default: return "entity " + uuid;
        }
    }
}
