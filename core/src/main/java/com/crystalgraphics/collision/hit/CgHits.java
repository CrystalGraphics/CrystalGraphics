package com.crystalgraphics.collision.hit;

import com.crystalgraphics.collision.CgCollisionQuery;

/**
 * Hits kept past the query that found them, nearest first: what {@link CgCollisionQuery#into} fills. Reused; filling it
 * again replaces what it held, and allocates nothing once it has held as many.
 *
 * <pre>{@code
 * private final CgHits hits = new CgHits();                    // a field, reused
 *
 * int n = world.overlap(BLAST).at(x, y, z).living().into(hits);
 * for (int i = 0; i < n; i++) {
 *     CgHit h = hits.get(i);
 *     damage(h.entity(), 10f * h.falloff(2, 6));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #get} hands out the hit it holds, not a copy: it changes when this is filled again.</li>
 *   <li>{@link #add}, {@link #sort} and the rest fill one by hand, as a query does: hits gathered from several.</li>
 * </ul>
 */
public final class CgHits {

    private CgHit[] hits = new CgHit[0];
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Hit {@code i}, nearest first. */
    public CgHit get(int i) {
        check(i);
        return hits[i];
    }

    public void clear() {
        size = 0;
    }

    /** A cleared hit at the end, to fill. */
    public CgHit add() {
        if (size == hits.length) {
            CgHit[] grown = new CgHit[Math.max(8, size * 2)];
            System.arraycopy(hits, 0, grown, 0, size);
            for (int i = size; i < grown.length; i++) grown[i] = new CgHit();
            hits = grown;
        }
        CgHit h = hits[size++];
        h.reset();
        return h;
    }

    /** Takes back the last {@link #add}. */
    public void removeLast() {
        size--;
    }

    /** Drops hit {@code i}, keeping the order. */
    public void remove(int i) {
        check(i);
        CgHit gone = hits[i];
        System.arraycopy(hits, i + 1, hits, i, size - i - 1);
        hits[--size] = gone;
    }

    /** Keeps the first {@code n}. */
    public void truncate(int n) {
        if (n < size) size = n;
    }

    /** Nearest first; a shell sort, which allocates nothing. */
    public void sort() {
        for (int gap = size / 2; gap > 0; gap = gap == 2 ? 1 : gap * 5 / 11) {
            for (int i = gap; i < size; i++) {
                CgHit h = hits[i];
                int j = i;
                for (; j >= gap && after(hits[j - gap], h); j -= gap) hits[j] = hits[j - gap];
                hits[j] = h;
            }
        }
    }

    /**
     * Nearest first; a tie by what was hit, so the order never depends on how the host listed them, and a hittable
     * hitbox before its owner.
     */
    private static boolean after(CgHit a, CgHit b) {
        if (a.distance() != b.distance()) return a.distance() > b.distance();
        if (a.entity() != b.entity()) return a.entity() > b.entity();
        boolean aBox = a.kind() == CgHit.Kind.HITBOX, bBox = b.kind() == CgHit.Kind.HITBOX;
        if (aBox != bBox) return bBox;
        if (a.blockX() != b.blockX()) return a.blockX() > b.blockX();
        if (a.blockY() != b.blockY()) return a.blockY() > b.blockY();
        return a.blockZ() > b.blockZ();
    }

    private void check(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i + " of " + size);
    }
}
