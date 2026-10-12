package com.crystalgraphics.mc.legacy.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
//? if >=1.9 {
import net.minecraft.util.math.AxisAlignedBB;
//?} else {
/*import net.minecraft.util.AxisAlignedBB;
*///?}

import java.util.List;
import java.util.function.IntConsumer;

/**
 * {@link LevelEntityQueryLegacy} over the client's level, with its local and camera entity, and a box walk over the
 * loaded list that allocates nothing. Client only: provided by {@code PlatformServiceLegacy.register}. Render thread.
 */
public final class EntityQueryLegacy extends LevelEntityQueryLegacy {

    public EntityQueryLegacy() {
        super(null);
    }

    @Override
    World world() {
        return ClientLegacy.world();
    }

    @Override
    boolean isLocal(Entity e) {
        return e == ClientLegacy.player();
    }

    @Override
    public int localPlayer() {
        Entity player = ClientLegacy.player();
        return player == null ? -1 : player.getEntityId();
    }

    @Override
    public int cameraEntity() {
        Entity camera = Minecraft.getMinecraft().getRenderViewEntity();
        return camera == null ? -1 : camera.getEntityId();
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return;
        List<?> entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = (Entity) entities.get(i);
            AxisAlignedBB b = e.getEntityBoundingBox();
            if (b.maxX > minX && b.minX < maxX && b.maxY > minY && b.minY < maxY && b.maxZ > minZ && b.minZ < maxZ) {
                visitor.accept(e.getEntityId());
            }
        }
    }
}
