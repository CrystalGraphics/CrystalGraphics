package com.crystalgraphics.mc.entity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The host of {@link CgHostEntities} where no Minecraft runs (the GL debug harness, a test): a ref is its own entity, a
 * player's height or a block's, so the stand-in pictures fit and name it as a game would.
 *
 * <pre>{@code
 * CgPlatform.provide(CgHostEntities.HOST, new CgStandInEntities());
 * }</pre>
 */
public final class CgStandInEntities implements CgHostEntities.Host {

    @Override
    public String absent() {
        return null;
    }

    /** The reference itself: the stand-ins draw from the value. */
    @Override
    public Object entity(CgEntityRef ref, Object previous) {
        return ref;
    }

    /** Only refs: there are no game objects here. */
    @Override
    public boolean isEntity(Object o) {
        return o instanceof CgEntityRef;
    }

    /** A player's height; one block for anything else. */
    @Override
    public float height(Object entity) {
        CgEntityRef.Kind k = ((CgEntityRef) entity).kind();
        return k == CgEntityRef.Kind.PLAYER || k == CgEntityRef.Kind.PROFILE ? 1.8f : 1f;
    }

    @Override
    public String name(Object entity) {
        return entity.toString();
    }

    /** The ref's own where it is live; one from its text otherwise, the same each run. */
    @Override
    public UUID uuid(Object entity) {
        CgEntityRef ref = (CgEntityRef) entity;
        return ref.uuid() != null ? ref.uuid() : UUID.nameUUIDFromBytes(ref.toString().getBytes(StandardCharsets.UTF_8));
    }
}
