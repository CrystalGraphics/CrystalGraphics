package com.crystalgraphics.vfx.look;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.camera.CgCameraShake;

import java.util.Arrays;

/**
 * A value for every parameter of a {@link CgVfxLayout}: a look's, or a playing effect's own copy of its look's.
 *
 * <pre>{@code
 * CgVfxValues values = look.copyValues();
 * values.set(CgEnergyWave.RADIUS, 0.9f);              // and everything following RADIUS follows
 * float green = values.get(CgEnergyWave.CORE, 1);     // a colour's second component
 * float band = values.get(MyLook.BANDS, 2, 0);        // the third colour's red
 * values.link(MyLook.REACH, CgEnergyWave.RADIUS, 4f); // REACH follows RADIUS × 4 from now on
 * }</pre>
 *
 * <ul>
 *   <li>Flat floats: reading allocates nothing and costs two array reads.</li>
 *   <li>Setting a number breaks the link it followed; {@link #link} makes one.</li>
 *   <li>A parameter of a schema the layout does not include throws.</li>
 * </ul>
 */
public final class CgVfxValues {

    private final CgVfxLayout layout;
    private final float[] values;
    private final Object[] objects;
    /** The scalars following another: each one's offset, its source's and the factor, sources before followers. */
    private int[] linkTo, linkFrom;
    private float[] linkFactor;
    private int links;

    CgVfxValues(CgVfxLayout layout) {
        this(layout, layout.defaults(), layout.defaultObjects(), layout.linkTo.clone(), layout.linkFrom.clone(),
                layout.linkFactor.clone(), layout.linkTo.length);
        order();
        resolve();
    }

    private CgVfxValues(CgVfxLayout layout, float[] values, Object[] objects, int[] linkTo, int[] linkFrom,
                        float[] linkFactor, int links) {
        this.layout = layout;
        this.values = values;
        this.objects = objects;
        this.linkTo = linkTo;
        this.linkFrom = linkFrom;
        this.linkFactor = linkFactor;
        this.links = links;
    }

    public CgVfxLayout layout() {
        return layout;
    }

    public float get(CgVfxParam.Scalar param) {
        return values[layout.offset(param)];
    }

    /** Component {@code index} of a colour: 0 red, 1 green, 2 blue, 3 the fourth value. */
    public float get(CgVfxParam.Color param, int index) {
        return values[layout.offset(param) + index];
    }

    /** Component {@code index} of a vector: 0 x, 1 y, 2 z. */
    public float get(CgVfxParam.Vector param, int index) {
        return values[layout.offset(param) + index];
    }

    /** Component {@code component} of colour {@code entry} of a palette. */
    public float get(CgVfxParam.Palette param, int entry, int component) {
        return values[layout.offset(param) + entry * 4 + component];
    }

    public CgKeyframes curve(CgVfxParam.Curve param) {
        return (CgKeyframes) objects[layout.offset(param)];
    }

    public CgCameraShake shake(CgVfxParam.Shake param) {
        return (CgCameraShake) objects[layout.offset(param)];
    }

    /** Sets a number, breaking any link it followed; what follows it follows. */
    public CgVfxValues set(CgVfxParam.Scalar param, float value) {
        int at = layout.offset(param);
        unlink(at);
        values[at] = value;
        resolve();
        return this;
    }

    public CgVfxValues set(CgVfxParam.Color param, float r, float g, float b, float a) {
        int at = layout.offset(param);
        values[at] = r;
        values[at + 1] = g;
        values[at + 2] = b;
        values[at + 3] = a;
        return this;
    }

    public CgVfxValues set(CgVfxParam.Vector param, float x, float y, float z) {
        int at = layout.offset(param);
        values[at] = x;
        values[at + 1] = y;
        values[at + 2] = z;
        return this;
    }

    /** {@code 0xAARRGGBB}. */
    public CgVfxValues set(CgVfxParam.Color param, int argb) {
        return set(param, argb, 1f);
    }

