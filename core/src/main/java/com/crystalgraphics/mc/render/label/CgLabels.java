package com.crystalgraphics.mc.render.label;

import java.util.LinkedHashMap;
import java.util.Map;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgService;

/**
 * Text in Minecraft's own font, as its screens draw their titles: {@link CgLabelPicture}. This holds the era's
 * {@link Host}.
 *
 * <pre>{@code
 * canvas.picture(CgLabelPicture.translated("container.inventory", 0xFF404040, false), 8, 72);   // vanilla's label
 * }</pre>
 */
public final class CgLabels {

    /**
     * What each era fills: a string's width in its font, a translation, and the string drawn at the picture's origin.
     *
     * <pre>{@code
     * final class LabelsModern implements CgLabels.Host {
     *     public int width(String text) { return Minecraft.getInstance().font.width(text); }
     *     public String translate(String key) { return I18n.get(key); }
     *     public void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow) {
     *         ((GuiGraphics) ctx.gui()).drawString(Minecraft.getInstance().font, text, 0, 0, argb, shadow);
     *     }
     * }
     * }</pre>
     */
    public interface Host {

        /** Why nothing draws here, or null where the host draws labels. */
        String absent();

        /** {@code text}'s width in the font, in GUI units. Render thread. */
        int width(String text);

        /** The font's line height in GUI units: 9 in vanilla. */
        default int lineHeight() {
            return 9;
        }

        /** {@code key} in the game's language; the key itself where none has it. Render thread. */
        String translate(String key);

        /** Draws {@code text} with its top-left at the picture's origin. Render thread, at the draw point. */
        void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow);

        Host NONE = new Host() {
            @Override public String absent() { return "no host draws labels here"; }
            @Override public int width(String text) { return 0; }
            @Override public String translate(String key) { return key; }
            @Override public void draw(CgHostDrawContext ctx, String text, int argb, boolean shadow) {
            }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:labels", Host.NONE);

    /** Label pictures by what they draw: a screen shows a handful, so the least recent past this many are dropped. */
    private static final int KEPT = 128;
    private static final Map<CgLabelPicture.Key, CgLabelPicture> PICTURES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CgLabelPicture.Key, CgLabelPicture> eldest) {
            return size() > KEPT;
        }
    };

    private CgLabels() {
    }

    /** The one picture of {@code key}. Any thread. */
    static synchronized CgLabelPicture picture(CgLabelPicture.Key key) {
        return PICTURES.computeIfAbsent(key, CgLabelPicture::new);
    }
}
