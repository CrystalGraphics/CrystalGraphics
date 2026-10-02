package com.crystalgraphics.vfx.look;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The parameters one kind of effect reads, each with a built-in default: what its {@link CgVfxLook}s fill in and what
 * a playing effect may override. Declare it once per effect class, as static constants.
 *
 * <pre>{@code
 * static final CgVfxSchema SCHEMA = new CgVfxSchema();
 * public static final CgVfxParam SPEED = SCHEMA.scalar("speed", 40f);
 * public static final CgVfxParam GLOW = SCHEMA.color("glow", 0.2f, 0.45f, 1.4f, 1f);
 * }</pre>
 *
 * <ul>
 *   <li>Declare every parameter before the first look is built: a look copies the defaults as they are then.</li>
 *   <li>Values are flat floats, so reading one allocates nothing.</li>
 * </ul>
 */
public final class CgVfxSchema {

    private final List<CgVfxParam> params = new ArrayList<>();
    private float[] defaults = new float[0];
    private final List<CgVfxCurve> curves = new ArrayList<>();

    public CgVfxParam scalar(String name, float value) {
        CgVfxParam param = declare(name, 1);
        defaults[param.offset] = value;
        return param;
    }

    /** A colour, linear rgb above 1 allowed, and a fourth value the reader decides (usually a strength). */
    public CgVfxParam color(String name, float r, float g, float b, float a) {
        CgVfxParam param = declare(name, 4);
        defaults[param.offset] = r;
        defaults[param.offset + 1] = g;
        defaults[param.offset + 2] = b;
        defaults[param.offset + 3] = a;
        return param;
    }

    /** A value over time, read with {@code CgVfxValues.curve}. */
    public CgVfxParam curve(String name, CgVfxCurve value) {
        unique(name);
        CgVfxParam param = new CgVfxParam(this, name, curves.size(), 0);
        curves.add(value);
        params.add(param);
        return param;
    }

    public List<CgVfxParam> params() {
        return Collections.unmodifiableList(params);
    }

    /** Floats in a full set of values. */
    public int width() {
        return defaults.length;
    }

    float[] defaults() {
        return defaults.clone();
    }

    CgVfxCurve[] defaultCurves() {
        return curves.toArray(new CgVfxCurve[0]);
    }

    private void unique(String name) {
        for (CgVfxParam existing : params) {
            if (existing.name.equals(name)) throw new IllegalArgumentException("Parameter '" + name + "' is already declared");
        }
    }

    private CgVfxParam declare(String name, int size) {
        unique(name);
        CgVfxParam param = new CgVfxParam(this, name, defaults.length, size);
        defaults = Arrays.copyOf(defaults, defaults.length + size);
        params.add(param);
        return param;
    }

    void check(CgVfxParam param) {
        if (param.schema != this) throw new IllegalArgumentException("'" + param.name + "' belongs to another effect");
    }
}
