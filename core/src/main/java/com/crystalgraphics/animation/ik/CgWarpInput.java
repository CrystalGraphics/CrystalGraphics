package com.crystalgraphics.animation.ik;

import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * A node's number that is a constant or read from a float variable each frame: a warping node's speed, scale or angle.
 *
 * <pre>{@code
 * CgWarpInput.of(1.5f)                       // always 1.5
 * CgWarpInput.of(SPEED)                      // the instance's SPEED this frame
 * }</pre>
 */
public record CgWarpInput(float constant, @Nullable CgAnimVariable<Float> variable) {

    public static CgWarpInput of(float constant) {
        return new CgWarpInput(constant, null);
    }

    public static CgWarpInput of(CgAnimVariable<Float> variable) {
        return new CgWarpInput(variable.defaultValue(), variable);
    }

    /** This frame's value in {@code g}. */
    public float get(CgAnimGraphInstance g) {
        return variable != null ? g.get(variable) : constant;
    }

    void reads(Consumer<CgAnimVariable<?>> each) {
        if (variable != null) each.accept(variable);
    }

    void write(CgAnimNodeWriter out, String key, float dflt) {
        out.alpha(key, constant, variable, dflt);
    }

    /** {@code m.key}: a number, or a float variable's name; {@code dflt} where absent. */
    @SuppressWarnings("unchecked")
    static CgWarpInput read(CgAnimNodeKind.Context c, String at, Map<?, ?> m, String key, float dflt) {
        Object o = m.get(key);
        if (o == null) return of(dflt);
        if (o instanceof String name) {
            CgAnimVariable<?> v = c.variable(at + "." + key, name);
            if (v.type() != CgAnimVariable.Type.FLOAT) throw new CgAssetException(at + "." + key + ": " + name + " is not a float");
            return of((CgAnimVariable<Float>) v);
        }
        return of(c.number(at + "." + key, o));
    }
}
