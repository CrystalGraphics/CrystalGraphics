package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgHostShapes;
import net.minecraft.entity.Entity;
//? if >=1.9 {
import net.minecraft.util.math.AxisAlignedBB;
//?} else {
/*import net.minecraft.util.AxisAlignedBB;
*///?}

import java.util.List;

/**
 * {@link CgHostShapes} over {@code AxisAlignedBB} and entities on Forge 1.8.9 to 1.12.2. Server-safe; provided beside
 * {@link LevelsLegacy}. A block's shape is the {@code List} of boxes its collision call fills.
 */
public final class HostShapesLegacy implements CgHostShapes {

    @Override
    public void box(Object hostBox, CgBoxSink out) {
        AxisAlignedBB b = hostBox instanceof AxisAlignedBB ? (AxisAlignedBB) hostBox
                : hostBox instanceof Entity ? ((Entity) hostBox).getEntityBoundingBox() : null;
        if (b == null) throw unknown(hostBox, "an AxisAlignedBB or an Entity");
        out.set(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
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
        out[o] = b.minX;
        out[o + 1] = b.minY;
        out[o + 2] = b.minZ;
        out[o + 3] = b.maxX;
        out[o + 4] = b.maxY;
        out[o + 5] = b.maxZ;
    }

    @Override
    public Object toHost(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean immutable(Object hostShape) {
        return hostShape instanceof AxisAlignedBB;
    }

    private static IllegalArgumentException unknown(Object o, String accepts) {
        return new IllegalArgumentException((o == null ? "null" : o.getClass().getName())
                + " is not a Minecraft shape: this host takes " + accepts);
    }
}
