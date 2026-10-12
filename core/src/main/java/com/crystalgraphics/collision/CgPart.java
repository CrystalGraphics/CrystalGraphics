package com.crystalgraphics.collision;

/**
 * One part of something that strikes on its own, as an effect's beam and its blast do: what a listener subscribes to,
 * so it hears that part alone. The owner declares its parts as constants and reports through them.
 *
 * <pre>{@code
 * wave.onCollide(CgEnergyWave.PART_BEAM, c -> stagger(c.entity()))
 *     .onCollide(CgEnergyWave.PART_BLAST, c -> explode(c.x(), c.y(), c.z(), c.radius()));
 * }</pre>
 *
 * Declaring parts for an effect of your own:
 *
 * <pre>{@code
 * public static final CgPart PART_BOLT = CgPart.of("bolt"), PART_ARC = CgPart.of("arc");
 *
 * collided(PART_ARC, CgVfxCollision.Hit.ENTITY, struck, x, y, z, nx, ny, nz, vx, vy, vz, 0f);   // in tick
 * }</pre>
 *
 * <ul>
 *   <li>Compared by identity: two parts of one name are two parts. Declare each once, as a constant.</li>
 *   <li>The name is for logs and {@code toString}.</li>
 * </ul>
 */
public final class CgPart {

    private final String name;

    private CgPart(String name) {
        this.name = name;
    }

    public static CgPart of(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a part needs a name");
        return new CgPart(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
