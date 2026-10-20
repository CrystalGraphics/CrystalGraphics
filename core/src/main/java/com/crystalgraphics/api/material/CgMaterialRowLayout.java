package com.crystalgraphics.api.material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.github.bsideup.jabel.Desugar;

/**
 * Where each row property of an archetype sits in a draw's row: its numeric properties under {@code cg_use rows}, in
 * declaration order, a vec4 each and an array one an element. What a renderer's or a track's {@code bind} writes.
 *
 * <pre>{@code
 * CgMaterialRowLayout rows = definition.rows();
 * int at = rows.slot("_Color");     // its vec4 in the row; -1 when the archetype cannot read it per draw
 * int size = rows.vec4s();          // the row's length
 * }</pre>
 *
 * @param slots each row property's first vec4, in declaration order
 * @param vec4s the row's length in vec4s
 */
@Desugar
public record CgMaterialRowLayout(Map<String, Integer> slots, int vec4s) {

    /** The layout of an archetype declaring none: nothing is read per draw. */
    public static final CgMaterialRowLayout NONE = new CgMaterialRowLayout(Collections.emptyMap(), 0);

    /** The row layout of {@code properties}, an archetype's parsed {@code Properties}. */
    public static CgMaterialRowLayout of(List<CgMaterialProperty> properties) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        int at = 0;
        for (CgMaterialProperty p : properties) {
            if (!p.isRows() || p.isHidden() || !p.isRowable()) continue;
            slots.put(p.getName(), at);
            at += p.getCount();
        }
        return slots.isEmpty() ? NONE : new CgMaterialRowLayout(Collections.unmodifiableMap(slots), at);
    }

    /** {@code property}'s first vec4, -1 when it is not a row property. */
    public int slot(String property) {
        Integer s = slots.get(property);
        return s == null ? -1 : s;
    }

    /** The row properties, in declaration order. */
    public List<String> properties() {
        return new ArrayList<>(slots.keySet());
    }
}
