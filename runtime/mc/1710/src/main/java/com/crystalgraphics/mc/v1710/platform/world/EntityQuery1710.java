package com.crystalgraphics.mc.v1710.platform.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * {@link LevelEntityQuery1710} over the client's level, with its local and camera entity, and a box walk over the
 * loaded list and each entity's parts that allocates nothing. Client only: provided by {@code PlatformService1710.onPreInit}. Render thread.
 */
public final class EntityQuery1710 extends LevelEntityQuery1710 {

    public EntityQuery1710() {
        super(null);
    }

    @Override
    World world() {
        return Minecraft.getMinecraft().theWorld;
    }

    @Override
    boolean isLocal(Entity e) {
        return e == Minecraft.getMinecraft().thePlayer;
    }

    @Override
    public int localPlayer() {
        Entity player = Minecraft.getMinecraft().thePlayer;
        return player == null ? -1 : player.getEntityId();
    }

    @Override
    public int cameraEntity() {
        Entity camera = Minecraft.getMinecraft().renderViewEntity;
        return camera == null ? -1 : camera.getEntityId();
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) return;
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (touches(e, minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(e.getEntityId());
            // a client lists no parts, loaded or by id
            Entity[] parts = e.getParts();
            if (parts == null) continue;
            for (Entity part : parts) {
                if (touches(part, minX, minY, minZ, maxX, maxY, maxZ)) visit(world, part, visitor);
            }
        }
    }

    private static boolean touches(Entity e, double minX, double minY, double minZ, double maxX, double maxY,
                                   double maxZ) {
        AxisAlignedBB b = e.boundingBox;   // its corners in either order: HostShapes1710.set
        return Math.max(b.minX, b.maxX) > minX && Math.min(b.minX, b.maxX) < maxX
                && Math.max(b.minY, b.maxY) > minY && Math.min(b.minY, b.maxY) < maxY
                && Math.max(b.minZ, b.maxZ) > minZ && Math.min(b.minZ, b.maxZ) < maxZ;
    }
}
