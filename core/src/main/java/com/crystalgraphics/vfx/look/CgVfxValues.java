package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.camera.CgCameraShake;

/**
 * A value for every parameter of one {@link CgVfxSchema}: a look's defaults, or a playing effect's own copy of them.
 *
 * <pre>{@code
 * CgVfxValues values = look.copyValues();
 * values.set(CgEnergyWave.RADIUS, 0.9f);
 * float green = values.get(CgEnergyWave.CORE, 1);   // a colour's second component
 * float size = values.curve(CgEnergyWave.CHARGE_SIZE).at(0.5f);
 * values.set(CgEnergyWave.BLAST_SHAKE, CgCameraShakes.IMPACT);
 * }</pre>
 *
 * <ul>
 *   <li>Flat floats: reading one allocates nothing.</li>
 *   <li>A parameter of another schema throws.</li>
 * </ul>
 */
public final class CgVfxValues {

    private final CgVfxSchema schema;
    private final float[] values;
    // Curves and shakes.
    private final Object[] objects;

    CgVfxValues(CgVfxSchema schema, float[] values, Object[] objects) {
        this.schema = schema;
        this.values = values;
        this.objects = objects;
    }

    public CgVfxSchema schema() {
        return schema;
    }

    public float get(CgVfxParam param) {
        scalar(param);
        return values[param.offset];
    }

    /** Component {@code index} of a colour: 0 red, 1 green, 2 blue, 3 the fourth value. */
    public float get(CgVfxParam param, int index) {
        scalar(param);
        return values[param.offset + index];
    }

    public CgVfxValues set(CgVfxParam param, float value) {
        scalar(param);
        values[param.offset] = value;
        return this;
    }

    public CgVfxValues set(CgVfxParam param, float r, float g, float b, float a) {
        schema.check(param);
        if (param.size != 4) throw new IllegalArgumentException("'" + param.name + "' is not a colour");
        values[param.offset] = r;
        values[param.offset + 1] = g;
        values[param.offset + 2] = b;
        values[param.offset + 3] = a;
        return this;
    }

    public CgKeyframes curve(CgVfxParam param) {
        curveParam(param);
        return (CgKeyframes) objects[param.offset];
    }

    public CgVfxValues set(CgVfxParam param, CgKeyframes curve) {
        curveParam(param);
        objects[param.offset] = curve;
        return this;
    }

    public CgCameraShake shake(CgVfxParam param) {
        shakeParam(param);
        return (CgCameraShake) objects[param.offset];
    }

    public CgVfxValues set(CgVfxParam param, CgCameraShake shake) {
        shakeParam(param);
        objects[param.offset] = shake;
        return this;
    }

    private void scalar(CgVfxParam param) {
        schema.check(param);
        if (param.size <= 0) throw new IllegalArgumentException("'" + param.name + "' is not a number");
    }

    private void curveParam(CgVfxParam param) {
        schema.check(param);
        if (!param.isCurve()) throw new IllegalArgumentException("'" + param.name + "' is not a curve");
    }

    private void shakeParam(CgVfxParam param) {
        schema.check(param);
        if (!param.isShake()) throw new IllegalArgumentException("'" + param.name + "' is not a shake");
    }

    public CgVfxValues copy() {
        return new CgVfxValues(schema, values.clone(), objects.clone());
    }
}
