package com.crystalgraphics.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgKitFiles;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;

/**
 * How a game draws its subtitles: a kit's {@code subtitle_style.yaml}, in the game's UI units, which scale with the
 * screen's height from {@code reference_height}. A line whose kit has none is drawn in Unreal's canvas look.
 *
 * <pre>{@code
 * # assets/kakarot_kit/subtitle_style.yaml
 * _v: 1
 * reference_height: 1080
 * line: { font: kakarot_kit:fonts/Art/UI/Xcmn_Font/CMN/FOT-RodinNTLGPro-B.otf, size: 20,
 *         outline: { size: 3, colour: 0xFF000000 }, anchor: [0.5, 0.5], alignment: [0.5, 0], offset: [-10, 328] }
 * band: { colour: 0x80030305, anchor: [0.5, 0.5], height: 200, offset: [0, 330] }
 * fade_in: { text: [[0, 0], [0.2, 1]], slide: [[0, 100], [0.2, 0]], interp: cubic }    # in from the right, eased
 * }</pre>
 *
 * <pre>{@code
 * CgSubtitleStyle.registerKit("kakarot_kit", Paths.get("assets/kakarot_kit"));   // the asset registry does this
 * CgSubtitleStyle style = CgSubtitleStyle.of("kakarot_kit");                       // null: the canvas look
 * float px = style.line().size() * style.scale(screenHeight) * 96f / 72f;
 * }</pre>
 *
 * <ul>
 *   <li>A file that does not validate fails the kit's registration, naming it and each issue.</li>
 *   <li>A size is in points at Slate's 96 DPI; everything else in UI units, times {@link #scale}.</li>
 * </ul>
 */
