package com.crystalgraphics.vfx.look;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A look's numbers and colours as variables while a tool shows them: set one from the console, a tuning panel or a
 * remote tool, and the look takes it, every effect playing the look takes it at its next tick, and every one played
 * after starts from it.
 *
 * <pre>{@code
 * CgVfxTuning tuning = CgVfxTuning.open("vfx.crystalgraphics:kamehameha", CgEnergyWave.kamehameha());
 * CgVars.get("vfx.crystalgraphics:kamehameha.radius").setText(CgVar.Source.CONSOLE, "0.9");
 * CgVars.get("vfx.crystalgraphics:kamehameha.core.g").setText(CgVar.Source.CONSOLE, "0.8");   // a colour is r, g, b, a
 * tuning.close();   // the variables go; the look keeps what was set
 * }</pre>
 *
 * <ul>
 *   <li>Curves and shakes are not variables: they have no text form.</li>
 *   <li>A tuned value wins over one an effect set for itself with {@code set}.</li>
 *   <li>One tuning a prefix at a time: a second {@link #open} under the same prefix throws.</li>
 *   <li>Clearing a variable puts back the value the look had when it was opened.</li>
 * </ul>
 */
public final class CgVfxTuning implements AutoCloseable {

    private static final String[] COMPONENTS = {"r", "g", "b", "a"};

    private final List<CgVar<?>> variables = new ArrayList<>();

    private CgVfxTuning() {
    }

    /** Declares a variable {@code <prefix>.<parameter>} for each number of {@code look}, and four for each colour. */
    public static CgVfxTuning open(String prefix, CgVfxLook look) {
        CgVfxTuning tuning = new CgVfxTuning();
        CgVfxValues values = look.values();
        try {
            for (CgVfxParam param : look.schema().params()) {
                if (param.size == 1) {
                    tuning.declare(values, prefix + "." + param.name, param.offset, param.name + " of " + prefix + ".");
                } else if (param.size == 4) {
                    for (int i = 0; i < 4; i++) {
                        tuning.declare(values, prefix + "." + param.name + "." + COMPONENTS[i], param.offset + i,
                                "Colour " + param.name + " of " + prefix + ", its " + COMPONENTS[i] + ".");
                    }
                }
            }
        } catch (RuntimeException e) {
            tuning.close();
            throw e;
        }
        return tuning;
    }

    private void declare(CgVfxValues values, String id, int offset, String description) {
        CgVar.Num v = CgVar.number(id, values.raw(offset), Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, CgVar.DEV,
                description);
        variables.add(v);
        v.onChange(value -> values.tune(offset, value));
        // A -D value was taken at declaration, before the hook.
        if (v.get() != values.raw(offset)) values.tune(offset, v.get());
    }

    /** Its variables, in the schema's order. */
    public List<CgVar<?>> variables() {
        return Collections.unmodifiableList(variables);
    }

    @Override
    public void close() {
        for (CgVar<?> v : variables) CgVars.unregister(v);
        variables.clear();
    }
}
