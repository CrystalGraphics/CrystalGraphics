package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgHostShapes;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;

import java.util.List;

/**
 * {@link CgHostShapes} over {@code AxisAlignedBB} and entities on Minecraft 1.7.10. Server-safe; provided beside
 * {@link Levels1710}. A block's shape is the {@code List} of boxes its collision call fills; a box here is
 * mutable, so nothing converted from one is kept.
 */
public final class HostShapes1710 implements CgHostShapes {

    @Override
    public void box(Object hostBox, CgBoxSink out) {
        AxisAlignedBB b = hostBox instanceof AxisAlignedBB ? (AxisAlignedBB) hostBox
                : hostBox instanceof Entity ? ((Entity) hostBox).boundingBox : null;
        if (b == null) throw unknown(hostBox, "an AxisAlignedBB or an Entity");
        set(b, 0, 0, 0, out);
    }

    /**
     * {@code b} moved by ({@code dx}, {@code dy}, {@code dz}), its corners in order: 1.7.10's box keeps them as set, and
     * a hanging entity's comes out with min above max on its thin side (a painting facing north). 1.8 orders them.
     */
    static void set(AxisAlignedBB b, double dx, double dy, double dz, CgBoxSink out) {
        out.set(Math.min(b.minX, b.maxX) + dx, Math.min(b.minY, b.maxY) + dy, Math.min(b.minZ, b.maxZ) + dz,
                Math.max(b.minX, b.maxX) + dx, Math.max(b.minY, b.maxY) + dy, Math.max(b.minZ, b.maxZ) + dz);
    }

    @Override
    public int boxes(Object hostShape, double[] out) {
        if (hostShape instanceof AxisAlignedBB) {
            put((AxisAlignedBB) hostShape, out, 0);
            return 1;
        }
        if (!(hostShape instanceof List)) throw unknown(hostShape, "an AxisAlignedBB or a List of them");
        List<?> list = (List<?>) hostShape;
        for (int i = 0; i < list.size(); i++) {
            Object b = list.get(i);
            if (!(b instanceof AxisAlignedBB)) throw unknown(b, "an AxisAlignedBB in the list");
            put((AxisAlignedBB) b, out, i);
        }
        return list.size();
    }

    /** Box {@code i} into {@code out} if it fits. */
    private static void put(AxisAlignedBB b, double[] out, int i) {
        int o = i * 6;
        if (o + 6 > out.length) return;
        out[o] = Math.min(b.minX, b.maxX);
        out[o + 1] = Math.min(b.minY, b.maxY);
        out[o + 2] = Math.min(b.minZ, b.maxZ);
        out[o + 3] = Math.max(b.minX, b.maxX);
        out[o + 4] = Math.max(b.minY, b.maxY);
        out[o + 5] = Math.max(b.minZ, b.maxZ);
    }

    @Override
    public Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean immutable(Object hostShape) {
        return false;
    }

    private static IllegalArgumentException unknown(Object o, String accepts) {
        return new IllegalArgumentException((o == null ? "null" : o.getClass().getName())
                + " is not a Minecraft shape: this host takes " + accepts);
    }
}
