package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The keep/reuse/evict policy behind {@link CgPreviewRenderer}'s targets, with the GL taken out.
 *
 * <h3>Why this is a separate class</h3>
 * <p>Purely so it can be tested. A render target can only be created against a live GL context, so a
 * renderer that allocated one inline would be untestable — and the eviction rule is precisely the kind
 * that fails <em>silently</em>: every symptom of a wrong LRU is a thumbnail that is blank or stale, which
 * is indistinguishable from one that simply has not rendered yet. Nothing would ever throw.</p>
 *
 * <p>So the policy is generic over what it hands out: {@link CgPreviewRenderer} supplies real
 * {@link CgPreviewTarget}s while a test supplies strings.</p>
 *
 * @param <T> the pooled resource
 */
public final class CgPreviewSlots<T> {

    private final int capacity;
    private final Supplier<T> factory;

    /** Access-ordered, so the eldest entry IS the least recently used one. */
    private final Map<String, T> live;
    private final Deque<T> free = new ArrayDeque<>();

    public CgPreviewSlots(int capacity, Supplier<T> factory) {
        if (capacity <= 0) throw new IllegalArgumentException("Capacity must be > 0, got " + capacity);
        this.capacity = capacity;
        this.factory = factory;
        this.live = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * The slot held for {@code key}, taking a free one, evicting, or allocating as needed.
     *
     * <p>Always marks the result most-recently-used, so anything acquired during a frame cannot be
     * evicted later in that same frame — otherwise a large visible set would evict targets it is still
     * in the middle of filling.</p>
     */
    public T acquire(String key) {
        T existing = live.get(key);
        if (existing != null) return existing;

        T slot = free.pollFirst();
        if (slot == null && live.size() >= capacity) slot = evictEldest();
        if (slot == null) slot = factory.get();
        live.put(key, slot);
        return slot;
    }

    /** The slot held for {@code key}, or null — without allocating or reordering. */
    @Nullable
    public T peek(String key) {
        // Deliberately not live.get(): that would count as an access and reorder the LRU, so merely
        // asking whether a preview exists would change which one is evicted next.
        for (Map.Entry<String, T> entry : live.entrySet()) {
            if (entry.getKey().equals(key)) return entry.getValue();
        }
        return null;
    }

    /** Returns a key's slot to the free list. No-op when it holds none. */
    public void release(String key) {
        T slot = live.remove(key);
        if (slot != null) free.addLast(slot);
    }

    /** Releases every key not in {@code keep} — the cull set is the render set. */
    public void retainOnly(Set<String> keep) {
        live.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            free.addLast(entry.getValue());
            return true;
        });
    }

    /**
     * {@link #retainOnly}, confined to keys under {@code prefix}.
     *
     * <h3>Why a prefix rather than a pool each</h3>
     *
     * <p>One pool now serves every {@link CgPreviewRenderer} in the context, so a renderer culling its
     * own invisible nodes must not evict another's. Keys are namespaced by their renderer and every
     * scoped operation says which namespace it means.</p>
     *
     * <p>A pool per renderer was the alternative and is what this replaced: with one visible graph and
     * five open, it held five capacities' worth of framebuffers to draw one graph — and closing a graph
     * <em>deleted</em> a pool, so a close/reopen cycle paid a full allocate on driver-serialised work.</p>
     */
    public void retainOnlyWithin(String prefix, Set<String> keep) {
        live.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) return false;
            if (keep.contains(entry.getKey())) return false;
            free.addLast(entry.getValue());
            return true;
        });
    }

    /**
     * Returns every slot under {@code prefix} to the free list — what a renderer does when it closes.
     *
     * <p><b>Releases, never deletes.</b> That is the whole point: the targets stay in the pool for
     * whatever asks next, so closing a graph is bookkeeping and reopening it allocates nothing.</p>
     */
    public void releaseAllWithin(String prefix) {
        live.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) return false;
            free.addLast(entry.getValue());
            return true;
        });
    }

    /** How many live keys sit under {@code prefix}. For tests and diagnostics. */
    public int liveCountWithin(String prefix) {
        int found = 0;
        for (String key : live.keySet()) {
            if (key.startsWith(prefix)) found++;
        }
        return found;
    }

    @Nullable
    private T evictEldest() {
        var iterator = live.entrySet().iterator();
        if (!iterator.hasNext()) return null;
        T eldest = iterator.next().getValue();
        iterator.remove();
        return eldest;
    }

    /** Every slot this policy is holding, live or free — for teardown. */
    public Collection<T> all() {
        var everything = new java.util.ArrayList<T>(live.size() + free.size());
        everything.addAll(live.values());
        everything.addAll(free);
        return everything;
    }

    public void clear() {
        live.clear();
        free.clear();
    }

    public int liveCount() {
        return live.size();
    }

    public int pooledCount() {
        return free.size();
    }
}
