package com.crystalgraphics.vfx;

/**
 * One part of an effect that strikes on its own, as a beam's contact and its blast do: what a listener subscribes to,
 * so it hears that part alone. An effect declares its parts as constants and reports through them.
 *
 * <pre>{@code
 * wave.onCollide(CgEnergyWave.PART_BEAM, c -> stagger(c.entity()))
 *     .onCollide(CgEnergyWave.PART_BLAST, c -> explode(c.x(), c.y(), c.z(), c.radius()));
 * }</pre>
 *
 * Declaring parts for an effect of your own:
 *
 * <pre>{@code
 * public static final CgVfxPart PART_BOLT = CgVfxPart.of("bolt"), PART_ARC = CgVfxPart.of("arc");
 *
 * collided(PART_ARC, CgVfxCollision.Hit.ENTITY, struck, x, y, z, nx, ny, nz, vx, vy, vz, 0f);   // in tick
 * }</pre>
 *
 * <ul>
 *   <li>Compared by identity: two parts of one name are two parts. Declare each once, as a constant.</li>
 *   <li>The name is for logs and {@code toString}.</li>
 * </ul>
 */
public final class CgVfxPart {

    private final String name;

    private CgVfxPart(String name) {
        this.name = name;
    }

    public static CgVfxPart of(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a part needs a name");
        return new CgVfxPart(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
