package com.crystalgraphics.mc.render.label;

import java.util.Objects;

import com.crystalgraphics.mc.render.CgHostPicture;
import com.crystalgraphics.mc.render.CgPictureLighting;
import com.crystalgraphics.mc.render.CgPictureRefresh;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgPlatform;

/**
 * A line of text drawn by Minecraft's font, as its screens draw their titles: the same glyphs, resource pack and all, so
 * a screen built elsewhere matches vanilla's pixels. Sized by the host's own measure.
 *
 * <pre>{@code
 * CgLabelPicture title = CgLabelPicture.translated("container.crafting", 0xFF404040, false);  // vanilla's grey, no shadow
 * canvas.picture(title, 97, 8);                       // at its own size, its top-left there
 * CgLabelPicture.of("Crusher", 0xFF404040, false);     // literal text
 * }</pre>
 *
 * <ul>
 *   <li>It knows its size from its first draw point on: until then {@link #naturalWidth} is 0 and nothing shows.</li>
 *   <li>Drawn once and kept ({@link CgPictureRefresh#ONCE}); the same handle for the same text, colour and shadow.</li>
 * </ul>
 */
public final class CgLabelPicture extends CgHostPicture {

    /** What a label draws: literal text or a translation key, its colour and shadow. */
    record Key(String text, boolean translated, int argb, boolean shadow) {
    }

    private final Key key;
    private volatile int width, height;

    CgLabelPicture(Key key) {
        super("label " + key.text(), CgPictureRefresh.ONCE, CgPictureLighting.ITEMS_3D);
        this.key = key;
    }

    /** {@code text} as it is, in {@code argb}, with or without the font's shadow. Any thread. */
    public static CgLabelPicture of(String text, int argb, boolean shadow) {
        return CgLabels.picture(new Key(Objects.requireNonNull(text, "text"), false, argb, shadow));
    }

    /** {@code key} in the game's language: a vanilla screen's {@code container.inventory}. Any thread. */
    public static CgLabelPicture translated(String key, int argb, boolean shadow) {
        return CgLabels.picture(new Key(Objects.requireNonNull(key, "key"), true, argb, shadow));
    }

    /** Its width in units; 0 until measured. */
    @Override
    public float naturalWidth() {
        return width;
    }

    @Override
    public float naturalHeight() {
        return height;
    }

    @Override
    protected void draw(CgHostDrawContext ctx) {
        CgLabels.Host host = CgPlatform.get(CgLabels.HOST);
        if (host.absent() != null) return;
        String text = key.translated() ? host.translate(key.text()) : key.text();
        int w = host.width(text), h = host.lineHeight();
        if (w != width || h != height) {
            // Shown at its size from the next frame; this one draws into the size it was asked at
            width = w;
            height = h;
            changed();
        }
        if (w > 0) host.draw(ctx, text, key.argb(), key.shadow());
    }

    @Override
    protected String traceZone() {
        return "pictures.label";
    }
}
