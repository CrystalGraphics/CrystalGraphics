package com.crystalgraphics.platform.world;

/**
 * What a host reads off one entity for its animation: the facts {@link CgEntityQuery#facts} writes, which core's
 * {@code com.crystalgraphics.world.CgEntityState} turns into speeds, arm poses, timers and events, the same way on every
 * version. A caller reuses one; a host writes every field each call.
 *
 * <pre>{@code
 * CgEntityFacts facts = new CgEntityFacts();                    // once, and reused
 * if (entities.facts(id, partialTick, facts) && facts.has(CgEntityFacts.USING)) {
 *     int held = facts.item[facts.useHand];                     // the item in use, by its raw id
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A fact the running version lacks reads as not happening: no swim pose before 1.13, no gliding before 1.10.2, no
 *       off hand before 1.10.2, no charged crossbow before 1.14.3.</li>
 *   <li>Items are the host's raw registry ids, 0 for an empty hand: they mean one item for the session, never across a
 *       restart.</li>
 *   <li>Its level's thread, as {@link CgEntityQuery}.</li>
 * </ul>
 */
public final class CgEntityFacts {

    /** {@link #bits}. {@code SWIM_POSE} is the host's swimming or crawling pose, in water or out. */
    public static final int LIVING = 1, GROUNDED = 1 << 1, SNEAKING = 1 << 2, SPRINTING = 1 << 3, SWIM_POSE = 1 << 4,
            GLIDING = 1 << 5, SPIN_ATTACK = 1 << 6, IN_WATER = 1 << 7, SLEEPING = 1 << 8, DEAD = 1 << 9,
            SWINGING = 1 << 10, USING = 1 << 11, LEFT_HANDED = 1 << 12, CHARGED_MAIN = 1 << 13, CHARGED_OFF = 1 << 14;

    /** Hands, as {@link #item}, {@link #use}, {@link #swingHand} and {@link #useHand} index them. */
    public static final int MAIN = 0, OFF = 1;

    /**
     * How an item looks in use, {@link #use}: what Minecraft calls its use animation. {@code TRIDENT} is the overhead
     * throw (a trident's, which Minecraft named spear before 1.21.11); {@code SPEAR} is 1.21.11's spear. {@code OTHER}
     * is one the engine has no name for: a mod's own ({@code CUSTOM}), or a later version's.
     */
    public static final int USE_NONE = 0, USE_EAT = 1, USE_DRINK = 2, USE_BLOCK = 3, USE_BOW = 4, USE_TRIDENT = 5,
            USE_CROSSBOW = 6, USE_SPYGLASS = 7, USE_TOOT_HORN = 8, USE_BRUSH = 9, USE_BUNDLE = 10, USE_SPEAR = 11,
            USE_OTHER = 12;

    /** The flags above. */
    public int bits;
    /** How far it moved over its last tick, in blocks: its position less its previous one. */
    public double moveX, moveY, moveZ;
    /** Its feet's height at the partial tick asked. */
    public double y;
    /** The network id of what it rides; -1 for nothing. */
    public int vehicle;
    /** Minecraft's own countdowns, in ticks: since the last hurt (10 at the hit, then down to 0), and since death. */
    public int hurtTime, deathTime;
    /** The swing's progress, 0 to 1 at the partial tick asked, and the hand swinging. */
    public float swing;
    public int swingHand;
    /** The hand in use, ticks into the use (-1 where the host cannot say), and its length in ticks (0 likewise). */
    public int useHand, useTicks, useLength;
    /** Each hand's item, by raw registry id (0 for empty), and how it looks in use ({@code USE_}). */
    public final int[] item = new int[2], use = new int[2];

    /** Whether every bit of {@code bit} is set. */
    public boolean has(int bit) {
        return (bits & bit) == bit;
    }

    /** Back to an entity doing nothing: what a host starts from. */
    public CgEntityFacts clear() {
        bits = 0;
        moveX = moveY = moveZ = y = 0;
        vehicle = -1;
        hurtTime = deathTime = 0;
        swing = 0f;
        swingHand = useHand = MAIN;
        useTicks = -1;
        useLength = 0;
        item[MAIN] = item[OFF] = 0;
        use[MAIN] = use[OFF] = USE_NONE;
        return this;
    }
}
