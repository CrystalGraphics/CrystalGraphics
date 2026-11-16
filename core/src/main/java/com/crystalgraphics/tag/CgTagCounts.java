package com.crystalgraphics.tag;

import java.util.Arrays;
import java.util.Objects;

/**
 * Gameplay tags something owns, each with a count: Unreal's {@code FGameplayTagCountContainer}. A tag added twice is
 * owned until removed twice, so two sources (a stance and a buff) can grant one tag without either clearing the other's.
 * Asked about a tag, it answers the counts of that tag and every tag under it, as Unreal's count map does.
 *
 * <pre>{@code
 * CgTagCounts tags = new CgTagCounts();
 * tags.update(FLYING_FAST, 1);           // grants mymod:state.flying.fast
 * tags.count(FLYING);                    // 1: mymod:state.flying is its parent
 * tags.has(FLYING_FAST);                 // true
 * tags.update(FLYING_FAST, -1);          // gone at 0
 * }</pre>
 *
 * <ul>
 *   <li>Removing a tag it does not own explicitly is refused (false), as Unreal's is: a parent is never removed through
 *       its child's count.</li>
 *   <li>{@link #count} and {@link #has} allocate nothing; a tag first owned grows its arrays.</li>
 *   <li>One thread at a time, its owner's.</li>
 * </ul>
 */
public final class CgTagCounts {

    private CgGameplayTag[] tags = new CgGameplayTag[4];
    private int[] counts = new int[4];
    private int size;
    private int version;

    /**
     * Adds {@code delta} to {@code tag}'s count, removing it at 0 or below. True when that made a significant change, the
     * tag newly owned or no longer owned (Unreal's new-or-removed); false for any other change, and for a removal of a
     * tag it does not own.
     */
    public boolean update(CgGameplayTag tag, int delta) {
        Objects.requireNonNull(tag, "tag");
        if (delta == 0) return false;
        int at = indexOf(tag);
        if (at < 0) {
            if (delta < 0) return false;
            if (size == tags.length) {
                tags = Arrays.copyOf(tags, size * 2);
                counts = Arrays.copyOf(counts, size * 2);
            }
            tags[size] = tag;
            counts[size++] = delta;
            version++;
            return true;
        }
        counts[at] += delta;
        version++;
        if (counts[at] > 0) return false;
        size--;
        tags[at] = tags[size];
        counts[at] = counts[size];
        tags[size] = null;
        return true;
    }

    /** Becomes {@code other}'s copy: what a client's mirror takes from its server's. */
    public void set(CgTagCounts other) {
        clear();
        for (int i = 0; i < other.size; i++) update(other.tags[i], other.counts[i]);
    }

    /** Owns nothing. */
    public void clear() {
        if (size == 0) return;
        Arrays.fill(tags, 0, size, null);
        size = 0;
        version++;
    }

    /** {@code tag}'s count with every tag under it: what a graph's {@code tags:} binding reads. 0 when it owns none. */
    public int count(CgGameplayTag tag) {
        int n = 0;
        for (int i = 0; i < size; i++) if (tags[i].matches(tag)) n += counts[i];
        return n;
    }

    /** Whether it owns {@code tag} or a tag under it. */
    public boolean has(CgGameplayTag tag) {
        for (int i = 0; i < size; i++) if (tags[i].matches(tag)) return true;
        return false;
    }

    /** {@code tag}'s own count, its children's apart: 0 when not owned explicitly. */
    public int explicitCount(CgGameplayTag tag) {
        int at = indexOf(tag);
        return at < 0 ? 0 : counts[at];
    }

    /** How many tags it owns explicitly. */
    public int size() {
        return size;
    }

    /** The {@code i}th owned tag, in no set order, and its count. */
    public CgGameplayTag tag(int i) {
        return tags[i];
    }

    public int countAt(int i) {
        return counts[i];
    }

    /** Changes with every change: a reader keeping something derived compares it. */
    public int version() {
        return version;
    }

    private int indexOf(CgGameplayTag tag) {
        for (int i = 0; i < size; i++) if (tags[i] == tag) return i;
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < size; i++) b.append(i == 0 ? "" : ", ").append(tags[i]).append('=').append(counts[i]);
        return b.append('}').toString();
    }
}
