package com.crystalgraphics.render.post.volume;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *   <li>Reads {@code bloom}, {@code vignette}, {@code chromatic}, {@code exposure.flash} and {@code white_balance}:
 *       the fields the stack draws today. Other keys are not settings yet and are left out of both directions.</li>
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
        return s;
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
        return b;
    }
}
