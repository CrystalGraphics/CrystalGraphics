package com.crystalgraphics.api.font;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.crystalgraphics.util.io.CgIO;

/**
 * The engine's default font: Outfit, the brand's (the lockup's wordmark is Outfit 700), shipped as
 * {@link #PATH} and loaded ahead on a worker as the client starts, so no frame opens it. A variable font, instanced at
 * the weight asked: its default instance is Thin, so never load {@link #PATH} by hand without a weight.
 *
 * <pre>{@code
 * CgFont body = CgDefaultFont.regular(16);           // weight 400, size-bound
 * CgFont title = CgDefaultFont.at(700, 32);          // any weight from 100 to 900
 * CgFontFamily family = CgFontFamily.of(body);       // fallback to installed fonts as for any font
 * }</pre>
 *
 * <ul>
 *   <li>Any thread. The same weight answers the same unsized {@link CgFont}; never dispose one.</li>
 *   <li>Its glyphs cover Latin: other scripts come from the family's fallback.</li>
 *   <li>Licence: SIL OFL 1.1, {@code fonts/Outfit-OFL.txt} beside it.</li>
 * </ul>
 */
public final class CgDefaultFont {

    /** The font file, as {@code CgIO} and CrystalGUI's {@code font-family} name it. */
    public static final String PATH = "crystalgraphics:fonts/Outfit-wght.ttf";

    private static final Map<Integer, CgFont> BY_WEIGHT = new ConcurrentHashMap<>();
    private static volatile byte[] data;

    private CgDefaultFont() {
    }

    /** Weight 400 at {@code px}. */
    public static CgFont regular(int px) {
        return at(400, px);
    }

    /** Weight 700 at {@code px}. */
    public static CgFont bold(int px) {
        return at(700, px);
    }

    /** {@code weight} (100 to 900, clamped) at {@code px}. */
    public static CgFont at(int weight, int px) {
        return unsized(weight).atSize(px);
    }

    /** {@code weight}, unsized: one per weight, loaded once. */
    public static CgFont unsized(int weight) {
        return BY_WEIGHT.computeIfAbsent(weight, w -> CgFont.loadAtWeight(bytes(), PATH, w));
    }

    /** The file's bytes, read once. */
    public static byte[] bytes() {
        byte[] d = data;
        if (d != null) return d;
        synchronized (CgDefaultFont.class) {
            if (data == null) data = read();
            return data;
        }
    }

    /**
     * Opens the regular and bold instances ahead, off the render thread: the start's GL-free worker calls it once the
     * text natives load. Anything else first asking for the font finds them open.
     */
    public static void ahead() {
        unsized(400);
        unsized(700);
    }

    private static byte[] read() {
        try (InputStream in = CgIO.openStream(PATH)) {
            if (in == null) throw new IllegalStateException("the engine's default font is missing from the jar: " + PATH);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + PATH, e);
        }
    }
}