public record CgSubtitleStyle(float referenceHeight, float uiScale, int lines, Text line, @Nullable Text name,
                              @Nullable Band band, Fade fadeIn, Fade fadeOut) {

    private static final String FILE = "subtitle_style.yaml";
    private static final Map<String, CgSubtitleStyle> BY_KIT = new ConcurrentHashMap<>();

    /** A Slate text block in a canvas slot: where its box sits is {@code anchor} on the screen, then {@code offset}, less {@code alignment} of its own size. */
    public record Text(String font, float size, int colour, float outlineSize, int outlineColour, float lineHeight,
                       float wrap, String justify, float anchorX, float anchorY, float alignmentX, float alignmentY,
                       float offsetX, float offsetY, @Nullable Underline underline) {
    }

    /** An image under the name, centred {@code offset} from the name's anchor. */
    public record Underline(String image, float width, float height, float offsetX, float offsetY) {
    }

    /** A bar across the screen's width, centred {@code offsetY} below its anchor; {@code colour} is a linear tint. */
    public record Band(int colour, float anchorY, float height, float offsetY) {
    }

    /**
     * A widget animation over seconds, held past its ends: the band's alpha, the text's alpha and the text's slide along
     * x in UI units, each {@code (t, value)} pairs, flattened. {@code cubic} eases as a rich curve with auto tangents
     * ({@code FRichCurve::AutoSetTangents}, {@code EvalForTwoKeys}): its ends flat, an inner key's slope its neighbours'
     * chord, so two keys are a smoothstep; else linear.
     */
    public record Fade(float[] band, float[] text, float[] slide, boolean cubic) {

        static final Fade NONE = new Fade(new float[0], new float[0], new float[0], false);

        public float band(float seconds) {
            return at(band, seconds, 1f);
        }

        public float text(float seconds) {
            return at(text, seconds, 1f);
        }

        /** How far the text is moved along x, in UI units. */
        public float slide(float seconds) {
            return at(slide, seconds, 0f);
        }

        /** When its last key falls. */
        public float seconds() {
            return Math.max(last(band), Math.max(last(text), last(slide)));
        }

        private static float last(float[] keys) {
            return keys.length > 0 ? keys[keys.length - 2] : 0f;
        }

        private float at(float[] keys, float t, float absent) {
            if (keys.length == 0) return absent;
            if (t <= keys[0]) return keys[1];
            for (int i = 2; i < keys.length; i += 2) {
                if (t < keys[i]) {
                    float t0 = keys[i - 2], v0 = keys[i - 1], t1 = keys[i], v1 = keys[i + 1], dt = t1 - t0;
                    float u = (t - t0) / dt;
                    if (!cubic) return v0 + u * (v1 - v0);
                    float p1 = v0 + tangent(keys, i - 2) * dt / 3f, p2 = v1 - tangent(keys, i) * dt / 3f;
                    float w = 1f - u;
                    return w * w * w * v0 + 3f * w * w * u * p1 + 3f * w * u * u * p2 + u * u * u * v1;
                }
            }
            return keys[keys.length - 1];
        }

        /** The auto tangent at key {@code i} (an index into the pairs): flat at the ends, else the neighbours' chord. */
        private static float tangent(float[] keys, int i) {
            if (i == 0 || i == keys.length - 2) return 0f;
            return (keys[i + 3] - keys[i - 1]) / Math.max(1e-4f, keys[i + 2] - keys[i - 2]);
        }
    }

    /** What a unit is in pixels on a screen {@code height} pixels tall. */
    public float scale(float height) {
        return uiScale * height / referenceHeight;
    }

    /** The style of the kit {@code namespace}; null where it has none, or {@code namespace} is null. */
    @Nullable
    public static CgSubtitleStyle of(@Nullable String namespace) {
        return namespace == null ? null : BY_KIT.get(namespace);
    }

    /** {@code folder}'s {@code subtitle_style.yaml}, the kit {@code namespace}'s; nothing where it ships none. */
    public static void registerKit(String namespace, Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) return;
        BY_KIT.put(namespace, read(file));
    }

    /** {@code file}, validated against {@link CgKitFiles#SUBTITLE_STYLE}. */
    public static CgSubtitleStyle read(Path file) {
        Map<?, ?> root;
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> m)) throw new IllegalArgumentException(file + ": expected a map");
            root = m;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.SUBTITLE_STYLE, root, CgValidation.Usecase.LOAD);
        if (!v.valid()) throw new IllegalArgumentException(file + ": " + v.issues());
        return new CgSubtitleStyle(num(root, "reference_height", 1080f), num(root, "ui_scale", 1f),
                (int) num(root, "lines", 1f), text((Map<?, ?>) root.get("line")),
                root.get("name") instanceof Map<?, ?> n ? text(n) : null,
                root.get("band") instanceof Map<?, ?> b ? band(b) : null,
                fade(root.get("fade_in")), fade(root.get("fade_out")));
    }

    private static Text text(Map<?, ?> m) {
        Map<?, ?> outline = m.get("outline") instanceof Map<?, ?> o ? o : Map.of();
        Map<?, ?> u = m.get("underline") instanceof Map<?, ?> x ? x : null;
        return new Text((String) m.get("font"), num(m, "size", 0f), colour(m.get("colour"), 0xFFFFFFFF),
                num(outline, "size", 0f), colour(outline.get("colour"), 0xFF000000), num(m, "line_height", 1f),
                num(m, "wrap", 0f), m.get("justify") instanceof String j ? j : "left",
                vec(m, "anchor", 0), vec(m, "anchor", 1), vec(m, "alignment", 0), vec(m, "alignment", 1),
                vec(m, "offset", 0), vec(m, "offset", 1),
                u == null ? null : new Underline((String) u.get("image"), vec(u, "size", 0), vec(u, "size", 1),
                        vec(u, "offset", 0), vec(u, "offset", 1)));
    }

    private static Band band(Map<?, ?> m) {
        return new Band(colour(m.get("colour"), 0), vec(m, "anchor", 1), num(m, "height", 0f), vec(m, "offset", 1));
    }

    private static Fade fade(@Nullable Object o) {
        if (!(o instanceof Map<?, ?> m)) return Fade.NONE;
        return new Fade(keys(m.get("band")), keys(m.get("text")), keys(m.get("slide")), "cubic".equals(m.get("interp")));
    }

    private static float[] keys(@Nullable Object o) {
        if (!(o instanceof List<?> list)) return new float[0];
        float[] out = new float[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            List<?> key = (List<?>) list.get(i);
            out[2 * i] = ((Number) key.get(0)).floatValue();
            out[2 * i + 1] = ((Number) key.get(1)).floatValue();
        }
        return out;
    }

    private static float num(Map<?, ?> m, String key, float absent) {
        return m.get(key) instanceof Number n ? n.floatValue() : absent;
    }

    private static float vec(Map<?, ?> m, String key, int i) {
        return m.get(key) instanceof List<?> l && l.size() > i && l.get(i) instanceof Number n ? n.floatValue() : 0f;
    }

    private static int colour(@Nullable Object o, int absent) {
        return o instanceof Number n ? (int) n.longValue() : absent;
    }
}
