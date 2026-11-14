package com.crystalgraphics.render.meshpass;

import java.util.ArrayList;
import java.util.List;

/**
 * A mark a component carries for a processor to read: the per-primitive flags a custom mesh pass keys on. Registered
 * once by name; at most 32 exist.
 *
 * <pre>{@code
 * static final CgPrimitiveFlag HEAT_SOURCE = CgPrimitiveFlag.register("mymod:heat_source");
 * rock.flag(HEAT_SOURCE);                                   // the component joins it
 * if (primitive.has(HEAT_SOURCE)) out.draw(batch, THERMAL); // in a processor
 * }</pre>
 */
public final class CgPrimitiveFlag {

    private static final List<CgPrimitiveFlag> FLAGS = new ArrayList<>();

    private final String id;
    private final int bit;

    private CgPrimitiveFlag(String id, int bit) {
        this.id = id;
        this.bit = bit;
    }

    /** The flag named {@code id}, made at its first registration and the same one after. */
    public static synchronized CgPrimitiveFlag register(String id) {
        for (CgPrimitiveFlag f : FLAGS) if (f.id.equals(id)) return f;
        if (FLAGS.size() == 32) throw new IllegalStateException("32 primitive flags exist already; " + id + " is one too many");
        CgPrimitiveFlag flag = new CgPrimitiveFlag(id, 1 << FLAGS.size());
        FLAGS.add(flag);
        return flag;
    }

    public String id() {
        return id;
    }

    /** Its bit in a component's flags. */
    public int bit() {
        return bit;
    }

    @Override
    public String toString() {
        return id;
    }
}
