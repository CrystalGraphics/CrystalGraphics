package com.crystalgraphics.object.layout;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.object.CgValueType;

/**
 * Values laid out by a {@link CgLayout}: what a definition's row and a playing instance read by offset.
 *
 * <pre>{@code
 * CgLayoutValues values = layout.values();          // defaults
 * values.set("strength", 9.8);                      // compile: by name, from a decoded input
 * values.setFloat(layout.bind(STRENGTH), 9.8f);     // play: by slot
 * }</pre>
 *
 * <ul>
 *   <li>Reads and writes by slot allocate nothing; {@link #set(String, Object)} is for compiling.</li>
 *   <li>A {@code bool} reads as 0 or 1, an enum as its value's index in the declaration.</li>
 * </ul>
 */
public final class CgLayoutValues {

    private final CgLayout layout;
    private final float[] floats;
    private final int[] ints;
    private final Object[] objects;

    CgLayoutValues(CgLayout layout) {
        this.layout = layout;
        this.floats = new float[layout.floats()];
        this.ints = new int[layout.ints()];
        this.objects = new Object[layout.objects()];
        for (CgLayout.Slot s : layout.slots()) {
            Object d = s.property().defaultValue();
            if (d != null) set(s, d);
        }
    }

    public CgLayout layout() {
        return layout;
    }

    /** The float at {@code offset} of the float array: a compiled reader's path. */
    public float floatAt(int offset) {
        return floats[offset];
    }

    /** The int at {@code offset} of the int array. */
    public int intAt(int offset) {
        return ints[offset];
    }

    public float getFloat(CgLayout.Slot slot) {
        return floats[slot.offset()];
    }

    /** Number {@code lane} of a vector slot. */
    public float getFloat(CgLayout.Slot slot, int lane) {
        return floats[slot.offset() + lane];
    }

    public int getInt(CgLayout.Slot slot) {
        return ints[slot.offset()];
    }

    public boolean getBool(CgLayout.Slot slot) {
        return ints[slot.offset()] != 0;
    }

    public Object getObject(CgLayout.Slot slot) {
        return objects[slot.offset()];
    }

    public CgLayoutValues setFloat(CgLayout.Slot slot, float value) {
        floats[slot.offset()] = value;
        return this;
    }

    public CgLayoutValues setFloat(CgLayout.Slot slot, int lane, float value) {
        floats[slot.offset() + lane] = value;
        return this;
    }

    public CgLayoutValues setInt(CgLayout.Slot slot, int value) {
        ints[slot.offset()] = value;
        return this;
    }

    public CgLayoutValues setBool(CgLayout.Slot slot, boolean value) {
        ints[slot.offset()] = value ? 1 : 0;
        return this;
    }

    public CgLayoutValues setObject(CgLayout.Slot slot, Object value) {
        objects[slot.offset()] = value;
        return this;
    }

    /** Sets {@code name} from a decoded value: a number, a boolean, an enum's value, a list of numbers or an object. */
    public CgLayoutValues set(String name, Object value) {
        CgLayout.Slot s = layout.slot(name);
        if (s == null) throw new IllegalArgumentException(layout.id() + " has no parameter '" + name + "'");
        set(s, value);
        return this;
    }

    /** Every entry of {@code values} by name, as {@link #set(String, Object)}. */
    public CgLayoutValues setAll(Map<?, ?> values) {
        for (Map.Entry<?, ?> e : values.entrySet()) set(String.valueOf(e.getKey()), e.getValue());
        return this;
    }

    private void set(CgLayout.Slot s, Object value) {
        String where = layout.id() + "." + s.name();
        switch (s.store()) {
            case FLOAT:
                if (s.width() == 1) {
                    floats[s.offset()] = number(value, where).floatValue();
                } else {
                    if (!(value instanceof List<?> list) || list.size() != s.width()) {
                        throw new IllegalArgumentException(where + " takes " + s.width() + " numbers, not " + value);
                    }
                    for (int i = 0; i < s.width(); i++) floats[s.offset() + i] = number(list.get(i), where).floatValue();
                }
                break;
            case INT:
                ints[s.offset()] = intOf(s, value, where);
                break;
            default:
                objects[s.offset()] = value;
        }
    }

    private static int intOf(CgLayout.Slot s, Object value, String where) {
        CgValueType type = s.property().type();
        if (type.kind() == CgValueType.Kind.BOOL) {
            if (!(value instanceof Boolean b)) throw new IllegalArgumentException(where + " takes true or false, not " + value);
            return b ? 1 : 0;
        }
        if (type.kind() == CgValueType.Kind.ENUM) {
            int i = type.enumValues().indexOf(value);
            if (i < 0) throw new IllegalArgumentException(where + " takes one of " + type.enumValues() + ", not " + value);
            return i;
        }
        return number(value, where).intValue();
    }

    private static Number number(Object value, String where) {
        if (!(value instanceof Number n)) throw new IllegalArgumentException(where + " takes a number, not " + value);
        return n;
    }

    @Override
    public String toString() {
        return layout.id() + Arrays.toString(floats) + Arrays.toString(ints) + Arrays.toString(objects);
    }
}
