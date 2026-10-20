package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgPropertyPath;

/**
 * What a definition compiled from each value-only property: the property's path and the slot it became. The editor's
 * fast path and hot reload's value-only swap write a changed value straight into the live definition through it,
 * with no recompile.
 *
 * <pre>{@code
 * CgPatchTable.Builder patches = CgPatchTable.builder();
 * patches.add(CgPropertyPath.of("mymod:fade").key("curves").index(0).key("keys").index(1).index(1), 3, Effect.DERIVED);
 * CgPatchTable table = patches.build();
 *
 * CgPatchTable.Entry e = table.entry(path);          // null: the edit is structural, recompile
 * definition.patch(e, 0.5);                           // written in place; every playing copy reads it next step
 * }</pre>
 *
 * <ul>
 *   <li>A path absent from the table is structural: recompile and swap.</li>
 *   <li>A slot means what its definition says; only that definition's {@link CgPatchable#patch} interprets it.</li>
 * </ul>
 */
public final class CgPatchTable {

    /** What a patch costs beyond the write. */
    public enum Effect {
        /** The value is read as written. */
        VALUE,
        /** The definition recomputes what derives from it (auto tangents, bounds, books) as it patches. */
        DERIVED
    }

    /**
     * One patchable property.
     *
     * @param path   the property in the asset
     * @param slot   where it went in the definition
     * @param effect what a patch to it recomputes
     */
    public record Entry(CgPropertyPath path, int slot, Effect effect) {
    }

    /** An empty table: a definition nothing patches. */
    public static final CgPatchTable EMPTY = new CgPatchTable(Collections.emptyMap());

    private final Map<CgPropertyPath, Entry> entries;

    private CgPatchTable(Map<CgPropertyPath, Entry> entries) {
        this.entries = entries;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code path}'s entry, or null where an edit to it is structural. */
    @Nullable
    public Entry entry(CgPropertyPath path) {
        return entries.get(path);
    }

    /** Every entry, in the order compiled. */
    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /** Builds a table as a compiler goes. */
    public static final class Builder {
        private final Map<CgPropertyPath, Entry> entries = new LinkedHashMap<>();

        public Builder add(CgPropertyPath path, int slot, Effect effect) {
            entries.put(path, new Entry(path, slot, effect));
            return this;
        }

        public CgPatchTable build() {
            return entries.isEmpty() ? EMPTY : new CgPatchTable(Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        }
    }
}
