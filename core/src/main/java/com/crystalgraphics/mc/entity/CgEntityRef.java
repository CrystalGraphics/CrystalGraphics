package com.crystalgraphics.mc.entity;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * Which entity a view shows: one made from data, the local player, any player by name or UUID with their skin, or a
 * live entity in the level. Never the game's object itself; the host finds or makes the entity where it draws it.
 *
 * <pre>{@code
 * CgEntityRef sheep = CgEntityRef.of(CgEntityData.parse("minecraft:sheep{Color:2b}"));
 * CgEntityRef me = CgEntityRef.player();
 * CgEntityRef notch = CgEntityRef.profile("Notch");                 // a name or a UUID's text
 * CgEntityRef that = CgEntityRef.live(entity.getUUID());            // as long as it is in the client's level
 *
 * switch (ref.kind()) { case DATA -> ref.data(); case PROFILE -> ref.profile(); ... }
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
