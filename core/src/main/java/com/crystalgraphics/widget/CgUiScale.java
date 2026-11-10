package com.crystalgraphics.widget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgKitFiles;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;

/**
 * How many screen pixels a UI unit is: a kit's {@code ui.yaml}, Unreal's {@code UUserInterfaceSettings}. A widget on
 * the screen fills it in UI units, as {@code AddToViewport} sizes a user widget to the viewport over its DPI scale.
 *
 * <pre>{@code
 * CgUiScale ui = CgUiScale.of("kakarot_kit");                   // the kit's, or Unreal's default without one
 * float scale = ui.scale(screenWidth, screenHeight);           // 2560 x 1440: 1.333
 * pop.allot(screenWidth / scale, screenHeight / scale);
 * pop.paint(painter, placement.scaling(scale));
 * }</pre>
 *
 * <ul>
 *   <li>The curve is linear between its keys and held past its ends; the scale never falls below 0.01, as
 *       {@code GetDPIScaleBasedOnSize}'s.</li>
 *   <li>A file that does not validate throws as the kit registers, naming the file.</li>
 * </ul>
 *
 * @param rule             which side of the screen the curve reads
 * @param sides            the curve's keys: screen sides in pixels, ascending
 * @param scales           the curve's values at those sides
 * @param applicationScale multiplied into the curve's value
 */
public record CgUiScale(Rule rule, float[] sides, float[] scales, float applicationScale) {

    /** Unreal's {@code EUIScalingRule}: the side the curve is read at. */
    public enum Rule { SHORTEST_SIDE, LONGEST_SIDE, HORIZONTAL, VERTICAL }

    /** The file at a kit's root. */
    public static final String FILE = "ui.yaml";

    /**
     * Unreal's default ({@code BaseEngine.ini}'s {@code UIScaleCurve}): the shortest side over 1080, held below 480,
     * application scale 1. A kit that keeps its look at another size sets {@code application_scale} rather than a curve.
     */
    public static final CgUiScale DEFAULT = new CgUiScale(Rule.SHORTEST_SIDE,
            new float[]{480f, 720f, 1080f, 8640f}, new float[]{0.444f, 0.666f, 1f, 8f}, 1f);

    private static final Map<String, CgUiScale> BY_KIT = new ConcurrentHashMap<>();

    public CgUiScale {
        Objects.requireNonNull(rule, "rule");
        if (sides.length == 0 || sides.length != scales.length) {
            throw new IllegalArgumentException("a UI scale curve needs a key, each a side and a scale");
        }
    }

    /** The kit {@code namespace}'s scale; {@link #DEFAULT} for a kit without a {@code ui.yaml}, or none. */
    public static CgUiScale of(@Nullable String namespace) {
        CgUiScale s = namespace == null ? null : BY_KIT.get(namespace);
        return s != null ? s : DEFAULT;
    }

    /** Reads {@code folder}'s {@code ui.yaml} as the asset registry registers the kit. */
    public static void registerKit(String namespace, Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            BY_KIT.remove(namespace);
            return;
        }
        BY_KIT.put(namespace, read(file));
    }

    /** {@code file}, validated against {@link CgKitFiles#UI}. */
    public static CgUiScale read(Path file) {
        Map<?, ?> root;
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> m)) throw new IllegalArgumentException(file + ": expected a map");
            root = m;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.UI, root, CgValidation.Usecase.LOAD);
        if (!v.valid()) throw new IllegalArgumentException(file + ": " + v.issues());
        return of(root);
    }

    /** A validated {@code ui.yaml} tree. */
    static CgUiScale of(Map<?, ?> root) {
        Rule rule = root.get("rule") instanceof String r ? Rule.valueOf(r.toUpperCase(Locale.ROOT)) : Rule.SHORTEST_SIDE;
        float app = root.get("application_scale") instanceof Number n ? n.floatValue() : 1f;
        if (!(root.get("curve") instanceof List<?> keys) || keys.isEmpty()) {
            return new CgUiScale(rule, DEFAULT.sides, DEFAULT.scales, app);
        }
        float[] sides = new float[keys.size()];
        float[] scales = new float[keys.size()];
        for (int i = 0; i < sides.length; i++) {
            List<?> key = (List<?>) keys.get(i);
            sides[i] = ((Number) key.get(0)).floatValue();
            scales[i] = ((Number) key.get(1)).floatValue();
        }
        return new CgUiScale(rule, sides, scales, app);
    }

    /** Screen pixels a UI unit is on a {@code width} x {@code height} pixel screen. */
    public float scale(float width, float height) {
        float side = switch (rule) {
            case SHORTEST_SIDE -> Math.min(width, height);
            case LONGEST_SIDE -> Math.max(width, height);
            case HORIZONTAL -> width;
            case VERTICAL -> height;
        };
        return Math.max(curve(side) * applicationScale, 0.01f);
    }

    private float curve(float side) {
        if (side <= sides[0]) return scales[0];
        int last = sides.length - 1;
        if (side >= sides[last]) return scales[last];
        int i = 1;
        while (sides[i] < side) i++;
        float t = (side - sides[i - 1]) / (sides[i] - sides[i - 1]);
        return scales[i - 1] + (scales[i] - scales[i - 1]) * t;
    }
}
