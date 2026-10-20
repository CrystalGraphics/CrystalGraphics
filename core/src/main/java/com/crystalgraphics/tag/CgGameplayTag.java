package com.crystalgraphics.tag;

import javax.annotation.Nullable;

/**
 * A hierarchical name, {@code namespace:a.b.c}, interned by its {@link CgTagRegistry}: one instance per name, so tags
 * compare with {@code ==}. A tag <em>matches</em> itself and every ancestor: {@code mod:cue.beam.fire} matches
 * {@code mod:cue.beam} and {@code mod:cue}, never the reverse. Mirrors Unreal's {@code FGameplayTag}.
 *
 * <pre>{@code
 * CgGameplayTag fire = CgTagRegistry.global().register("mymod:cue.beam.fire");
 * CgGameplayTag beam = CgTagRegistry.global().find("mymod:cue.beam");   // made too: a tag's ancestors always exist
 *
 * fire.matches(beam);   // true
 * beam.matches(fire);   // false
 * }</pre>
 *
 * <p>Easy to get wrong: {@link #index()} is this process's registration order and differs between mod sets, so it
 * never goes on a wire; the net index of {@link CgTagLink} does.</p>
 */
public final class CgGameplayTag implements Comparable<CgGameplayTag> {

    private final CgTagRegistry registry;
    private final String name;
    private final String namespace;
    private final int index;
    private final int depth;
    @Nullable
    private final CgGameplayTag parent;
    /** Bit {@code i} set for this tag and each ancestor, by {@link #index()}. Ancestors are registered first. */
    private final long[] self;
    private volatile boolean explicit;

    CgGameplayTag(CgTagRegistry registry, String name, int index, @Nullable CgGameplayTag parent, boolean explicit) {
        this.registry = registry;
        this.name = name;
        this.namespace = name.substring(0, name.indexOf(':'));
        this.index = index;
        this.parent = parent;
        this.depth = parent == null ? 1 : parent.depth + 1;
        this.explicit = explicit;
        long[] bits = new long[(index >> 6) + 1];
        if (parent != null) System.arraycopy(parent.self, 0, bits, 0, parent.self.length);
        bits[index >> 6] |= 1L << (index & 63);
        this.self = bits;
    }

    /** The full name, as first registered. */
    public String name() {
        return name;
    }

    /** The part before the colon: the declaring mod's. */
    public String namespace() {
        return namespace;
    }

    /** The direct parent, or null for a top-level tag ({@code mod:cue}). */
    @Nullable
    public CgGameplayTag parent() {
        return parent;
    }

    /** Segments in the name: {@code mod:a.b.c} is 3. */
    public int depth() {
        return depth;
    }

    /** Registration order in this process: dense, from 0. Not for a wire. */
    public int index() {
        return index;
    }

    /** False for a tag that exists only as another's ancestor. */
    public boolean explicit() {
        return explicit;
    }

    void explicit(boolean on) {
        explicit = on;
    }

    /** Whether this is {@code other} or one of its descendants. False for null and for another registry's tag. */
    public boolean matches(@Nullable CgGameplayTag other) {
        if (other == null || other.registry != registry) return false;
        int word = other.index >> 6;
        return word < self.length && (self[word] & (1L << (other.index & 63))) != 0;
    }

    /** This tag's and its ancestors' bits, for the container. Not to be modified. */
    long[] bits() {
        return self;
    }

    CgTagRegistry registry() {
        return registry;
    }

    /** By name. */
    @Override
    public int compareTo(CgGameplayTag other) {
        return name.compareToIgnoreCase(other.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
