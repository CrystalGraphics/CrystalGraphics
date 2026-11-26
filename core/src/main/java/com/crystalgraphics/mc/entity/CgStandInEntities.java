package com.crystalgraphics.mc.entity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;

/**
 * Entities where no Minecraft runs (the GL debug harness, a test): a data or profile ref makes itself as its stand-in
 * entity, and {@link #levels} answers the per-object questions about one (a player's height, its ref's text as its
 * name), so the stand-in pictures fit and name it as a game would. There is no local player or live entity here: refs
 * to them draw nothing.
 *
 * <pre>{@code
 * CgPlatform.provide(CgClientEntities.HOST, new CgStandInEntities());
 * CgPlatform.provide(CgLevels.HOST, CgStandInEntities.levels(CgLevels.single(world, entities)));
 * }</pre>
 */
public final class CgStandInEntities implements CgClientEntities.Host {

    @Override
    public String absent() {
        return null;
    }

    /** The ref itself, for data and profiles: the stand-ins draw from the value. */
    @Override
    public Object make(CgEntityRef ref) {
        return ref.kind() == CgEntityRef.Kind.DATA || ref.kind() == CgEntityRef.Kind.PROFILE ? ref : null;
    }

    /** {@code base}, answering the per-object questions for the stand-in entities as well. */
    public static CgLevels.Host levels(CgLevels.Host base) {
        return new Levels(base);
    }

    private static final class Levels implements CgLevels.Host {
        private final CgLevels.Host base;

        Levels(CgLevels.Host base) {
            this.base = base;
        }

        @Override public CgWorldQuery world(Object level) { return base.world(level); }
        @Override public CgEntityQuery entities(Object level) { return base.entities(level); }
        @Override public CgWorldQuery clientWorld() { return base.clientWorld(); }
        @Override public CgEntityQuery clientEntities() { return base.clientEntities(); }
        @Override public Object clientLevel() { return base.clientLevel(); }
        @Override public String dimension(Object handle) { return base.dimension(handle); }
        @Override public Object level(Object handle, String dimension) { return base.level(handle, dimension); }
        @Override public boolean chunkLoaded(Object level, int chunkX, int chunkZ) { return base.chunkLoaded(level, chunkX, chunkZ); }
        @Override public Path saveDirectory(Object handle) { return base.saveDirectory(handle); }
        @Override public int entityId(Object entity) { return base.entityId(entity); }
        @Override public boolean position(Object entity, double[] out) { return base.position(entity, out); }
        @Override public boolean alive(Object entity) { return base.alive(entity); }

        @Override
        public boolean isEntity(Object o) {
            return o instanceof CgEntityRef || base.isEntity(o);
        }

        /** A player's height; one block for anything else. */
        @Override
        public float height(Object entity) {
            if (!(entity instanceof CgEntityRef)) return base.height(entity);
            CgEntityRef.Kind k = ((CgEntityRef) entity).kind();
            return k == CgEntityRef.Kind.PLAYER || k == CgEntityRef.Kind.PROFILE ? 1.8f : 1f;
        }

        @Override
        public String name(Object entity) {
            return entity instanceof CgEntityRef ? entity.toString() : base.name(entity);
        }

        /** The same each run: from the ref's text. */
        @Override
        public UUID uuid(Object entity) {
            if (!(entity instanceof CgEntityRef)) return base.uuid(entity);
            return UUID.nameUUIDFromBytes(entity.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
