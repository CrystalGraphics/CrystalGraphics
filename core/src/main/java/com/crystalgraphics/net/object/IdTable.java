package com.crystalgraphics.net.object;

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * Objects by id without boxing: open addressing, linear probing, backward-shift deletion. Ids are positive; 0 marks an
 * empty slot.
 *
 * <pre>{@code
 * table.put(obj.id, obj);
 * for (int s = 0; s < table.capacity(); s++) { CgNetObject obj = table.at(s); if (obj != null) tick(obj); }
 * }</pre>
 *
 * <p>Never put or remove while walking the slots: either may move entries.</p>
 */
final class IdTable {

    private int[] keys = new int[16];
    private CgNetObject[] values = new CgNetObject[16];
    private int size;

    int size() {
        return size;
    }

    @Nullable
    CgNetObject get(int id) {
        int mask = keys.length - 1;
        for (int i = home(id, mask); ; i = i + 1 & mask) {
            int key = keys[i];
            if (key == id) return values[i];
            if (key == 0) return null;
        }
    }

    /** Answers what {@code id} held before, or null. */
    @Nullable
    CgNetObject put(int id, CgNetObject obj) {
        if (id <= 0) throw new IllegalArgumentException("an object id is positive: " + id);
        if (size * 2 >= keys.length) grow();
        int mask = keys.length - 1;
        int i = home(id, mask);
        while (keys[i] != 0 && keys[i] != id) i = i + 1 & mask;
        CgNetObject previous = values[i];
        if (keys[i] == 0) size++;
        keys[i] = id;
        values[i] = obj;
        return previous;
    }

    @Nullable
    CgNetObject remove(int id) {
        int mask = keys.length - 1;
        int gap = home(id, mask);
        while (keys[gap] != id) {
            if (keys[gap] == 0) return null;
            gap = gap + 1 & mask;
        }
        CgNetObject removed = values[gap];
        // Pull back each later entry of the run whose home is at or before the gap, so no probe stops short.
        for (int j = gap + 1 & mask; keys[j] != 0; j = j + 1 & mask) {
            if ((j - home(keys[j], mask) & mask) >= (j - gap & mask)) {
                keys[gap] = keys[j];
                values[gap] = values[j];
                gap = j;
            }
        }
        keys[gap] = 0;
        values[gap] = null;
        size--;
        return removed;
    }

    void clear() {
        Arrays.fill(keys, 0);
        Arrays.fill(values, null);
        size = 0;
    }

    /** Slots to walk with {@link #at}. */
    int capacity() {
        return keys.length;
    }

    /** The object in {@code slot}, or null where it is empty. */
    @Nullable
    CgNetObject at(int slot) {
        return values[slot];
    }

    private void grow() {
        int[] oldKeys = keys;
        CgNetObject[] oldValues = values;
        keys = new int[oldKeys.length * 2];
        values = new CgNetObject[oldKeys.length * 2];
        int mask = keys.length - 1;
        for (int s = 0; s < oldKeys.length; s++) {
            if (oldKeys[s] == 0) continue;
            int i = home(oldKeys[s], mask);
            while (keys[i] != 0) i = i + 1 & mask;
            keys[i] = oldKeys[s];
            values[i] = oldValues[s];
        }
    }

    /** Fibonacci hashing: sequential ids spread across the table. */
    private static int home(int id, int mask) {
        int h = id * 0x9E3779B9;
        return (h ^ h >>> 16) & mask;
    }
}
