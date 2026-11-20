package com.crystalgraphics.api.paint.effect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.crystalgraphics.api.material.CgMaterialValues;

/**
 * What a pane does to what is behind it: CSS's {@code backdrop-filter}, a {@link CgFilter} chain over the captured
 * backdrop, then, past CSS, a {@link Lens} at the pane's edge. A box on the canvas or a quad in the world takes
 * one, and sees anything already drawn behind it, other panes included.
 *
 * <pre>{@code
 * // CSS's backdrop-filter: grayscale(1) contrast(1.2) blur(8px)
 * CgBackdropFilter muted = CgBackdropFilter.of(CgFilter.grayscale(1f).then(CgFilter.contrast(1.2f)).then(CgFilter.blur(8f)));
 * canvas.box(x, y, w, h).radius(12).backdropFilter(muted).draw();
 *
 * // glass: a chain, then a lens
 * CgBackdropFilter liquid = CgBackdropFilter.of(CgFilter.blur(4f).then(CgFilter.saturate(1.8f))).lens(Lens.LIQUID);
 * canvas.box(x, y, w, h).radius(24).backdropFilter(liquid).draw();
 * }</pre>
 *
 * <pre>{@code
 * // a preset, and a preset tuned: each edit changes its own step and keeps the rest
 * canvas.box(x, y, w, h).radius(12).backdropFilter(CgBackdropFilter.ACRYLIC).draw();
 * CgBackdropFilter warm = CgBackdropFilter.FROSTED.tint(0x20FF8040).blur(6f).lens(Lens.CLEAR.grain(0.02f));
 * float sigma = warm.blur(), saturation = warm.saturation();      // read back for a slider
 *
 * // in the world, the lens's lengths in blocks: tuned in pixels, a block 32 of them
 * scene.quad(2f, 1f).radii(0.25f).backdropFilter(CgBackdropFilter.FROSTED.scaled(1 / 32f)).at(x, y, z).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>The chain takes blurs, colour adjustments ({@code saturate}, {@code contrast}, {@code tint}, any matrix) and
 *       one {@code luminosity}; blurs before the luminosity. A drop shadow or a material is a layer's: refused here,
 *       named.</li>
 *   <li>A blur is a Gaussian's sigma in the target's pixels, as {@code CgFilter.blur} and CSS's {@code blur()}, in the
 *       world as on the screen; the lens's lengths are the pane's units, so {@link #scaled} scales the lens alone.</li>
 *   <li>It costs what a pane did: one capture, its blur ladder, one pass, the colour steps folded into that pass.</li>
 *   <li>Each pane captures what is behind it when its turn comes; panes that do not overlap share one capture.</li>
 *   <li>{@link #blur}, {@link #saturation}, {@link #luminosity} and {@link #tint} read the chain's step of that function
 *       (its identity where there is none); their withers change that step where it is, or add it where acrylic puts it:
 *       blur first, saturation after the blur, luminosity before the tint, the tint last. The luminosity is toward the
 *       tint's brightness, so a new tint moves it too.</li>
 *   <li>{@link #fallback} is what a pane paints where there is nothing to filter: inside a kept layer.</li>
 * </ul>
 */
public final class CgBackdropFilter {

    /** Acrylic's {@code FallbackColor}. */
    private static final int FALLBACK = 0xFF2B2D30;

    /** No filter, CSS's {@code backdrop-filter: none}: a box given it captures nothing and draws no pane. */
    public static final CgBackdropFilter NONE = new CgBackdropFilter(CgFilter.NONE, Lens.NONE, 0);
    /** A pane over a blur, a lens at its edge, lit and grained: what a pane is when nothing says otherwise. */
    public static final CgBackdropFilter DEFAULT = acrylic(4f, 1.35f, 0f, 0x662B2D30).lens(Lens.DEFAULT);
    /** Plain glass: no blur, a gentle lens, a faint highlight. */
    public static final CgBackdropFilter CLEAR = acrylic(0f, 1f, 0f, 0).lens(Lens.CLEAR);
    /** Frosted: a wide blur under a light tint, lit, no lens. */
    public static final CgBackdropFilter FROSTED = acrylic(8f, 1.2f, 0f, 0x1FFFFFFF)
            .lens(Lens.of(0f, 1.5f, 0.20f, 0.3f, 0.10f, 0.25f, 3f, 0.5f, 0.05f));
    /** Apple's liquid glass: a little blur, a wide strong lens, a bright rim and some dispersion. */
    public static final CgBackdropFilter LIQUID = acrylic(4f / 3f, 1.8f, 0f, 0x14FFFFFF).lens(Lens.LIQUID);
    /** Windows' acrylic: luminosity takes the tint's brightness, so a dark tint is a dark pane over anything. */
    public static final CgBackdropFilter ACRYLIC = acrylic(10f, 1.5f, 0.1f, 0x401F1F23)
            .lens(Lens.NONE.grain(0.03f));
    /** Windows' Mica: a very wide blur under a dark tint, its brightness mostly the tint's. */
    public static final CgBackdropFilter MICA = acrylic(40f / 3f, 1.2f, 0.55f, 0xBF202124);
    /** A dispersive prism: a pixel of blur under a lens that splits colours rather than displaces them. */
    public static final CgBackdropFilter PRISM = acrylic(1f / 3f, 1.2f, 0f, 0).lens(Lens.PRISM);

