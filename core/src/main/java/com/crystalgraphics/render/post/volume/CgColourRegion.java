package com.crystalgraphics.render.post.volume;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostEffect;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.look.CgFilmLut;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * Grades the world's pixels inside a shape: Unreal 5.1's {@code AColorCorrectRegion}. Its shape's alpha at each pixel's
 * world point mixes the HDR scene toward the scene graded in ACEScg (saturation, contrast, gamma, gain and offset, for
 * the whole and per shadows, midtones and highlights), before bloom and every look, as
 * {@code FColorCorrectRegionsSceneViewExtension::PrePostProcessPass_RenderThread} draws it.
 *
 * <pre>{@code
 * CgColourRegion flash = new CgColourRegion().shape(CgColourRegion.Shape.SPHERE)
 *         .grade(CgColourRegion.Range.GLOBAL, CgColourRegion.Control.GAIN, 1f, 1f, 1f, 2f);   // twice as bright
 * flash.place(x, y, z, new Quaternionf(), 6f, 6f, 6f);            // each frame: a sphere 3 blocks round
 * CgRenderStage.Registration on = flash.show();
 * on.close();                                                       // gone
 * }</pre>
 *
 * <pre>{@code
 * # a sequence's spawnable, what CgColourRegionSpawner plays
 * - { id: s2, name: flash, colour_region: { priority: 1, falloff: 0.2, colour: { gain: [2, 2, 2, 1] } } }
 * }</pre>
 *
 * <ul>
 *   <li>Its size is its scale: at 1 its unit shape is half a block across each way, Unreal's 50 cm at a metre a block.</li>
 *   <li>Regions draw by priority, lowest first, each over what the last one left.</li>
 *   <li>Outside its unit box nothing is graded, unless inverted: then everything outside its shape is.</li>
 *   <li>The legacy temperature is played; white balance and colour temperature away from 6500 K and no tint are not yet,
 *       and fail where a region is made from data. So does {@code affected}: per-actor regions are not played.</li>
 * </ul>
 */
public final class CgColourRegion implements CgPostEffect {

    /** {@code EColorCorrectRegionsType}. Its axis, for a cylinder and a cone, is its local +Y (Unreal's +Z). */
    public enum Shape { SPHERE, BOX, CYLINDER, CONE }

    /** {@code EColorCorrectRegionTemperatureType}. */
    public enum Temperature { LEGACY, WHITE_BALANCE, COLOUR_TEMPERATURE }

    /** {@code FColorGradingSettings}' four ranges. */
    public enum Range { GLOBAL, SHADOWS, MIDTONES, HIGHLIGHTS }

    /** {@code FColorGradePerRangeSettings}' five controls, each four floats: rgb and a scalar times them. */
    public enum Control { SATURATION, CONTRAST, GAMMA, GAIN, OFFSET }

    public static final String SHADER = "crystalgraphics:shaders/post/colour_region.shader";
    private static final double[] TO_AP1 = CgFilmLut.srgbToAp1(), FROM_AP1 = CgFilmLut.ap1ToSrgb();
    private static final String[] TO_AP1_ROWS = {"_ToAp10", "_ToAp11", "_ToAp12"}, FROM_AP1_ROWS = {"_FromAp10", "_FromAp11", "_FromAp12"};
    /** Per range, per control, its property: {@code _G3} the whole's gain. */
    private static final String[][] GRADES = new String[4][5];

    static {
        String[] prefix = {"_G", "_S", "_M", "_H"};
        for (int r = 0; r < 4; r++) for (int c = 0; c < 5; c++) GRADES[r][c] = prefix[r] + c;
    }

    private Shape shape = Shape.SPHERE;
    private int priority;
    private float intensity = 1f, inner = 0.5f, outer = 1f, falloff = 1f;
    private boolean invert;
    private Temperature temperatureType = Temperature.COLOUR_TEMPERATURE;
    private float temperature = 6500f, tint;
    private float shadowsMax = 0.09f, highlightsMin = 0.5f;
    /** Per range, per control, four floats. */
    private final float[][][] grade = new float[4][5][4];

    private double x, y, z;
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f scale = new Vector3f(1f);