    /** {@code 0xAARRGGBB}, its rgb times {@code intensity}: past white where above 1. */
    public CgVfxValues set(CgVfxParam.Color param, int argb, float intensity) {
        return set(param, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb));
    }

    /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}. */
    public CgVfxValues set(CgVfxParam.Palette param, int entry, int argb) {
        return set(param, entry, argb, 1f);
    }

    /** Colour {@code entry} of a palette, {@code 0xAARRGGBB}, its rgb times {@code intensity}. */
    public CgVfxValues set(CgVfxParam.Palette param, int entry, int argb, float intensity) {
        return set(param, entry, CgVfxParam.Color.red(argb) * intensity, CgVfxParam.Color.green(argb) * intensity,
                CgVfxParam.Color.blue(argb) * intensity, CgVfxParam.Color.alpha(argb));
    }

    /** Sets colour {@code entry} of a palette. */
    public CgVfxValues set(CgVfxParam.Palette param, int entry, float r, float g, float b, float a) {
        if (entry < 0 || entry >= param.count) throw new IndexOutOfBoundsException(param + " has " + param.count + " colours");
        int at = layout.offset(param) + entry * 4;
        values[at] = r;
        values[at + 1] = g;
        values[at + 2] = b;
        values[at + 3] = a;
        return this;
    }

    public CgVfxValues set(CgVfxParam.Curve param, CgKeyframes curve) {
        objects[layout.offset(param)] = curve;
        return this;
    }

    public CgVfxValues set(CgVfxParam.Shake param, CgCameraShake shake) {
        objects[layout.offset(param)] = shake;
        return this;
    }

    /** Makes {@code param} follow {@code source × factor} from now on. A cycle throws. */
    public CgVfxValues link(CgVfxParam.Scalar param, CgVfxParam.Scalar source, float factor) {
        int to = layout.offset(param), from = layout.offset(source);
        unlink(to);
        if (links == linkTo.length) {
            int n = Math.max(4, links * 2);
            linkTo = Arrays.copyOf(linkTo, n);
            linkFrom = Arrays.copyOf(linkFrom, n);
            linkFactor = Arrays.copyOf(linkFactor, n);
        }
        linkTo[links] = to;
        linkFrom[links] = from;
        linkFactor[links] = factor;
        links++;
        order();
        resolve();
        return this;
    }

    /** Whether {@code param} follows another now. */
    public boolean linked(CgVfxParam.Scalar param) {
        int at = layout.offset(param);
        for (int i = 0; i < links; i++) if (linkTo[i] == at) return true;
        return false;
    }

    /** The raw float at {@code offset}: a tool's, and the rows'. */
    public float raw(int offset) {
        return values[offset];
    }

    /** Copies {@code count} floats from {@code offset} into {@code into} at {@code at}. */
    public void copy(int offset, int count, float[] into, int at) {
        System.arraycopy(values, offset, into, at, count);
    }

    /** Sets the float at {@code offset}, with no link broken: a tween's, a cue's, a tool's write of what it was. */
    public void setRaw(int offset, float value) {
        values[offset] = value;
        resolve();
    }

    /** Multiplies every number in a scaling unit by {@code s}: lengths and speeds. Links keep following. */
    void scale(float s) {
        for (CgVfxParam p : layout.params()) {
            if (p instanceof CgVfxParam.Scalar && ((CgVfxParam.Scalar) p).unit.scales()) values[layout.offset(p)] *= s;
        }
        resolve();
    }

    public CgVfxValues copy() {
        return new CgVfxValues(layout, values.clone(), objects.clone(), linkTo.clone(), linkFrom.clone(),
                linkFactor.clone(), links);
    }

    /**
     * Takes every value and link of {@code from} in place, allocating nothing once its links fit: a pooled effect
     * starting again from its look.
     *
     * <pre>{@code
     * values.set(look.values());   // as look.copyValues() would make them, into these arrays
     * }</pre>
     *
     * @throws IllegalArgumentException when {@code from} lays its values out otherwise
     */
    public void set(CgVfxValues from) {
        if (from.layout != layout) throw new IllegalArgumentException("another layout's values");
        System.arraycopy(from.values, 0, values, 0, values.length);
        System.arraycopy(from.objects, 0, objects, 0, objects.length);
        if (linkTo.length < from.links) {
            linkTo = new int[from.links];
            linkFrom = new int[from.links];
            linkFactor = new float[from.links];
        }
        System.arraycopy(from.linkTo, 0, linkTo, 0, from.links);
        System.arraycopy(from.linkFrom, 0, linkFrom, 0, from.links);
        System.arraycopy(from.linkFactor, 0, linkFactor, 0, from.links);
        links = from.links;
    }

    /**
     * Keeps over these what {@code current} changed from {@code was}'s own values: a playing effect's values carried
     * onto a newer look, as Unreal's component keeps its user parameter overrides through a reinit. A value it never
     * changed takes the newer look's; links it made or broke keep too.
     *
     * <pre>{@code
     * CgVfxValues carried = next.copyValues();
     * carried.keep(effect.values(), previousLook);
     * }</pre>
     *
     * @return false, keeping nothing, when {@code was} or {@code current} is not these values' size: another layout
     */
    public boolean keep(CgVfxValues current, CgVfxLook was) {
        CgVfxValues base = was.values();
        if (base.values.length != values.length || base.objects.length != objects.length
                || current.values.length != values.length || current.objects.length != objects.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if (Float.floatToIntBits(current.values[i]) != Float.floatToIntBits(base.values[i])) values[i] = current.values[i];
        }
        for (int i = 0; i < objects.length; i++) if (current.objects[i] != base.objects[i]) objects[i] = current.objects[i];
        if (!sameLinks(current, base)) {
            linkTo = Arrays.copyOf(current.linkTo, current.links);
            linkFrom = Arrays.copyOf(current.linkFrom, current.links);
            linkFactor = Arrays.copyOf(current.linkFactor, current.links);
            links = current.links;
        }
        resolve();
        return true;
    }

    private static boolean sameLinks(CgVfxValues a, CgVfxValues b) {
        if (a.links != b.links) return false;
        for (int i = 0; i < a.links; i++) {
            if (a.linkTo[i] != b.linkTo[i] || a.linkFrom[i] != b.linkFrom[i] || a.linkFactor[i] != b.linkFactor[i]) return false;
        }
        return true;
    }

    private void unlink(int offset) {
        for (int i = 0; i < links; i++) {
            if (linkTo[i] != offset) continue;
            links--;
            System.arraycopy(linkTo, i + 1, linkTo, i, links - i);
            System.arraycopy(linkFrom, i + 1, linkFrom, i, links - i);
            System.arraycopy(linkFactor, i + 1, linkFactor, i, links - i);
            return;
        }
    }

    /** Puts every link after the links its source follows, so one pass resolves chains. */
    private void order() {
        for (int pass = 0; pass <= links; pass++) {
            boolean moved = false;
            for (int i = 0; i < links; i++) {
                for (int j = i + 1; j < links; j++) {
                    if (linkTo[j] != linkFrom[i]) continue;
                    swap(i, j);
                    moved = true;
                }
            }
            if (!moved) return;
        }
        throw new IllegalArgumentException("A cycle of linked parameters in " + layout);
    }

    private void swap(int i, int j) {
        int t = linkTo[i]; linkTo[i] = linkTo[j]; linkTo[j] = t;
        t = linkFrom[i]; linkFrom[i] = linkFrom[j]; linkFrom[j] = t;
        float f = linkFactor[i]; linkFactor[i] = linkFactor[j]; linkFactor[j] = f;
    }

    private void resolve() {
        for (int i = 0; i < links; i++) values[linkTo[i]] = values[linkFrom[i]] * linkFactor[i];
    }
}