    private final CgFilter filter;
    private final Lens lens;
    private final int fallback;
    private final Plan plan;

    private CgBackdropFilter(CgFilter filter, Lens lens, int fallback) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.lens = Objects.requireNonNull(lens, "lens");
        this.fallback = fallback;
        this.plan = Plan.of(filter);
    }

    /**
     * {@code filter} over the backdrop, no lens.
     *
     * @throws IllegalArgumentException naming the step, for a drop shadow, a material, a blur after the luminosity, a
     *                                  second luminosity or a blur not the same across and down
     */
    public static CgBackdropFilter of(CgFilter filter) {
        return new CgBackdropFilter(filter, Lens.NONE, FALLBACK);
    }

    /**
     * Fluent acrylic's recipe: blurred by {@code blur}, saturated, its brightness taken toward the tint's by
     * {@code luminosity}, then {@code tint} laid over. What the presets are. A step that changes nothing (no blur,
     * saturation 1, no luminosity, a clear tint) is left out of its chain.
     */
    public static CgBackdropFilter acrylic(float blur, float saturation, float luminosity, int tint) {
        CgFilter f = blur > 0f ? CgFilter.blur(blur) : CgFilter.NONE;
        if (saturation != 1f) f = f.then(CgFilter.saturate(saturation));
        if (luminosity > 0f) f = f.then(CgFilter.luminosity(tint, luminosity));
        if ((tint >>> 24) != 0) f = f.then(CgFilter.tint(tint));
        return of(f);
    }

    /** This filter with {@code lens} at the pane's edge; {@link Lens#NONE} for none. */
    public CgBackdropFilter lens(Lens lens) {
        return new CgBackdropFilter(filter, lens, fallback);
    }

    /** This filter painting {@code argb} where there is nothing to filter: inside a kept layer. */
    public CgBackdropFilter fallback(int argb) {
        return new CgBackdropFilter(filter, lens, argb);
    }

    /** Its lens's lengths times {@code unitsPerPixel}, for a pane measured in other units; the blur stays the target's. */
    public CgBackdropFilter scaled(float unitsPerPixel) {
        return new CgBackdropFilter(filter, lens.scaled(unitsPerPixel), fallback);
    }

    /** The chain over the backdrop. */
    public CgFilter filter() {
        return filter;
    }

    /** The lens after it; {@link Lens#NONE} for none. */
    public Lens lens() {
        return lens;
    }

    /** What it paints where there is nothing to filter, straight ARGB. */
    public int fallback() {
        return fallback;
    }

    /** The backdrop's Gaussian sigma, target pixels: its chain's blurs together; 0 for none. */
    public float blur() {
        return plan.blur;
    }

    /** Its {@code saturate} step's amount; 1 where it has none. */
    public float saturation() {
        CgFilter.Step s = step(CgFilter.Fn.SATURATE);
        return s == null ? 1f : s.value();
    }

    /** Its {@code luminosity} step's amount; 0 where it has none. */
    public float luminosity() {
        CgFilter.Step s = step(CgFilter.Fn.LUMINOSITY);
        return s == null ? 0f : s.value();
    }

    /** Its {@code tint} step's colour, straight ARGB; 0, clear, where it has none. */
    public int tint() {
        CgFilter.Step s = step(CgFilter.Fn.TINT);
        return s == null ? 0 : s.color();
    }

    /** This filter blurring by {@code sigma}: its blurs become one, or a blur comes first. */
    public CgBackdropFilter blur(float sigma) {
        List<CgFilter.Step> steps = new ArrayList<>(filter.steps());
        int at = indexOf(steps, CgFilter.Fn.BLUR);
        steps.removeIf(t -> t.function() == CgFilter.Fn.BLUR);
        steps.add(Math.max(0, at), CgFilter.blur(sigma).steps().get(0));
        return with(steps);
    }

    /** This filter saturating by {@code amount}: its {@code saturate} step, or one after the blur. */
    public CgBackdropFilter saturation(float amount) {
        List<CgFilter.Step> steps = new ArrayList<>(filter.steps());
        put(steps, CgFilter.saturate(amount).steps().get(0), lastIndexOf(steps, CgFilter.Fn.BLUR) + 1);
        return with(steps);
    }

    /** This filter taking {@code amount} of the tint's brightness: its {@code luminosity} step, or one before the tint. */
    public CgBackdropFilter luminosity(float amount) {
        List<CgFilter.Step> steps = new ArrayList<>(filter.steps());
        int tintAt = indexOf(steps, CgFilter.Fn.TINT);
        put(steps, CgFilter.luminosity(tint(), amount).steps().get(0), tintAt < 0 ? steps.size() : tintAt);
        return with(steps);
    }

    /** This filter laying {@code argb} over the backdrop: its {@code tint} step, or one last; its luminosity follows. */
    public CgBackdropFilter tint(int argb) {
        List<CgFilter.Step> steps = new ArrayList<>(filter.steps());
        put(steps, CgFilter.tint(argb).steps().get(0), steps.size());
        int lum = indexOf(steps, CgFilter.Fn.LUMINOSITY);
        if (lum >= 0) steps.set(lum, CgFilter.luminosity(argb, steps.get(lum).value()).steps().get(0));
        return with(steps);
    }

    private CgBackdropFilter with(List<CgFilter.Step> steps) {
        return new CgBackdropFilter(CgFilter.of(steps), lens, fallback);
    }

    private CgFilter.Step step(CgFilter.Fn fn) {
        for (CgFilter.Step s : filter.steps()) if (s.function() == fn) return s;
        return null;
    }

    private static int indexOf(List<CgFilter.Step> steps, CgFilter.Fn fn) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).function() == fn) return i;
        return -1;
    }

    private static int lastIndexOf(List<CgFilter.Step> steps, CgFilter.Fn fn) {
        for (int i = steps.size() - 1; i >= 0; i--) if (steps.get(i).function() == fn) return i;
        return -1;
    }

    /** {@code step} where its function's step is, or at {@code at}. */
    private static void put(List<CgFilter.Step> steps, CgFilter.Step step, int at) {
        int i = indexOf(steps, step.function());
        if (i >= 0) steps.set(i, step);
        else steps.add(Math.min(at, steps.size()), step);
    }

    /**
     * Its colour steps and lens into {@code values}, as both glass shaders name them ({@code shaders/ui/glass.shader},
     * a canvas pane, and {@code shaders/materials/surface/glass.shader}, a quad in the world); the blur, the backdrop
     * and the pane's shape are each drawer's own. Engine.
     */
    public void writeTo(CgMaterialValues values) {
        matrix(values, "_Before", plan.before());
        float[] lum = plan.luminosity();
        values.vec4("_Lum", lum[0], lum[1], lum[2], lum[3]);
        matrix(values, "_After", plan.after());
        values.set1f("_Bezel", lens.bezel());
        values.set1f("_Ior", lens.ior());
        values.set1f("_Specular", lens.specular());
        values.set1f("_Glow", lens.glow());
        values.set1f("_EdgeHighlight", lens.rim());
        values.set1f("_EdgeWidth", lens.rimWidth());
        values.set1f("_RimAmbient", lens.rimAmbient());
        values.set1f("_Chromatic", lens.chromatic());
        values.set1f("_Noise", lens.grain());
        float lx = lens.lightX(), ly = lens.lightY(), length = (float) Math.sqrt(lx * lx + ly * ly);
        values.vec2("_LightDir", length > 0f ? lx / length : 0f, length > 0f ? ly / length : -1f);
    }

    /** A 4x5 matrix's colour rows and offsets: its alpha row has nothing to act on over an opaque backdrop. */
    private static void matrix(CgMaterialValues values, String name, float[] m) {
        values.vec4(name + "R", m[0], m[1], m[2], m[3]);
        values.vec4(name + "G", m[5], m[6], m[7], m[8]);
        values.vec4(name + "B", m[10], m[11], m[12], m[13]);
        values.vec4(name + "Off", m[4], m[9], m[14], 0f);
    }

    /** How the glass pass draws it: the blur, and the colour steps folded around the luminosity. Engine. */
    public Plan plan() {
        return plan;
    }

    /** How far past the pane its lens reads, in the shape's units. */
    public float lensReach() {
        return lens.reach();
    }

    /**
     * The glass past CSS: a lens at a pane's edge that refracts what is behind it, splits it into colours, lights a rim and
     * grains it. A {@link CgBackdropFilter}'s last step, after its {@link CgFilter} chain has blurred and coloured the
     * backdrop.
     *
     * <pre>{@code
     * CgBackdropFilter liquid = CgBackdropFilter.of(CgFilter.blur(4f).then(CgFilter.saturate(1.8f)))
     *         .lens(Lens.LIQUID);
     * Lens soft = Lens.DEFAULT.refraction(12f, 1.4f).grain(0.02f);   // a preset, tuned
     * }</pre>
     *
     * <ul>
     *   <li>Its lengths (bezel, rim width) are in the pane's units; {@link #scaled} fits a lens tuned in pixels to a pane in
     *       the world.</li>
     *   <li>{@link #NONE} refracts and lights nothing: what a pane without a lens is.</li>
     *   <li>The rim and glow show only with {@code specular} above 0; refraction only with a bezel and an {@code ior}
     *       above 1.</li>
     * </ul>
     *
     * @param bezel      the lens's width at the edge, in the shape's units; 0 for none
     * @param ior        its index of refraction: 1 is none, 1.5 glass
     * @param chromatic  how far apart the colour channels refract
     * @param specular   the highlight's strength, over glow and rim
     * @param glow       the broad highlight along the light axis
     * @param rim        the thin highlight band at the edge
     * @param rimWidth   that band's width, in the shape's units
     * @param rimAmbient how much of the rim is lit evenly: 0 directional, 1 even
     * @param grain      noise over the pane: over a flat backdrop it is what reads as a material
     * @param lightX     the light's direction across, normalised when used; up-left by default
     * @param lightY     the light's direction down
     */
    public record Lens(float bezel, float ior, float chromatic, float specular, float glow, float rim, float rimWidth,
                              float rimAmbient, float grain, float lightX, float lightY) {

            /** No lens: nothing refracted, lit or grained. */
        public static final Lens NONE = of(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        /** A lens at the edge, lit and grained: a pane when nothing says otherwise. */
        public static final Lens DEFAULT = of(8f, 1.5f, 0.20f, 1f, 0.10f, 0.25f, 3f, 0.5f, 0.04f);
        /** Plain glass: a gentle lens, a faint highlight. */
        public static final Lens CLEAR = of(10f, 1.35f, 0f, 0.6f, 0.05f, 0.25f, 3f, 0.5f, 0f);
        /** Apple's liquid glass: a wide strong lens, a bright rim and some dispersion. */
        public static final Lens LIQUID = of(20f, 1.6f, 0.25f, 1.2f, 0.2f, 0.4f, 3f, 0.5f, 0.02f);
        /**
         * A dispersive prism: dispersion rather than displacement. A stronger lens folds what is behind back on itself, and
         * a fringe that wide splits into hard coloured lines; a pixel of blur in its filter keeps it soft.
         */
        public static final Lens PRISM = of(16f, 1.9f, 1.6f, 0.5f, 0.05f, 0.25f, 3f, 0.5f, 0f);

        public Lens {
            for (float v : new float[]{bezel, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain}) {
                if (!(v >= 0f) || Float.isInfinite(v)) {
                    throw new IllegalArgumentException("a lens's lengths and strengths are finite and >= 0");
                }
            }
            if (!(ior >= 1f) || Float.isInfinite(ior)) throw new IllegalArgumentException("ior must be >= 1: " + ior);
            if (!Float.isFinite(lightX) || !Float.isFinite(lightY) || (lightX == 0f && lightY == 0f)) {
                throw new IllegalArgumentException("the light needs a direction: " + lightX + ", " + lightY);
            }
        }

        /** A lens lit from the default direction, up-left. */
        public static Lens of(float bezel, float ior, float chromatic, float specular, float glow, float rim,
                                     float rimWidth, float rimAmbient, float grain) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, LENS_LIGHT, LENS_LIGHT);
        }

        /** This lens {@code bezel} wide at the edge, refracting by {@code ior}. */
        public Lens refraction(float bezel, float ior) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, lightX, lightY);
        }

        /** This lens with its colours refracting {@code chromatic} apart. */
        public Lens chromatic(float chromatic) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, lightX, lightY);
        }

        /** This lens's highlights: overall strength, the broad glow, the rim band, its width and its even part. */
        public Lens highlight(float specular, float glow, float rim, float rimWidth, float rimAmbient) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, lightX, lightY);
        }

        /** This lens with {@code grain} noise. */
        public Lens grain(float grain) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, lightX, lightY);
        }

        /** This lens lit from ({@code x}, {@code y}), a direction on the target, y down. */
        public Lens light(float x, float y) {
            return new Lens(bezel, ior, chromatic, specular, glow, rim, rimWidth, rimAmbient, grain, x, y);
        }

        /** Its bezel and rim width times {@code unitsPerPixel}: a lens tuned in pixels on a pane measured in other units. */
        public Lens scaled(float unitsPerPixel) {
            return new Lens(bezel * unitsPerPixel, ior, chromatic, specular, glow, rim, rimWidth * unitsPerPixel,
                    rimAmbient, grain, lightX, lightY);
        }

        /** Whether it bends what is behind it: a bezel and an ior above 1. */
        public boolean refracts() {
            return ior > 1.001f && bezel > 0f;
        }

        /** How far past the pane it reads, in the shape's units: what the backdrop's capture covers around the pane. */
        public float reach() {
            return refracts() ? 2.2f * bezel * (1f + 0.2f * chromatic) : 0f;
        }
    }

    /** A lens's light, up-left. */
    private static final float LENS_LIGHT = -0.7071f;

    /**
     * Its chain as the glass pass draws it: one blur of the backdrop, a colour matrix, Fluent's luminosity, a second
     * matrix. Colour matrices meet a blur unchanged (both are linear), so a matrix written before the blur folds into
     * the first.
     *
     * @param blur       the Gaussian sigma, target pixels
     * @param before     the 4x5 matrix before the luminosity, row-major
     * @param luminosity r, g, b of its colour and its amount; amount 0 for none
     * @param after      the 4x5 matrix after it
     */
    public record Plan(float blur, float[] before, float[] luminosity, float[] after) {

        static final float[] IDENTITY = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};

        static Plan of(CgFilter filter) {
            double variance = 0.0;
            float[] before = IDENTITY, after = IDENTITY, luminosity = {0f, 0f, 0f, 0f};
            boolean lit = false;
            for (CgFilter.Op op : filter.ops()) {
                float[] v = op.values();
                switch (op.kind()) {
                    case BLUR -> {
                        if (lit) throw new IllegalArgumentException("a backdrop's blur comes before its luminosity");
                        if (v[0] != v[1]) {
                            throw new IllegalArgumentException("a backdrop blurs the same across and down, not "
                                    + v[0] + " x " + v[1]);
                        }
                        variance += (double) v[0] * v[0];
                    }
                    case COLOR_MATRIX -> {
                        if (lit) after = compose(v, after);
                        else before = compose(v, before);
                    }
                    case LUMINOSITY -> {
                        if (lit) throw new IllegalArgumentException("a backdrop takes one luminosity");
                        luminosity = v.clone();
                        lit = true;
                    }
                    case DROP_SHADOW, MATERIAL -> throw new IllegalArgumentException(
                            "a backdrop filter takes blurs, colour adjustments and a luminosity; " + op.kind()
                                    + " is a layer's");
                }
            }
            return new Plan((float) Math.sqrt(variance), before, luminosity, after);
        }

        /** {@code outer} applied after {@code inner}: outer x [inner; 0 0 0 0 1]. */
        private static float[] compose(float[] outer, float[] inner) {
            float[] r = new float[20];
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 5; col++) {
                    float sum = col == 4 ? outer[row * 5 + 4] : 0f;
                    for (int k = 0; k < 4; k++) sum += outer[row * 5 + k] * inner[k * 5 + col];
                    r[row * 5 + col] = sum;
                }
            }
            return r;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Plan p && blur == p.blur && Arrays.equals(before, p.before)
                    && Arrays.equals(luminosity, p.luminosity) && Arrays.equals(after, p.after);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blur, Arrays.hashCode(before), Arrays.hashCode(luminosity), Arrays.hashCode(after));
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgBackdropFilter f && fallback == f.fallback && filter.equals(f.filter) && lens.equals(f.lens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filter, lens, fallback);
    }

    @Override
    public String toString() {
        return this == NONE ? "CgBackdropFilter[none]" : "CgBackdropFilter[" + filter + ", " + lens + String.format(
                ", fallback #%08X]", fallback);
    }
}
