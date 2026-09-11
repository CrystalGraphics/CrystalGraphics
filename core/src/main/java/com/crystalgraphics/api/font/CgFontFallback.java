package com.crystalgraphics.api.font;

import java.util.Locale;

/**
 * Supplies a font for a character none of a {@link CgFontFamily}'s own fonts can draw — the last
 * step of its per-character fallback, where an application asks the platform.
 *
 * <pre>{@code
 * // what an application does: anything the bundled font lacks comes from the installed fonts
 * CgFontFamily family = CgFontFamily.of(bundled)
 *         .withFallback(CgSystemFonts.get().fallback(Locale.getDefault()));
 *
 * // or a fixed answer for one script
 * CgFontFallback arabic = (codePoint, style, targetPx) ->
 *         Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC
 *                 ? arabicFont.atSize(targetPx) : null;
 * }</pre>
 *
 * <ul>
 *   <li>Return a size-bound font at exactly {@code targetPx} that draws {@code codePoint}, or
 *       {@code null}. Anything else is treated as {@code null}.</li>
 *   <li>A family asks once per code point and language and remembers a {@code null}, so a slow
 *       answer is paid once.</li>
 *   <li>Called from whichever thread shapes text, possibly several at once.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgFontFallback {

    CgFont fontFor(int codePoint, CgFontStyle style, int targetPx);

    /**
     * As {@link #fontFor(int, CgFontStyle, int)}, for a Han character whose own text says which
     * language it is in: Japanese beside kana, Korean beside Hangul. A fallback with no use for the
     * hint keeps this default, which ignores it.
     */
    default CgFont fontFor(int codePoint, CgFontStyle style, int targetPx, Locale language) {
        return fontFor(codePoint, style, targetPx);
    }
}
