package com.crystalgraphics.vfx.look;

import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The parameters one kind of effect reads, each with a built-in default: what its {@link CgVfxLook}s fill in and what
 * a playing effect may override. Declare it once per effect class, as static constants.
 *
 * <pre>{@code
 * static final CgVfxSchema SCHEMA = new CgVfxSchema();
 * public static final CgVfxParam SPEED = SCHEMA.scalar("speed", 40f);
 * public static final CgVfxParam GLOW = SCHEMA.color("glow", 0.2f, 0.45f, 1.4f, 1f);
 * public static final CgVfxParam BLAST_SHAKE = SCHEMA.shake("blastShake", CgCameraShakes.EXPLOSION);
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
    // Curves and shakes, by a parameter's offset.
    private final List<Object> objects = new ArrayList<>();
    private final List<CgNetField<?>> networked = new ArrayList<>();
    private final List<CgHitPart> parts = new ArrayList<>();

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
    public CgVfxParam curve(String name, CgKeyframes value) {
        return object(name, value, 0);
    }

    /** A camera shake the effect plays, read with {@code CgVfxValues.shake}: how a look changes what its moments feel like. */
    public CgVfxParam shake(String name, CgCameraShake value) {
        return object(name, value, -1);
    }

    public List<CgVfxParam> params() {
        return Collections.unmodifiableList(params);
    }

    /**
     * State an effect of this kind sends from the server's copy to its viewers, beyond its parameters: every cue playing
     * a look of this schema lists these fields, and the effect writes and reads them through its cue
     * ({@code CgVfxCue.Authoritative}, {@code CgVfxCue.Live}).
     *
     * <pre>{@code
     * public static final CgNetField<Float> FIRED_AT = CgNetField.ofFloat("wave/fired_at").fallback(Float.NaN);
     * static { SCHEMA.networked(FIRED_AT); }
     * }</pre>
     *
     * Before any cue playing it is declared.
     */
    public CgVfxSchema networked(CgNetField<?>... fields) {
        Collections.addAll(networked, fields);
        return this;
    }

    public List<CgNetField<?>> networked() {
        return Collections.unmodifiableList(networked);
    }

    /**
     * Declares a part of an effect of this kind that strikes: each hit it reports names its part, so a listener may hear
     * one part alone. A cue sends a part as its place here, so declare parts as constants beside the parameters.
     *
     * <pre>{@code
     * public static final CgHitPart PART_BOLT = SCHEMA.part("bolt"), PART_ARC = SCHEMA.part("arc");
     *
     * bolt.onHit(Bolt.PART_ARC, h -> chain(h.entity()));
     * }</pre>
     */
    public CgHitPart part(String name) {
        CgHitPart part = CgHitPart.of(name);
        parts.add(part);
        return part;
    }

    /** The part declared {@code index}-th; null out of range. */
    @Nullable
    public CgHitPart partAt(int index) {
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /** Where {@code part} was declared; -1 for null or another schema's. */
    public int indexOf(@Nullable CgHitPart part) {
        return part == null ? -1 : parts.indexOf(part);
    }

    /** Floats in a full set of values. */
    public int width() {
        return defaults.length;
    }

    float[] defaults() {
        return defaults.clone();
    }

    Object[] defaultObjects() {
        return objects.toArray();
    }

    private void unique(String name) {
        for (CgVfxParam existing : params) {
            if (existing.name.equals(name)) throw new IllegalArgumentException("Parameter '" + name + "' is already declared");
        }
    }

    private CgVfxParam object(String name, Object value, int size) {
        unique(name);
        CgVfxParam param = new CgVfxParam(this, name, objects.size(), size);
        objects.add(value);
        params.add(param);
        return param;
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
