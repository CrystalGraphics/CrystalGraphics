package com.crystalgraphics.mc.entity;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.world.actor.CgEntity;

/**
 * The game's entity behind whatever names one: a {@link CgEntityRef}, a gameplay {@link CgEntity}, or the game's own
 * object. What an entity picture and a scene's child entity draw, resolved here and only here.
 *
 * <pre>{@code
 * Object source = CgHostEntities.source(anything);                 // checks it names an entity: any thread
 * Object entity = CgHostEntities.resolve(source, previous);        // render thread, each draw
 * float tall = CgHostEntities.height(entity);
 * }</pre>
 *
 * <ul>
 *   <li>A ref is made client-side once (data, a profile) and kept while it is the same entity, or found live each
 *       draw; a {@code CgEntity} answers its own {@code entity()}; an object is itself.</li>
 *   <li>Null where there is none: a live entity that left, a type the game does not know, no game here.</li>
 * </ul>
 */
public final class CgHostEntities {

    /** What an era answers. {@link #entity} on the render thread; the rest anywhere. */
    public interface Host {

        /** Why this node has no entities to draw; null where it has. */
        @Nullable
        String absent();

        /**
         * The game's entity {@code ref} names: the live one, or one made client-side and never added to the level.
         * {@code previous} is what this answered last for the same holder, kept while it still is that entity.
         */
        @Nullable
        Object entity(CgEntityRef ref, @Nullable Object previous);

        /** Whether {@code o} is one of the game's entities. */
        boolean isEntity(Object o);

        /** Its height in blocks. */
        float height(Object entity);

        /** Its name as its nametag shows it, plain text: a custom name, else its type's or its profile's. */
        String name(Object entity);

        UUID uuid(Object entity);

        Host NONE = new Host() {
            @Override public String absent() { return "no Minecraft here"; }
            @Override public Object entity(CgEntityRef ref, Object previous) { return null; }
            @Override public boolean isEntity(Object o) { return false; }
            @Override public float height(Object entity) { return 1; }
            @Override public String name(Object entity) { return ""; }
            @Override public UUID uuid(Object entity) { throw new IllegalStateException("no Minecraft here"); }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_entities", Host.NONE);

    private CgHostEntities() {
    }

    /**
     * {@code source} checked as something that names an entity: a {@link CgEntityRef}, a {@link CgEntity}, or an
     * object the game says is an entity. Any thread.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Object source(Object source) {
        Objects.requireNonNull(source, "source");
        if (source instanceof CgEntityRef || source instanceof CgEntity) return source;
        if (CgPlatform.get(HOST).isEntity(source)) return source;
        throw new IllegalArgumentException(source + " names no entity: a CgEntityRef, a CgEntity or the game's entity");
    }

    /** The entity {@code source} names now; {@code previous} what this answered last for the same holder. Render thread. */
    @Nullable
    public static Object resolve(Object source, @Nullable Object previous) {
        if (source instanceof CgEntityRef) return CgPlatform.get(HOST).entity((CgEntityRef) source, previous);
        if (source instanceof CgEntity) return ((CgEntity) source).entity();
        return source;
    }

    public static float height(Object entity) {
        return CgPlatform.get(HOST).height(entity);
    }

    public static String name(Object entity) {
        return CgPlatform.get(HOST).name(entity);
    }

    public static UUID uuid(Object entity) {
        return CgPlatform.get(HOST).uuid(entity);
    }

    /** Why entities draw nothing here; null where they draw. */
    @Nullable
    public static String absent() {
        return CgPlatform.get(HOST).absent();
    }
}
