package com.crystalgraphics.api.font;

/**
 * The engine's default font: the platform's installed sans-serif ({@link CgGenericFamily#SANS_SERIF}: Arial on
 * Windows), opened ahead on a worker as the client starts, so no frame waits on the font scan.
 *
 * <pre>{@code
 * CgFont body = CgDefaultFont.regular(16);
 * CgFont title = CgDefaultFont.bold(32);
 * CgFontFamily family = CgFontFamily.of(body);       // fallback to installed fonts as for any font
 * }</pre>
 *
 * <ul>
 *   <li>Any thread. Before {@link #ahead} has run, the first call waits for {@link CgSystemFonts}' scan.</li>
 *   <li>Shared, as every installed font: never dispose one.</li>
 *   <li>Throws where no installed font draws Latin.</li>
 * </ul>
 */
public final class CgDefaultFont {

    private CgDefaultFont() {
    }

    /** Regular at {@code px}. */
    public static CgFont regular(int px) {
        return at(CgFontStyle.REGULAR, px);
    }

    /** Bold at {@code px}. */
    public static CgFont bold(int px) {
        return at(CgFontStyle.BOLD, px);
    }

    /** {@code style} at {@code px}. */
    public static CgFont at(CgFontStyle style, int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, style);
        if (face == null) throw new IllegalStateException("no installed font draws Latin: the engine has no default font");
        return fonts.load(face, style, px);
    }

    /**
     * Opens the regular and bold faces ahead, off the render thread: the start's GL-free worker calls it once the text
     * natives load. Anything else first asking for the font finds them open.
     */
    public static void ahead() {
        regular(16);
        bold(16);
    }
}
