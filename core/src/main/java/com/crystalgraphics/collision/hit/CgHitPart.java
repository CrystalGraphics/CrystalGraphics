package com.crystalgraphics.collision.hit;

/**
 * One part of something that strikes on its own, as an effect's beam and its blast do: each hit names its part
 * ({@code CgHit.part}), so a listener may hear that part alone. The owner declares its parts as constants.
 *
 * <pre>{@code
 * wave.onHit(CgEnergyWave.PART_BEAM, h -> stagger(h.entity()))
 *     .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));
 * }</pre>
 *
 * Declaring parts: a hitbox's, a query's, or an effect's, where its schema numbers them for the network.
 *
 * <pre>{@code
 * CgHitbox.follow(id, CgEntityAttachments.CHEST).part(PART_FIST, CgSphere.of(0.4), 0.5, 0, 0);
 * world.overlap(BLAST).at(x, y, z).part(PART_SHOCK).forEach(...);
 * public static final CgHitPart PART_ARC = SCHEMA.part("arc");           // on an effect
 * }</pre>
 *
 * <ul>
 *   <li>Compared by identity: two parts of one name are two parts. Declare each once, as a constant.</li>
 *   <li>The name is for logs and {@code toString}.</li>
 * </ul>
 */
public final class CgHitPart {

    private final String name;

    private CgHitPart(String name) {
        this.name = name;
    }

    public static CgHitPart of(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a part needs a name");
        return new CgHitPart(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
