package com.crystalgraphics.render.post.volume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.crystalgraphics.render.post.volume.CgPostSettings.Control;
import com.crystalgraphics.render.post.volume.CgPostSettings.Region;

/**
 * A format {@code post} block to and from {@link CgPostSettings}: each key the block holds sets that field and its
 * override bit, and a key it lacks leaves the field to the volumes under it, as Unreal's {@code bOverride_} flags do
 * ({@code FPostProcessSettings}; the blend is {@code FSceneView::OverridePostProcessSettings}'s {@code LERP_PP}, a lerp
 * only where the source overrides, {@code Engine/Private/SceneView.cpp:1622-1627}).
 *
 * <pre>{@code
 * CgPostSettings s = CgPostBlock.settings(block);   // { bloom: 2.0, exposure: { flash: 1.5 } }
 * Map<String, Object> again = CgPostBlock.block(s);  // the same keys back: what it overrides, nothing else
 * }</pre>
 *
 * <ul>
 *   <li>Reads {@code bloom}, {@code vignette}, {@code chromatic}, {@code exposure.flash}, {@code white_balance} and
 *       the grade: {@code colour}, {@code shadows}, {@code midtones}, {@code highlights}, each with
 *       {@code saturation}, {@code contrast}, {@code gamma}, {@code gain} and {@code offset} as {@code [r, g, b, w]},
 *       and {@code colour.tint} as {@code [r, g, b]}. Other keys are not settings yet and are left out of both
 *       directions.</li>
 *   <li>A grading control that is not four numbers, or a tint not three or four, throws, naming its key.</li>
 * </ul>
 */
public final class CgPostBlock {

    private CgPostBlock() {
    }

    /** {@code block}'s settings, overriding exactly the keys it holds. */
    public static CgPostSettings settings(Map<?, ?> block) {
        CgPostSettings s = new CgPostSettings();
        if (block.get("bloom") instanceof Number) s.bloom(((Number) block.get("bloom")).floatValue());
        if (block.get("vignette") instanceof Number) s.vignette(((Number) block.get("vignette")).floatValue());
        if (block.get("chromatic") instanceof Number) s.chromatic(((Number) block.get("chromatic")).floatValue());
        if (block.get("exposure") instanceof Map && ((Map<?, ?>) block.get("exposure")).get("flash") instanceof Number) {
            s.flash(((Number) ((Map<?, ?>) block.get("exposure")).get("flash")).floatValue());
        }
        if (block.get("white_balance") instanceof Map<?, ?> wb) {
            s.whiteBalance(number(wb.get("temperature"), 6500f), number(wb.get("tint"), 0f));
        }
        for (Region region : Region.values()) {
            if (!(block.get(key(region)) instanceof Map<?, ?> controls)) continue;
            for (Control control : Control.values()) {
                Object v = controls.get(control.name().toLowerCase(Locale.ROOT));
                if (v == null) continue;
                float[] c = numbers(v, key(region) + "." + control.name().toLowerCase(Locale.ROOT), 4, 4);
                s.grade(region, control, c[0], c[1], c[2], c[3]);
            }
            if (region == Region.GLOBAL && controls.get("tint") != null) {
                float[] t = numbers(controls.get("tint"), "colour.tint", 3, 4);
                s.tint(t[0], t[1], t[2]);
            }
        }
        return s;
    }

    /** The block key of {@code region}'s controls. */
    private static String key(Region region) {
        return region == Region.GLOBAL ? "colour" : region.name().toLowerCase(Locale.ROOT);
    }

    private static float[] numbers(Object v, String key, int min, int max) {
        if (!(v instanceof List<?> list) || list.size() < min || list.size() > max) {
            throw new IllegalArgumentException("post." + key + ": " + min + (min == max ? "" : " to " + max) + " numbers, not " + v);
        }
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(list.get(i) instanceof Number n)) throw new IllegalArgumentException("post." + key + ": not a number, " + list.get(i));
            out[i] = n.floatValue();
        }
        return out;
    }

    private static float number(Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    /** The block {@code s} writes: a key for each field it overrides, in the format's order. */
    public static Map<String, Object> block(CgPostSettings s) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (s.overrides(CgPostSettings.BLOOM)) b.put("bloom", (double) s.bloom());
        if (s.overrides(CgPostSettings.FLASH)) {
            Map<String, Object> exposure = new LinkedHashMap<>();
            exposure.put("flash", (double) s.flash());
            b.put("exposure", exposure);
        }
        if (s.overrides(CgPostSettings.VIGNETTE)) b.put("vignette", (double) s.vignette());
        if (s.overrides(CgPostSettings.CHROMATIC)) b.put("chromatic", (double) s.chromatic());
        if (s.overrides(CgPostSettings.WHITE_BALANCE)) {
            Map<String, Object> wb = new LinkedHashMap<>();
            wb.put("temperature", (double) s.whiteTemp());
            wb.put("tint", (double) s.whiteTint());
            b.put("white_balance", wb);
        }
        for (Region region : Region.values()) {
            Map<String, Object> controls = new LinkedHashMap<>();
            for (Control control : Control.values()) {
                if (!s.grades(region, control)) continue;
                List<Object> c = new ArrayList<>(4);
                for (int k = 0; k < 4; k++) c.add((double) s.grade(region, control, k));
                controls.put(control.name().toLowerCase(Locale.ROOT), c);
            }
            if (region == Region.GLOBAL && s.tints()) controls.put("tint", List.of((double) s.tintR(), (double) s.tintG(), (double) s.tintB()));
            if (!controls.isEmpty()) b.put(key(region), controls);
        }
        return b;
    }
}
