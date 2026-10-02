package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgKeyframes;

/**
 * One value an effect reads, declared on its {@link CgVfxSchema}: a scalar, a colour or a {@link CgKeyframes}. A {@link CgVfxLook} gives each
 * its default, and a playing {@link CgVfxEffect} may override it.
 *
 * <pre>{@code
 * static final CgVfxSchema SCHEMA = new CgVfxSchema();
 * public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.6f);
 * public static final CgVfxParam CORE = SCHEMA.color("core", 1f, 1f, 1f, 1f);
 *
 * wave.set(RADIUS, 0.9f);              // this wave only
 * float r = wave.get(RADIUS);
 * }</pre>
 */
public final class CgVfxParam {

    final CgVfxSchema schema;
    final String name;
    final int offset;
    final int size;

    CgVfxParam(CgVfxSchema schema, String name, int offset, int size) {
        this.schema = schema;
        this.name = name;
        this.offset = offset;
        this.size = size;
    }

    public String name() {
        return name;
    }

    /** 1 for a scalar, 4 for a colour, 0 for a curve. */
    public int size() {
        return size;
    }

    public boolean isCurve() {
        return size == 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