    private final CgMaterial material = CgMaterial.newInstance(SHADER);
    private final CgPostMaterial draw = CgPostMaterial.of(material, CgPostPoint.AFTER_WORLD, 0);
    private final Matrix3f toLocal = new Matrix3f();
    private final float[] rows = new float[12], half = new float[3];
    private final Consumer<CgShaderBindings> write = this::write;
    /** The keyword set its program has: shape, advanced and temperature packed; -1 before the first. */
    private int keyed = -1;

    public CgColourRegion() {
        for (float[][] range : grade) {
            for (int c = 0; c < range.length; c++) Arrays.fill(range[c], c == Control.OFFSET.ordinal() ? 0f : 1f);
        }
    }

    public CgColourRegion shape(Shape shape) {
        this.shape = shape;
        return this;
    }

    /** Lower draws first. */
    public CgColourRegion priority(int priority) {
        this.priority = priority;
        return this;
    }

    /** How much of the grade it mixes in at full shape, 0 to 1. */
    public CgColourRegion intensity(float intensity) {
        this.intensity = intensity;
        return this;
    }

    /** Where its fade starts and ends in its unit shape, 0 to 1 each. */
    public CgColourRegion fade(float inner, float outer) {
        this.inner = inner;
        this.outer = outer;
        return this;
    }

    /** The power its shape's alpha is raised to. */
    public CgColourRegion falloff(float falloff) {
        this.falloff = falloff;
        return this;
    }

    /** Grades outside its shape instead. */
    public CgColourRegion invert(boolean invert) {
        this.invert = invert;
        return this;
    }

    /** Its temperature in Kelvin and its tint, each -1 to 1; 6500 K and no tint, the default, grade no temperature. */
    public CgColourRegion temperature(Temperature type, float kelvin, float tint) {
        this.temperatureType = type;
        this.temperature = kelvin;
        this.tint = tint;
        return this;
    }

    /** Its temperature and tint under the type it has: what a track keys. */
    public CgColourRegion temperature(float kelvin, float tint) {
        this.temperature = kelvin;
        this.tint = tint;
        return this;
    }

    /** One control of one range: rgb and a scalar times them. */
    public CgColourRegion grade(Range range, Control control, float r, float g, float b, float w) {
        float[] v = grade[range.ordinal()][control.ordinal()];
        v[0] = r;
        v[1] = g;
        v[2] = b;
        v[3] = w;
        return this;
    }

    /** Component {@code i} of one control; what a track keys. */
    public CgColourRegion grade(Range range, Control control, int i, float value) {
        grade[range.ordinal()][control.ordinal()][i] = value;
        return this;
    }

    public float grade(Range range, Control control, int i) {
        return grade[range.ordinal()][control.ordinal()][i];
    }

    /** Where shadows end and highlights start, by luma: {@code FColorGradingSettings}' 0.09 and 0.5. */
    public CgColourRegion ranges(float shadowsMax, float highlightsMin) {
        this.shadowsMax = shadowsMax;
        this.highlightsMin = highlightsMin;
        return this;
    }

