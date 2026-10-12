package com.crystalgraphics.mc.modern.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.function.IntConsumer;

/**
 * {@link LevelEntityQueryModern} over the client's level, with what only a client has: the local and camera entity, and
 * the render list a box walk reads. Client only: constructed by {@code PlatformServiceModern.gl()}. Render thread;
 * allocates nothing but the iterator of {@link #within}'s walk.
 *
 * <ul>
 *   <li>1.13.2 lists no entities for rendering: {@link #within} walks the level's own box query, a list per call.</li>
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

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        //? if >=1.14 {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getBoundingBox().intersects(minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(e.getId());
        }
        //?} else {
        /*super.within(minX, minY, minZ, maxX, maxY, maxZ, visitor);
        *///?}
    }
}
