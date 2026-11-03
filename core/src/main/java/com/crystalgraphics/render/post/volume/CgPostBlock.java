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
 *       and {@code colour.tint} as {@code [r, g, b]}.</li>
 *   <li>The tonemapper: {@code tonemapper: film | legacy} turns Unreal 5.1's or 4.21's on; {@code tone_curve},
 *       {@code film: { slope, toe, shoulder, black_clip, white_clip }}, {@code film_legacy: { contrast, dynamic_range,
 *       toe_amount, heal_amount }}, {@code blue_correction}, {@code expand_gamut} and {@code display_gamma} set its
 *       settings, each key alone.</li>
 *   <li>The bloom's layers: {@code bloom1} to {@code bloom6} as {@code { size, tint: [r, g, b] }},
 *       {@code bloom_size_scale}, {@code bloom_threshold}. Any of them draws Unreal's Gaussian bloom, as does
 *       {@code bloom_method: sog}; {@code fft} and {@code cc2} are not drawn yet and throw.</li>
 *   <li>Other keys are not settings yet and are left out of both directions.</li>
 *   <li>A grading control that is not four numbers, or a tint not three or four, throws, naming its key.</li>
 * </ul>
 *
 * <pre>{@code
 * // 5.1's tonemapper with no tone curve at a display gamma of 2.7, and one of its six bloom layers set
 * CgPostSettings stage = CgPostBlock.settings(Map.of("tonemapper", "film", "tone_curve", 0, "display_gamma", 2.7,
 *         "bloom", 1.25, "bloom_size_scale", 4, "bloom1", Map.of("size", 0.3, "tint", List.of(0.3466, 0.3466, 0.3466))));
 * }</pre>
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
        tonemapper(block, s);
        bloomLayers(block, s);
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

    private static void tonemapper(Map<?, ?> block, CgPostSettings s) {
        Object which = block.get("tonemapper");
        if ("film".equals(which)) s.filmic();
        else if ("legacy".equals(which)) s.tonemap();
        else if (which != null) throw new IllegalArgumentException("post.tonemapper: film or legacy, not " + which);
        if (block.get("tone_curve") instanceof Number n) s.toneCurve(n.floatValue());
        if (block.get("blue_correction") instanceof Number n) s.blueCorrection(n.floatValue());
        if (block.get("expand_gamut") instanceof Number n) s.expandGamut(n.floatValue());
        if (block.get("display_gamma") instanceof Number n) s.displayGamma(n.floatValue());
        if (block.get("film") instanceof Map<?, ?> f) {
            if (f.get("slope") instanceof Number n) set(s, CgPostSettings.FILM_SLOPE, n);
            if (f.get("toe") instanceof Number n) set(s, CgPostSettings.FILM_CURVE_TOE, n);
            if (f.get("shoulder") instanceof Number n) set(s, CgPostSettings.FILM_SHOULDER, n);
            if (f.get("black_clip") instanceof Number n) set(s, CgPostSettings.FILM_BLACK_CLIP, n);
            if (f.get("white_clip") instanceof Number n) set(s, CgPostSettings.FILM_WHITE_CLIP, n);
        }
        if (block.get("film_legacy") instanceof Map<?, ?> f) {
            if (f.get("contrast") instanceof Number n) set(s, CgPostSettings.FILM_CONTRAST, n);
            if (f.get("dynamic_range") instanceof Number n) set(s, CgPostSettings.FILM_DYNAMIC_RANGE, n);
            if (f.get("toe_amount") instanceof Number n) set(s, CgPostSettings.FILM_TOE, n);
            if (f.get("heal_amount") instanceof Number n) s.filmHeal(n.floatValue());
        }
    }

    /** One film setting of the bit {@code which}, alone: the setters take a curve's settings together. */
    private static void set(CgPostSettings s, int which, Number value) {
        float v = value.floatValue();
        switch (which) {
            case CgPostSettings.FILM_SLOPE -> s.filmSlope = v;
            case CgPostSettings.FILM_CURVE_TOE -> s.filmCurveToe = v;
            case CgPostSettings.FILM_SHOULDER -> s.filmShoulder = v;
            case CgPostSettings.FILM_BLACK_CLIP -> s.filmBlackClip = v;
            case CgPostSettings.FILM_WHITE_CLIP -> s.filmWhiteClip = v;
            case CgPostSettings.FILM_CONTRAST -> s.filmContrast = v;
            case CgPostSettings.FILM_DYNAMIC_RANGE -> s.filmDynamicRange = v;
            case CgPostSettings.FILM_TOE -> s.filmToe = v;
            default -> throw new IllegalArgumentException("not a film setting: " + which);
        }
        s.overrides |= which;
    }

    private static void bloomLayers(Map<?, ?> block, CgPostSettings s) {
        boolean layered = false;
        if (block.get("bloom_threshold") instanceof Number n) {
            s.bloomThreshold(n.floatValue());
            layered = true;
        }
        if (block.get("bloom_size_scale") instanceof Number n) {
            s.bloomSizeScale(n.floatValue());
            layered = true;
        }
        for (int l = 1; l <= CgPostSettings.BLOOM_LAYERS; l++) {
            if (!(block.get("bloom" + l) instanceof Map<?, ?> layer)) continue;
            layered = true;
            if (layer.get("size") instanceof Number n) s.bloomSize(l, n.floatValue());
            if (layer.get("tint") != null) {
                float[] t = numbers(layer.get("tint"), "bloom" + l + ".tint", 3, 4);
                s.bloomTint(l, t[0], t[1], t[2]);
            }
        }
        Object method = block.get("bloom_method");
        if (method == null ? layered : "sog".equals(method)) s.gaussianBloom();
        else if (method != null) throw new IllegalArgumentException("post.bloom_method " + method + " is not drawn yet: sog is");
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
        if (s.overrides(CgPostSettings.GAUSSIAN)) b.put("bloom_method", "sog");
        if (s.overrides(CgPostSettings.BLOOM_THRESHOLD)) b.put("bloom_threshold", (double) s.bloomThreshold());
        for (int l = 1; l <= CgPostSettings.BLOOM_LAYERS; l++) {
            Map<String, Object> layer = new LinkedHashMap<>();
            if (s.overridesLayer(l, false)) layer.put("size", (double) s.bloomSize(l));
            if (s.overridesLayer(l, true)) {
                layer.put("tint", List.of((double) s.bloomTint(l, 0), (double) s.bloomTint(l, 1), (double) s.bloomTint(l, 2)));
            }
            if (!layer.isEmpty()) b.put("bloom" + l, layer);
        }
        if (s.overrides(CgPostSettings.BLOOM_SIZE_SCALE)) b.put("bloom_size_scale", (double) s.bloomSizeScale());
        if (s.overrides(CgPostSettings.TONEMAP)) b.put("tonemapper", s.overrides(CgPostSettings.FILMIC) ? "film" : "legacy");
        if (s.overrides(CgPostSettings.TONE_CURVE)) b.put("tone_curve", (double) s.toneCurve());
        Map<String, Object> film = new LinkedHashMap<>();
        if (s.overrides(CgPostSettings.FILM_SLOPE)) film.put("slope", (double) s.filmSlope());
        if (s.overrides(CgPostSettings.FILM_CURVE_TOE)) film.put("toe", (double) s.filmCurveToe());
        if (s.overrides(CgPostSettings.FILM_SHOULDER)) film.put("shoulder", (double) s.filmShoulder());
        if (s.overrides(CgPostSettings.FILM_BLACK_CLIP)) film.put("black_clip", (double) s.filmBlackClip());
        if (s.overrides(CgPostSettings.FILM_WHITE_CLIP)) film.put("white_clip", (double) s.filmWhiteClip());
        if (!film.isEmpty()) b.put("film", film);
        Map<String, Object> legacy = new LinkedHashMap<>();
        if (s.overrides(CgPostSettings.FILM_CONTRAST)) legacy.put("contrast", (double) s.filmContrast());
        if (s.overrides(CgPostSettings.FILM_DYNAMIC_RANGE)) legacy.put("dynamic_range", (double) s.filmDynamicRange());
        if (s.overrides(CgPostSettings.FILM_TOE)) legacy.put("toe_amount", (double) s.filmToe());
        if (s.overrides(CgPostSettings.FILM_HEAL)) legacy.put("heal_amount", (double) s.filmHeal());
        if (!legacy.isEmpty()) b.put("film_legacy", legacy);
        if (s.overrides(CgPostSettings.EXPAND_GAMUT)) b.put("expand_gamut", (double) s.expandGamut());
        if (s.overrides(CgPostSettings.BLUE_CORRECTION)) b.put("blue_correction", (double) s.blueCorrection());
        if (s.overrides(CgPostSettings.DISPLAY_GAMMA)) b.put("display_gamma", (double) s.displayGamma());
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
