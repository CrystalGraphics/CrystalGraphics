package com.crystalgraphics.tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * A set of tags with their ancestors, tested by bit sets. Mirrors Unreal's {@code FGameplayTagContainer}: the tags
 * added are <em>explicit</em>, their ancestors come along, and a plain query sees both.
 *
 * <pre>{@code
 * CgTagContainer owned = new CgTagContainer().add(fire).add(charging);
 *
 * owned.has(beam);                     // true: fire is mymod:cue.beam.fire and beam is its ancestor
 * owned.hasExact(beam);                // false: beam itself was never added
 * owned.hasAny(wanted);                // one AND per word; false when wanted is empty
 * owned.hasAll(wanted);                // true when wanted is empty
 * }</pre>
 *
 * <p>Easy to get wrong: {@link #hasAny} of an empty container is false but {@link #hasAll} is true (Unreal's choice:
 * no failed check). A container belongs to the registry of its first tag; adding another registry's tag throws. Not
 * thread-safe.</p>
 */
public final class CgTagContainer {

    private static final long[] EMPTY = new long[0];

    private long[] explicit = EMPTY;
    private long[] all = EMPTY;
    @Nullable
    private CgTagRegistry registry;

    /** Adds {@code tag}, and its ancestors to the matched set. A tag already present changes nothing. */
    public CgTagContainer add(CgGameplayTag tag) {
        adopt(tag.registry());
        explicit = or(explicit, own(tag));
        all = or(all, tag.bits());
        return this;
    }

    /** Adds every explicit tag of {@code other}. */
    public CgTagContainer addAll(CgTagContainer other) {
        if (other.registry == null) return this;
        adopt(other.registry);
        explicit = or(explicit, other.explicit);
        all = or(all, other.all);
        return this;
    }

    /** Removes {@code tag} if it was added explicitly. Ancestors it alone provided go too. */
    public boolean remove(CgGameplayTag tag) {
        if (!hasExact(tag)) return false;
        int i = tag.index();
        explicit[i >> 6] &= ~(1L << (i & 63));
        rebuild();
        return true;
    }

    /** Whether {@code tag} was added, or is an ancestor of something added. False for null and for another registry's. */
    public boolean has(@Nullable CgGameplayTag tag) {
        return tag != null && tag.registry() == registry && test(all, tag.index());
    }

    /** Whether {@code tag} itself was added. */
    public boolean hasExact(@Nullable CgGameplayTag tag) {
        return tag != null && tag.registry() == registry && test(explicit, tag.index());
    }

    /** Whether any explicit tag of {@code other} is in this container or among its ancestors. False when empty. */
    public boolean hasAny(CgTagContainer other) {
        return intersects(other.explicit, all);
    }

    /** Whether any explicit tag of {@code other} was added here as well. */
    public boolean hasAnyExact(CgTagContainer other) {
        return intersects(other.explicit, explicit);
    }

    /** Whether every explicit tag of {@code other} is here or an ancestor of one here. True when empty. */
    public boolean hasAll(CgTagContainer other) {
        return contains(all, other.explicit);
    }

    /** Whether every explicit tag of {@code other} was added here as well. True when empty. */
    public boolean hasAllExact(CgTagContainer other) {
        return contains(explicit, other.explicit);
    }

    /** No explicit tags. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /** How many explicit tags. */
    public int size() {
        int n = 0;
        for (long word : explicit) n += Long.bitCount(word);
        return n;
    }

    /** The explicit tags in registration order. */
    public List<CgGameplayTag> tags() {
        if (registry == null) return Collections.emptyList();
        List<CgGameplayTag> out = new ArrayList<>();
        List<CgGameplayTag> known = registry.all();
        for (int w = 0; w < explicit.length; w++) {
            for (long bits = explicit[w]; bits != 0; bits &= bits - 1) {
                out.add(known.get((w << 6) + Long.numberOfTrailingZeros(bits)));
            }
        }
        return out;
    }

    /** Calls {@code each} with the explicit tags in registration order. */
    public void forEach(Consumer<CgGameplayTag> each) {
        for (CgGameplayTag tag : tags()) each.accept(tag);
    }

    /** An independent copy. */
    public CgTagContainer copy() {
        CgTagContainer out = new CgTagContainer();
        out.registry = registry;
        out.explicit = explicit.clone();
        out.all = all.clone();
        return out;
    }

    public void clear() {
        explicit = EMPTY;
        all = EMPTY;
    }

    /** Parents are recomputed from scratch, as Unreal's {@code FillParentTags} does: two tags may share one. */
    private void rebuild() {
        List<CgGameplayTag> known = registry.all();
        long[] fresh = EMPTY;
        for (int w = 0; w < explicit.length; w++) {
            for (long bits = explicit[w]; bits != 0; bits &= bits - 1) {
                fresh = or(fresh, known.get((w << 6) + Long.numberOfTrailingZeros(bits)).bits());
            }
        }
        all = fresh;
    }

    private void adopt(CgTagRegistry of) {
        if (registry == null) registry = of;
        else if (registry != of) throw new CgTagException("a tag container holds one registry's tags");
    }

    private static boolean test(long[] bits, int i) {
        int w = i >> 6;
        return w < bits.length && (bits[w] & (1L << (i & 63))) != 0;
    }

    private static boolean intersects(long[] a, long[] b) {
        for (int i = 0, n = Math.min(a.length, b.length); i < n; i++) if ((a[i] & b[i]) != 0) return true;
        return false;
    }

    /** Whether every bit of {@code wanted} is in {@code have}; vacuously true for none. */
    private static boolean contains(long[] have, long[] wanted) {
        for (int i = 0; i < wanted.length; i++) {
            long need = wanted[i];
            if (need != 0 && (i >= have.length || (need & ~have[i]) != 0)) return false;
        }
        return true;
    }

    /** Just {@code tag}'s own bit. */
    private static long[] own(CgGameplayTag tag) {
        long[] bits = new long[(tag.index() >> 6) + 1];
        bits[tag.index() >> 6] = 1L << (tag.index() & 63);
        return bits;
    }

    private static long[] or(long[] a, long[] b) {
        if (b.length == 0) return a;
        long[] out = Arrays.copyOf(a, Math.max(a.length, b.length));
        for (int i = 0; i < b.length; i++) out[i] |= b[i];
        return out;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CgTagContainer)) return false;
        long[] b = ((CgTagContainer) other).explicit;
        int n = Math.max(explicit.length, b.length);
        for (int i = 0; i < n; i++) {
            if ((i < explicit.length ? explicit[i] : 0) != (i < b.length ? b[i] : 0)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = explicit.length - 1; i >= 0; i--) {
            if (explicit[i] == 0 && h == 1) continue;
            h = 31 * h + Long.hashCode(explicit[i]);
        }
        return h;
    }

    @Override
    public String toString() {
        return tags().toString();
    }
}
