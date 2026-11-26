package com.crystalgraphics.mc.entity;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * Entities made on the client and never added to its level: what a {@link CgEntityRef} of data or of a profile draws
 * as. The slot each era fills; the rest of a ref's resolving, and every question about an entity, is
 * {@code CgEntityQueries}'.
 *
 * <pre>{@code
 * Object sheep = CgClientEntities.make(CgEntityRef.parse("sheep{Color:2b}"));   // render thread
 * }</pre>
 *
 * <ul>
 *   <li>Each call makes a new entity: keep it, as {@link CgEntityRef#resolve} does through its {@code previous}.</li>
 *   <li>Null where the client is in no level, the game does not know the type, or the era makes none.</li>
 * </ul>
 */
public final class CgClientEntities {

    /** What an era answers, render thread. */
    public interface Host {

        /** Why this node makes no entities; null where it does. */
        @Nullable
        String absent();

        /**
         * A new entity for {@code ref}, of kind {@link CgEntityRef.Kind#DATA} (from its data) or
         * {@link CgEntityRef.Kind#PROFILE} (a player's mannequin, the default skin until the game fetches theirs);
         * null for the other kinds.
         */
        @Nullable
        Object make(CgEntityRef ref);

        Host NONE = new Host() {
            @Override public String absent() { return "no Minecraft here"; }
            @Override public Object make(CgEntityRef ref) { return null; }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:client_entities", Host.NONE);

    private CgClientEntities() {
    }

    /** A new entity for a data or profile ref; null for other kinds, or where none can be made. Render thread. */
    @Nullable
    public static Object make(CgEntityRef ref) {
        return CgPlatform.get(HOST).make(ref);
    }

    /** Why this node makes no entities; null where it does. */
    @Nullable
    public static String absent() {
        return CgPlatform.get(HOST).absent();
    }
}
