package com.crystalgraphics.collision.hitbox;

import java.util.Arrays;

/**
 * Who a hitbox has hit, when and how often: what its rules ask before each hit. Every hitbox keeps its own; separate
 * hitboxes that must still hit each target once between them share one.
 *
 * <pre>{@code
 * CgHitList combo = new CgHitList();
 * CgHitbox.of(SLASH).at(x, y, z).hitList(combo).onHit(this::damage).start(level);
 * CgHitbox.of(THRUST).at(x, y, z).hitList(combo).onHit(this::damage).start(level);   // never the same target twice
 * }</pre>
 *
 * <ul>
 *   <li>Keyed by the whole entity: a dragon struck in two parts is one target.</li>
 *   <li>Times are the asking hitbox's age in ticks: hitboxes sharing a list should start together for
 *       {@code rehit} to mean the same to each.</li>
 * </ul>
 */
public final class CgHitList {

    private static final int EMPTY = Integer.MIN_VALUE;

    private int[] keys = new int[16];
    private int[] counts = new int[16];
    private double[] last = new double[16];
    private int size;

    public CgHitList() {
        Arrays.fill(keys, EMPTY);
    }

    /** How many targets have been hit. */
    public int size() {
        return size;
    }

    public boolean contains(int entity) {
        return keys[find(entity)] == entity;
    }

    /** How often {@code entity} has been hit; 0 for never. */
    public int count(int entity) {
        int slot = find(entity);
        return keys[slot] == entity ? counts[slot] : 0;
    }

    /** When {@code entity} was last hit, in its hitbox's ticks; NaN for never. */
    public double lastHit(int entity) {
        int slot = find(entity);
        return keys[slot] == entity ? last[slot] : Double.NaN;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        size = 0;
    }

    void record(int entity, double when) {
        int slot = find(entity);
        if (keys[slot] != entity) {
            if ((size + 1) * 2 > keys.length) {
                grow();
                slot = find(entity);
            }
            keys[slot] = entity;
            counts[slot] = 0;
            size++;
        }
        counts[slot]++;
        last[slot] = when;
    }

    private int find(int key) {
        int mask = keys.length - 1;
        int slot = key * 0x9E3779B9 >>> 7 & mask;
        while (keys[slot] != EMPTY && keys[slot] != key) slot = slot + 1 & mask;
        return slot;
    }

    private void grow() {
        int[] oldKeys = keys, oldCounts = counts;
        double[] oldLast = last;
        keys = new int[oldKeys.length * 2];
        counts = new int[keys.length];
        last = new double[keys.length];
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == EMPTY) continue;
            int slot = find(oldKeys[i]);
            keys[slot] = oldKeys[i];
            counts[slot] = oldCounts[i];
            last[slot] = oldLast[i];
        }
    }
}
