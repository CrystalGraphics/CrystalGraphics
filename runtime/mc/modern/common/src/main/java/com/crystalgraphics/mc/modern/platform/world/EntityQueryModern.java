package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.client.Minecraft;
//? if >=1.15 <1.21.5 {
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.UUID;
//?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * {@link LevelEntityQueryModern} over the client's level, with what only a client has: the local and camera entity.
 * Client only: constructed by {@code PlatformServiceModern.gl()}. Render thread.
 *
 * <ul>
 *   <li>{@link #within} is the level's box query, not the render list, which lists no dragon parts.</li>
 * </ul>
 */
public final class EntityQueryModern extends LevelEntityQueryModern {

    public EntityQueryModern() {
        super(null);
    }

    @Override
    Level level() {
        return Minecraft.getInstance().level;
    }

    @Override
    boolean isLocal(Entity e) {
        return e == Minecraft.getInstance().player;
    }

    @Override
    public int localPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? -1 : mc.player.getId();
    }

    @Override
    public int cameraEntity() {
        Entity camera = Minecraft.getInstance().getCameraEntity();
        return camera == null ? -1 : camera.getId();
    }
    //? if >=1.15 <1.21.5 {

    /** The client level has no lookup by UUID before 1.21.5: its rendered entities, walked. */
    @SuppressWarnings("unchecked")
    @Override
    public <V> V entity(UUID id) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || id == null) return null;
        for (Entity e : level.entitiesForRendering()) {
            if (id.equals(e.getUUID())) return (V) e;
        }
        return null;
    }
    //?}
}
