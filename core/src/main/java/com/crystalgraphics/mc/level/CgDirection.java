package com.crystalgraphics.mc.level;

import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A block's face, as the game names and orders them ({@code Direction}, legacy's {@code EnumFacing}): down, up, north,
 * south, west, east. North is −z, east is +x.
 *
 * <pre>{@code
 * CgDirection face = CgDirection.parse("north");
 * CgBlockPos front = pos.relative(face);           // one block north
 * CgDirection back = face.opposite();              // SOUTH
 * }</pre>
 *
 * <p>{@link #ordinal()} is the game's own index ({@code get3DDataValue}).</p>
 */
public enum CgDirection {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    public static final CgCodec<CgDirection> CODEC = CgCodecs.xmap(CgCodecs.STRING, CgDirection::parse, CgDirection::toString);

    private final int stepX, stepY, stepZ;

    CgDirection(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    /** By its name, any case: {@code north}. Throws naming the six where it is none. */
    public static CgDirection parse(String name) {
        CgDirection d = byName(name);
        if (d == null) throw new IllegalArgumentException("not a face: \"" + name + "\" (down, up, north, south, west, east)");
        return d;
    }

    @Nullable
    public static CgDirection byName(String name) {
        for (CgDirection d : values()) if (d.name().equalsIgnoreCase(name.trim())) return d;
        return null;
    }

    public int stepX() {
        return stepX;
    }

    public int stepY() {
        return stepY;
    }

    public int stepZ() {
        return stepZ;
    }

    public CgDirection opposite() {
        return values()[ordinal() ^ 1];
    }

    /** Whether it lies in the horizontal plane: north, south, west, east. */
    public boolean isHorizontal() {
        return stepY == 0;
    }

    /** The game's name: {@code north}. */
    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