    /** Where it stands: an absolute point, its turn, its scale. Each frame it moves. */
    public CgColourRegion place(double x, double y, double z, Quaternionf turn, float sx, float sy, float sz) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.turn.set(turn);
        scale.set(sx, sy, sz);
        return this;
    }

    public float intensity() {
        return intensity;
    }

    public float inner() {
        return inner;
    }

    public float outer() {
        return outer;
    }

    public float falloff() {
        return falloff;
    }

    public float temperature() {
        return temperature;
    }

    public float tint() {
        return tint;
    }

    /** Shows it on the post stack until the registration closes. */
    public CgRenderStage.Registration show() {
        return CgPostStack.get().add(this);
    }

    @Override
    public CgPostPoint point() {
        return CgPostPoint.AFTER_WORLD;
    }

    @Override
    public int order() {
        return priority;
    }

    @Override
    public boolean active(CgPostContext post) {
        keywords();
        return intensity > 0f && draw.active(post);
    }

    @Override
    public void record(CgPostContext post) {
        CgHostView view = post.stage().host().view();
        // Camera-relative in doubles: the shader's points are the camera's
        float cx = (float) (x - view.x()), cy = (float) (y - view.y()), cz = (float) (z - view.z());
        // Its unit shape half a block across at scale 1 (WorldToMetersScale / 2), turned into its axes
        toLocal.set(turn).transpose();
        half[0] = 0.5f * scale.x;
        half[1] = 0.5f * scale.y;
        half[2] = 0.5f * scale.z;
        for (int r = 0; r < 3; r++) {
            float s = Math.abs(half[r]) < 1e-6f ? 1e6f : 1f / half[r];
            float a = toLocal.getRowColumn(r, 0) * s, b = toLocal.getRowColumn(r, 1) * s, c = toLocal.getRowColumn(r, 2) * s;
            rows[r * 4] = a;
            rows[r * 4 + 1] = b;
            rows[r * 4 + 2] = c;
            rows[r * 4 + 3] = -(a * cx + b * cy + c * cz);
        }
        material.applyProperties(write);
        draw.record(post);
    }

    private void write(CgShaderBindings b) {
        b.vec4("_ToRegion0", rows[0], rows[1], rows[2], rows[3]);
        b.vec4("_ToRegion1", rows[4], rows[5], rows[6], rows[7]);
        b.vec4("_ToRegion2", rows[8], rows[9], rows[10], rows[11]);
        b.vec4("_Shape", inner, outer, falloff, intensity);
        b.vec4("_Options", invert ? 1f : 0f, temperature, tint, 0f);
        b.vec4("_Ranges", shadowsMax, highlightsMin, 0f, 0f);
        for (int r = 0; r < 3; r++) {
            b.vec4(TO_AP1_ROWS[r], (float) TO_AP1[r * 3], (float) TO_AP1[r * 3 + 1], (float) TO_AP1[r * 3 + 2], 0f);
            b.vec4(FROM_AP1_ROWS[r], (float) FROM_AP1[r * 3], (float) FROM_AP1[r * 3 + 1], (float) FROM_AP1[r * 3 + 2], 0f);
        }
        for (int range = 0; range < 4; range++) {
            for (int c = 0; c < 5; c++) {
                float[] v = grade[range][c];
                if (range > 0 && c == Control.GAMMA.ordinal()) {
                    // The ranges' gamma clamped to 0.02-10, as RenderRegion does; the whole's is not
                    b.vec4(GRADES[range][c], clamp(v[0]), clamp(v[1]), clamp(v[2]), clamp(v[3]));
                } else {
                    b.vec4(GRADES[range][c], v[0], v[1], v[2], v[3]);
                }
            }
        }
    }

    private static float clamp(float gamma) {
        return Math.max(0.02f, Math.min(10f, gamma));
    }

    /** Its program's keywords as they stand: shape, advanced ranges, a temperature to grade. */
    private void keywords() {
        boolean advanced = advanced(), legacy = temperatureGraded() && temperatureType == Temperature.LEGACY;
        int key = shape.ordinal() | (advanced ? 4 : 0) | (legacy ? 8 : 0);
        if (key == keyed) return;
        for (String k : ALL_KEYWORDS) material.disableKeyword(k);
        for (String k : keywords(shape, advanced, legacy ? Temperature.LEGACY : null)) material.enableKeyword(k);
        keyed = key;
    }

    private static final String[] ALL_KEYWORDS = {"SHAPE_BOX", "SHAPE_CYLINDER", "SHAPE_CONE", "ADVANCED", "TEMPERATURE_LEGACY"};

    /** Whether a range differs from neutral: Unreal's {@code bIsAdvanced}. */
    private boolean advanced() {
        for (int range = 1; range < 4; range++) {
            for (int c = 0; c < 5; c++) {
                float neutral = c == Control.OFFSET.ordinal() ? 0f : 1f;
                for (float v : grade[range][c]) if (v != neutral) return true;
            }
        }
        return false;
    }

    /** Unreal grades no temperature at 6500 K with no tint, whatever its type. */
    private boolean temperatureGraded() {
        return Math.abs(temperature - 6500f) > 1e-4f || Math.abs(tint) > 1e-4f;
    }

    /** The keyword set of a region: its shape's, {@code ADVANCED}, and a temperature's, null for none. */
    public static String[] keywords(Shape shape, boolean advanced, @Nullable Temperature temperature) {
        List<String> k = new ArrayList<>(3);
        if (shape != Shape.SPHERE) k.add("SHAPE_" + shape.name());
        if (advanced) k.add("ADVANCED");
        if (temperature == Temperature.LEGACY) k.add("TEMPERATURE_LEGACY");
        return k.toArray(new String[0]);
    }

    /**
     * A region as a sequence's {@code colour_region} block says, at {@code path}; its place is set each frame after.
     *
     * @throws CgAssetException naming the path for a key it does not take, or what is not played yet
     */
    public static CgColourRegion of(Map<?, ?> block, String path) {
        CgColourRegion r = new CgColourRegion();
        for (Map.Entry<?, ?> e : block.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (key) {
                case "shape" -> r.shape = Shape.valueOf(String.valueOf(v).toUpperCase(Locale.ROOT));
                case "priority" -> r.priority = whole(v, path + ".priority");
                case "intensity" -> r.intensity = number(v);
                case "inner" -> r.inner = number(v);
                case "outer" -> r.outer = number(v);
                case "falloff" -> r.falloff = number(v);
                case "invert" -> r.invert = Boolean.TRUE.equals(v);
                case "temperature" -> temperature(r, (Map<?, ?>) v, path + ".temperature");
                case "colour" -> range(r, Range.GLOBAL, (Map<?, ?>) v, path + ".colour");
                case "shadows" -> range(r, Range.SHADOWS, (Map<?, ?>) v, path + ".shadows");
                case "midtones" -> range(r, Range.MIDTONES, (Map<?, ?>) v, path + ".midtones");
                case "highlights" -> range(r, Range.HIGHLIGHTS, (Map<?, ?>) v, path + ".highlights");
                case "affected" -> throw new CgAssetException(path + ".affected: per-actor colour regions are not played yet");
                default -> throw new CgAssetException(path + "." + key + ": a colour region has no " + key);
            }
        }
        return r;
    }

    private static void temperature(CgColourRegion r, Map<?, ?> t, String path) {
        Object type = t.get("type");
        Temperature kind = type == null ? Temperature.COLOUR_TEMPERATURE : switch (String.valueOf(type)) {
            case "legacy" -> Temperature.LEGACY;
            case "white_balance" -> Temperature.WHITE_BALANCE;
            case "colour_temperature" -> Temperature.COLOUR_TEMPERATURE;
            default -> throw new CgAssetException(path + ".type: " + type + " is no temperature type");
        };
        r.temperature(kind, t.get("value") instanceof Number n ? n.floatValue() : 6500f, t.get("tint") instanceof Number n ? n.floatValue() : 0f);
        if (kind != Temperature.LEGACY && r.temperatureGraded()) {
            throw new CgAssetException(path + ": a " + String.valueOf(type) + " temperature away from 6500 K is not played yet");
        }
    }

    private static void range(CgColourRegion r, Range range, Map<?, ?> block, String path) {
        for (Map.Entry<?, ?> e : block.entrySet()) {
            String key = String.valueOf(e.getKey());
            Control c = control(key);
            if (c == null) throw new CgAssetException(path + "." + key + ": a colour region's grade has no " + key);
            if (!(e.getValue() instanceof List<?> l) || l.size() != 4) throw new CgAssetException(path + "." + key + ": four numbers");
            for (int i = 0; i < 4; i++) r.grade[range.ordinal()][c.ordinal()][i] = number(l.get(i));
        }
    }

    /** A control by its file name, null for none. */
    @Nullable
    static Control control(String name) {
        for (Control c : Control.values()) if (c.name().toLowerCase(Locale.ROOT).equals(name)) return c;
        return null;
    }

    /** A range by its file name: {@code colour} the whole; null for none. */
    @Nullable
    static Range range(String name) {
        return switch (name) {
            case "colour" -> Range.GLOBAL;
            case "shadows" -> Range.SHADOWS;
            case "midtones" -> Range.MIDTONES;
            case "highlights" -> Range.HIGHLIGHTS;
            default -> null;
        };
    }

    /** The keyword set {@code block} plays with, for the precache; null where it fails to make a region. */
    @Nullable
    public static String[] keywords(Map<?, ?> block) {
        try {
            CgColourRegion r = of(block, "");
            return keywords(r.shape, r.advanced(), r.temperatureGraded() ? r.temperatureType : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float number(Object v) {
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    private static int whole(Object v, String path) {
        float f = number(v);
        if (f != Math.rint(f)) throw new CgAssetException(path + ": " + f + " is not a whole priority");
        return (int) f;
    }
}
