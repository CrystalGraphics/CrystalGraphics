package com.crystalgraphics.sequence.state;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.sequence.CgSequenceTarget;

/** A parameter collection as a global track's target: its scalars and vectors by name. */
final class CgSequenceGlobals implements CgSequenceTarget {

    private final CgParameterCollection collection;
    private final List<Object> slots = new ArrayList<>();
    private final float[] current = new float[4];

    CgSequenceGlobals(CgParameterCollection collection) {
        this.collection = collection;
    }

    @Override
    public int slot(String name) {
        Object p = collection.scalarNamed(name);
        if (p == null) p = collection.vectorNamed(name);
        if (p == null) return -1;
        int had = slots.indexOf(p);
        if (had >= 0) return had;
        slots.add(p);
        return slots.size() - 1;
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        if (slots.get(slot) instanceof CgParameterCollection.Scalar s) {
            s.set(values[0]);
            return;
        }
        CgParameterCollection.Vector v = (CgParameterCollection.Vector) slots.get(slot);
        v.get(current);
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) current[i] = values[i];
        v.set(current[0], current[1], current[2], current[3]);
    }

    @Override
    public void get(int slot, float[] out) {
        if (slots.get(slot) instanceof CgParameterCollection.Scalar s) out[0] = s.get();
        else ((CgParameterCollection.Vector) slots.get(slot)).get(out);
    }

    @Override
    public String toString() {
        return "collection " + collection.namespace();
    }
}
