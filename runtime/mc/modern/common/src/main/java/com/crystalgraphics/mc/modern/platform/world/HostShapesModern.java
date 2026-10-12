package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgHostShapes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * {@link CgHostShapes} over {@code AABB}, {@code VoxelShape} and entities, 1.13.2 to 26.3. Server-safe; provided
 * beside {@link LevelQueriesModern}. A block shape's boxes come from {@code toAabbs()}, a list per call, which
 * {@code CgShape.of} pays once per shape.
 */
public final class HostShapesModern implements CgHostShapes {

    @Override
    public void box(Object hostBox, CgBoxSink out) {
        AABB b = hostBox instanceof AABB ? (AABB) hostBox
                : hostBox instanceof Entity ? ((Entity) hostBox).getBoundingBox() : null;
        if (b == null) throw unknown(hostBox, "an AABB or an Entity");
        out.set(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    @Override
    public int boxes(Object hostShape, double[] out) {
        if (hostShape instanceof AABB) {
            put((AABB) hostShape, out, 0);
            return 1;
        }
        List<?> list;
        if (hostShape instanceof VoxelShape) {
            VoxelShape shape = (VoxelShape) hostShape;
            if (shape.isEmpty()) return 0;
            list = shape.toAabbs();
        } else if (hostShape instanceof List) {
            list = (List<?>) hostShape;
        } else {
            throw unknown(hostShape, "a VoxelShape, an AABB or a List of AABB");
        }
        for (int i = 0; i < list.size(); i++) {
            Object b = list.get(i);
            if (!(b instanceof AABB)) throw unknown(b, "an AABB in the list");
            put((AABB) b, out, i);
        }
        return list.size();
    }

    /** Box {@code i} into {@code out} if it fits. */
    private static void put(AABB b, double[] out, int i) {
        int o = i * 6;
        if (o + 6 > out.length) return;
        out[o] = b.minX;
        out[o + 1] = b.minY;
        out[o + 2] = b.minZ;
        out[o + 3] = b.maxX;
        out[o + 4] = b.maxY;
        out[o + 5] = b.maxZ;
    }

    @Override
    public Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean immutable(Object hostShape) {
        return hostShape instanceof VoxelShape || hostShape instanceof AABB;
    }

    private static IllegalArgumentException unknown(Object o, String accepts) {
        return new IllegalArgumentException((o == null ? "null" : o.getClass().getName())
                + " is not a Minecraft shape: this host takes " + accepts);
    }
}
